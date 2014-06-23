// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.buildtool;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionCacheChecker;
import com.google.devtools.build.lib.actions.ActionExecutionStatusReporter;
import com.google.devtools.build.lib.actions.ActionInputFileCache;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.Builder;
import com.google.devtools.build.lib.actions.BuilderUtils;
import com.google.devtools.build.lib.actions.DependentActionGraph;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.TestExecException;
import com.google.devtools.build.lib.skyframe.ActionExecutionInactivityWatchdog;
import com.google.devtools.build.lib.skyframe.ActionExecutionNode;
import com.google.devtools.build.lib.skyframe.ArtifactNode;
import com.google.devtools.build.lib.skyframe.ArtifactNode.OwnedArtifact;
import com.google.devtools.build.lib.skyframe.NodeTypes;
import com.google.devtools.build.lib.skyframe.SkyframeActionExecutor;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.util.BlazeClock;
import com.google.devtools.build.lib.util.ExitCausingException;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.skyframe.AutoUpdatingGraph.NodeProgressReceiver;
import com.google.devtools.build.skyframe.CycleInfo;
import com.google.devtools.build.skyframe.ErrorInfo;
import com.google.devtools.build.skyframe.Node;
import com.google.devtools.build.skyframe.NodeKey;
import com.google.devtools.build.skyframe.NodeType;
import com.google.devtools.build.skyframe.UpdateResult;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link Builder} implementation driven by Skyframe.
 */
class SkyframeBuilder implements Builder {

  private final SkyframeExecutor skyframeExecutor;
  private final boolean keepGoing;
  private final int numJobs;
  private final ActionInputFileCache fileCache;
  private final ActionCacheChecker actionCacheChecker;
  private final int progressReportInterval;

  SkyframeBuilder(SkyframeExecutor skyframeExecutor, ActionCacheChecker actionCacheChecker,
      boolean keepGoing, int numJobs, ActionInputFileCache fileCache, int progressReportInterval) {
    this.skyframeExecutor = skyframeExecutor;
    this.actionCacheChecker = actionCacheChecker;
    this.keepGoing = keepGoing;
    this.numJobs = numJobs;
    this.fileCache = fileCache;
    this.progressReportInterval = progressReportInterval;
  }

  @Override
  public void buildArtifacts(Set<Artifact> artifacts,
      Set<Artifact> exclusiveTestArtifacts,
      DependentActionGraph forwardGraph, Executor executor,
      ModifiedFileSet modifiedFileSet, Set<Artifact> builtArtifacts)
      throws BuildFailedException, ExitCausingException, TestExecException, InterruptedException {
    skyframeExecutor.prepareExecution();
    skyframeExecutor.setFileCache(fileCache);
    // Note that executionProgressReceiver accesses builtArtifacts concurrently (after wrapping in a
    // synchronized collection), so unsynchronized access to this variable is unsafe while it runs.
    ExecutionNodeProgressReceiver executionProgressReceiver =
        new ExecutionNodeProgressReceiver(artifacts, builtArtifacts);

    boolean success = false;
    UpdateResult<ArtifactNode> result = null;

    ActionExecutionStatusReporter statusReporter = ActionExecutionStatusReporter.create(
        skyframeExecutor.getReporter(), executor, skyframeExecutor.getEventBus());

    AtomicBoolean isBuildingExclusiveArtifacts = new AtomicBoolean(false);
    ActionExecutionInactivityWatchdog watchdog = new ActionExecutionInactivityWatchdog(
        executionProgressReceiver.createInactivityMonitor(statusReporter),
        executionProgressReceiver.createInactivityReporter(statusReporter,
            isBuildingExclusiveArtifacts), progressReportInterval);

    skyframeExecutor.setActionExecutionProgressReportingObjects(executionProgressReceiver,
        executionProgressReceiver, statusReporter);
    watchdog.start();

    skyframeExecutor.findArtifactConflicts();
    // TODO(bazel-team): Put this call inside SkyframeExecutor#handleDiffs() when legacy
    // codepath is no longer used. [skyframe-execution]
    skyframeExecutor.informAboutNumberOfModifiedFiles();
    try {
      result = skyframeExecutor.buildArtifacts(executor, artifacts, keepGoing, numJobs,
          actionCacheChecker, executionProgressReceiver);
      // progressReceiver is finished, so unsynchronized access to builtArtifacts is now safe.
      builtArtifacts.addAll(ArtifactNode.artifacts(result.<OwnedArtifact>keyNames()));
      success = processResult(result, keepGoing, skyframeExecutor);

      // Run exclusive tests: either tagged as "exclusive" or is run in an invocation with
      // --test_output=streamed.
      isBuildingExclusiveArtifacts.set(true);
      for (Artifact exclusiveArtifact : exclusiveTestArtifacts) {
        // Since only one artifact is being built at a time, we don't worry about an artifact being
        // built and then the build being interrupted.
        result = skyframeExecutor.buildArtifacts(executor, ImmutableSet.of(exclusiveArtifact),
            keepGoing, numJobs, actionCacheChecker, null);
        success = processResult(result, keepGoing, skyframeExecutor) && success;
      }
    } finally {
      watchdog.stop();
      skyframeExecutor.setActionExecutionProgressReportingObjects(null, null, null);
      statusReporter.unregisterFromEventBus();
    }

    if (!success) {
      throw new BuildFailedException();
    }
  }

