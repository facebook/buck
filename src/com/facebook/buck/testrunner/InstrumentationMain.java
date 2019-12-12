/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.testrunner;

public class InstrumentationMain {

  private InstrumentationMain() {
    // Launcher class.
  }

  public static void main(String[] args) {
    try {
      InstrumentationTestRunner.fromArgs(args).run();
    } catch (Throwable e) {
      e.printStackTrace();
    } finally {
      // Explicitly exit to force the test runner to complete even if tests have sloppily left
      // behind non-daemon threads that would have otherwise forced the process to wait and
      // eventually timeout.
      //
      // Separately, we're using a successful exit code regardless of test outcome since the runner
      // is designed to execute all tests and produce a report of success or failure.  We've done
      // that successfully if we've gotten here.
      System.exit(0);
    }
  }
}
