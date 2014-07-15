/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.testutil;

import com.google.common.base.Joiner;

import java.util.regex.Pattern;

/**
 * Utility class to provide helper methods for matching buck command line output lines in junit
 * test cases.
 */
public class OutputHelper {

  /** Utility class: do not instantiate. */
  private OutputHelper() {}

  /**
   * Regex for matching buck output time format.
   * Based on time format definition from {@link com.facebook.buck.util.TimeFormat}
   */
  public static final String BUCK_TIME_OUTPUT_FORMAT = "<?\\d+m?s";

  /**
   * Generates a regular expression that should match a buck test output line in following format:
   * <pre>
   * {@code
   * PASS    <100ms  1 Passed   0 Skipped   0 Failed   com.example.PassingTest
   * }
   * </pre>
   *
   * @param status string representing expected output status ("PASS", "FAIL", etc.)
   * @param passedCount expected number of tests passed
   * @param skippedCount expected number of tests skipped
   * @param failedCount expected number of tests failed
   * @param testClassName class name that is expected on the buck output line
   * @return a regular expression that should match a buck test output line
   */
  public static String createBuckTestOutputLineRegex(
      String status,
      int passedCount,
      int skippedCount,
      int failedCount,
      String testClassName) {
    return Joiner.on("").join(
        Pattern.quote(status), "\\s*",
        BUCK_TIME_OUTPUT_FORMAT, "\\s*",
        Pattern.quote(String.valueOf(passedCount) + " Passed"), "\\s*",
        Pattern.quote(String.valueOf(skippedCount) + " Skipped"), "\\s*",
        Pattern.quote(String.valueOf(failedCount) + " Failed"), "\\s*",
        Pattern.quote(testClassName)
    );
  }

}