  /**
   * Process the Skyframe update, taking into account the keepGoing setting.
   *
   * Returns false if the update() failed, but we should continue. Returns true on success.
   * Throws on fail-fast failures.
   */
  private static boolean processResult(UpdateResult<?> result, boolean keepGoing,
      SkyframeExecutor skyframeExecutor) throws BuildFailedException, TestExecException {
    if (result.hasError()) {
      boolean hasCycles = false;
      for (Map.Entry<NodeKey, ErrorInfo> entry : result.errorMap().entrySet()) {
        Iterable<CycleInfo> cycles = entry.getValue().getCycleInfo();
        skyframeExecutor.reportCycles(cycles, entry.getKey());
        hasCycles |= !Iterables.isEmpty(cycles);
      }
      if (keepGoing) {
        return false;
      }
      if (hasCycles) {
        throw new BuildFailedException();
      } else {
        // Need to wrap exception for rethrowCause.
        BuilderUtils.rethrowCause(
          new Exception(Preconditions.checkNotNull(result.getError().getException())));
      }
    }
    return true;
  }

  @Override
  public int getPercentageCached() {
    // TODO(bazel-team): In the future this could be provided by the evaluator.
    return 0;
  }

  /**
   * Listener for executed actions and built artifacts. We use a listener so that we have an
   * accurate set of successfully run actions and built artifacts, even if the build is interrupted.
   */
  private static class ExecutionNodeProgressReceiver implements NodeProgressReceiver,
      SkyframeActionExecutor.ProgressSupplier, SkyframeActionExecutor.ActionCompletedReceiver {
    // Must be thread-safe!
    private final Set<Artifact> builtArtifacts;
    private final ImmutableSet<Artifact> artifacts;
    private final Set<NodeKey> enqueuedActions = Sets.newConcurrentHashSet();
    private final Set<Action> completedActions = Sets.newConcurrentHashSet();
    private final Object activityIndicator = new Object();

    /**
     * {@code builtArtifacts} is accessed through a synchronized set, and so no other access to it
     * is permitted while this receiver is active.
     */
    ExecutionNodeProgressReceiver(Set<Artifact> artifacts, Set<Artifact> builtArtifacts) {
      this.artifacts = ImmutableSet.copyOf(artifacts);
      this.builtArtifacts = Collections.synchronizedSet(builtArtifacts);
    }

    @Override
    public void invalidated(Node node, InvalidationState state) {}

    @Override
    public void enqueueing(NodeKey nodeKey) {
      if (ActionExecutionNode.isReportWorthyAction(nodeKey)) {
        enqueuedActions.add(nodeKey);
      }
    }

    /**
     * We add to the list of built artifacts here, to ensure we have an accurate list of all
     * artifacts that were built if the build is interrupted. We add all (top-level) outputs of a
     * completed action to the built artifacts as soon as we are notified that the action has
     * completed. Note that this happens before the corresponding ArtifactNode is created in
     * Skyframe.
     *
     * <p>Adding action outputs leaves out two cases -- when the top-level artifact's generating
     * action did not need to be run (so we are not notified here that it was built), and when the
     * top-level artifact is a source artifact (so it had no generating action). In those cases, we
     * add the artifact when we are notified that the corresponding ArtifactNode is built.
     *
     * <p>If Blaze were more tolerant to inconsistencies between the events fired and the artifacts
     * known to be built, we could just add artifacts here via their ArtifactNodes being built, or
     * even avoid this listener entirely.
     */
    @Override
    public void evaluated(NodeKey nodeKey, Node node, EvaluationState state) {
      NodeType type = nodeKey.getNodeType();
      if (type == NodeTypes.ARTIFACT) {
        Artifact artifact = ArtifactNode.artifact(nodeKey);
        if ((state == EvaluationState.CLEAN || artifact.isSourceArtifact())
            && artifacts.contains(artifact)) {
          // If an artifact was built this run, it will already have been added below by its
          // generating action. But a cached artifact must be added here, as must source artifacts.
          builtArtifacts.add(artifact);
        }
      } else if (type == NodeTypes.ACTION_EXECUTION) {
        // We monitor actions because it is possible that an action will successfully run but its
        // output artifact's node will not be created before the build is interrupted. By adding the
        // artifact to the set of built artifacts here, we avoid an inconsistency between the built
        // artifacts here and successful actions (as given by events posted from the node builder).
        for (Artifact artifact : ((Action) nodeKey.getNodeName()).getOutputs()) {
          if (artifacts.contains(artifact)) {
            builtArtifacts.add(artifact);
          }
        }
      }
    }

    /**
     * {@inheritDoc}
     *
     * <p>This method adds the action to {@link #completedActions} and notifies the
     * {@link #activityIndicator}.
     *
     * <p>We could do all of this in the {@link #evaluated} method too, but as it happens the action
     * executor tells the reporter about the completed action before the node is inserted into the
     * graph, so the reporter would find out about the completed action sooner than we could
     * have updated {@link #completedActions}, which would result in incorrect numbers on the
     * progress messages.
     */
    @Override
    public void actionCompleted(Action a) {
      if (ActionExecutionNode.isReportWorthyAction(a)) {
        completedActions.add(a);
        synchronized (activityIndicator) {
          activityIndicator.notifyAll();
        }
      }
    }

    @Override
    public String getProgressString() {
      return String.format("[%d/%d]", completedActions.size(), enqueuedActions.size());
    }

    ActionExecutionInactivityWatchdog.InactivityMonitor createInactivityMonitor(
        final ActionExecutionStatusReporter statusReporter) {
      return new ActionExecutionInactivityWatchdog.InactivityMonitor() {

        @Override
        public boolean hasStarted() {
          return !enqueuedActions.isEmpty();
        }

        @Override
        public int getPending() {
          return statusReporter.getCount();
        }

        @Override
        public int waitForNextCompletion(int timeoutMilliseconds) throws InterruptedException {
          synchronized (activityIndicator) {
            int before = completedActions.size();
            long startTime = BlazeClock.instance().currentTimeMillis();
            while (true) {
              activityIndicator.wait(timeoutMilliseconds);

              int completed = completedActions.size() - before;
              long now = 0;
              if (completed > 0 || (startTime + timeoutMilliseconds) <= (now = BlazeClock.instance()
                  .currentTimeMillis())) {
                // Some actions completed, or timeout fully elapsed.
                return completed;
              } else {
                // Spurious Wakeup -- no actions completed and there's still time to wait.
                timeoutMilliseconds -= now - startTime;  // account for elapsed wait time
                startTime = now;
              }
            }
          }
        }
      };
    }

    ActionExecutionInactivityWatchdog.InactivityReporter createInactivityReporter(
        final ActionExecutionStatusReporter statusReporter,
        final AtomicBoolean isBuildingExclusiveArtifacts) {
      return new ActionExecutionInactivityWatchdog.InactivityReporter() {
        @Override
        public void maybeReportInactivity() {
          // Do not report inactivity if we are currently running an exclusive test or a streaming
          // action (in practice only tests can stream and it implicitly makes them exclusive).
          if (!isBuildingExclusiveArtifacts.get()) {
            statusReporter.showCurrentlyExecutingActions(
                ExecutionNodeProgressReceiver.this.getProgressString() + " ");
          }
        }
      };
    }
  }
}