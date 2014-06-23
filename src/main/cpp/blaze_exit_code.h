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
//
// blaze_exit_code.h: Exit codes for Blaze.
// Must be kept in sync with the Java counterpart under
// com/google/devtools/build/lib/util/ExitCode.java

#ifndef DEVTOOLS_BLAZE_MAIN_BLAZE_EXIT_CODE_H_
#define DEVTOOLS_BLAZE_MAIN_BLAZE_EXIT_CODE_H_

namespace blaze_exit_code {

enum ExitCodes {
  // Success.
  SUCCESS = 0,

  // Command Line Problem, Bad or Illegal flags or command combination, or
  // Bad environment variables. The user must modify their command line.
  BAD_ARGV = 2,

  LOCAL_ENVIRONMENTAL_ERROR = 36,

  // Unexpected server termination, due to e.g. external SIGKILL, misplaced
  // System.exit(), or a JVM crash.
  // This exit code should be a last resort.
  INTERNAL_ERROR = 37,
};

}  // namespace blaze_exit_code

#endif  // DEVTOOLS_BLAZE_MAIN_BLAZE_EXIT_CODE_H_