/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.test.result.type;

/** The kind of result */
public enum ResultType {

  // First, three different reasons why the tests weren't even attempted:

  /**
   * The test was not run because the user chose to run tests with the --dry-run flag, which caused
   * the test runner to print out the names of tests that *would* have run without actually running
   * them.
   */
  DRY_RUN,
  /**
   * The test was not run because it was excluded by the user (e.g., specifying {@link
   * com.facebook.buck.cli.TestSelectorOptions}).
   */
  EXCLUDED,
  /**
   * The test was not run because it was excluded in source code, such as with JUnit's {@link
   * org.junit.Ignore} annotation or TestNG's {@link org.testng.annotations.Test#enabled()} field.
   */
  DISABLED,

  // Then, three different outcomes when the tests were attempted:

  /**
   * The test was attempted but did not run to completion. It was aborted because a
   * precondition/assumption of the test failed, see {@link org.junit.Assume}.
   */
  ASSUMPTION_VIOLATION,
  /**
   * The test ran, but it failed with either an assertion error or an unexpected uncaught exception.
   * Note that JUnit3 distinguishes between these outcomes (FAILURE and ERROR), while JUnit4 does
   * not.
   */
  FAILURE,
  /** The test ran successfully. */
  SUCCESS
}
