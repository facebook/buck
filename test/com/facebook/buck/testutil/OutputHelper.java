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

package com.facebook.buck.testutil;

import com.google.common.base.Joiner;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.hamcrest.Matcher;

/**
 * Utility class to provide helper methods for matching buck command line output lines in junit test
 * cases.
 */
public class OutputHelper {

  /** Utility class: do not instantiate. */
  private OutputHelper() {}

  /**
   * Regex for matching buck output time format. Based on time format definition from {@link
   * com.facebook.buck.util.TimeFormat}
   */
  public static final String BUCK_TIME_OUTPUT_FORMAT = "<?(\\d|\\.)+m?s";

  /**
   * Generates a regular expression that should match a buck test output line in following format:
   *
   * <pre>{@code
   * PASS    <100ms  1 Passed   0 Skipped   0 Failed   com.example.PassingTest
   * }</pre>
   *
   * @param status string representing expected output status ("PASS", "FAIL", etc.)
   * @param passedCount expected number of tests passed, or null if any number is acceptable
   * @param skippedCount expected number of tests skipped, or null if any number is acceptable
   * @param failedCount expected number of tests failed, or null if any number is acceptable
   * @param testClassName class name that is expected on the buck output line
   * @return a regular expression that should match a buck test output line
   */
  public static String createBuckTestOutputLineRegex(
      String status,
      @Nullable Integer passedCount,
      @Nullable Integer skippedCount,
      @Nullable Integer failedCount,
      String testClassName) {

    String passedPattern = (passedCount == null) ? "\\d+" : String.valueOf(passedCount);
    String skippedPattern = (skippedCount == null) ? "\\d+" : String.valueOf(skippedCount);
    String failedPattern = (failedCount == null) ? "\\d+" : String.valueOf(failedCount);

    String line =
        Joiner.on("\\s+")
            .join(
                Pattern.quote(status),
                BUCK_TIME_OUTPUT_FORMAT,
                passedPattern,
                "Passed",
                skippedPattern,
                "Skipped",
                failedPattern,
                "Failed",
                Pattern.quote(testClassName));
    return "(?m)^" + line + "$";
  }

  /**
   * Generates a regular expression that should match a buck test output line in following format:
   *
   * <pre>{@code
   * PASS    <100ms  1 Passed   0 Skipped   0 Failed   com.example.PassingTest
   * }</pre>
   *
   * @param status string representing expected output status ("PASS", "FAIL", etc.)
   * @param passedCount expected number of tests passed, or null if any number is acceptable
   * @param skippedCount expected number of tests skipped, or null if any number is acceptable
   * @param failedCount expected number of tests failed, or null if any number is acceptable
   * @param testClassName class name that is expected on the buck output line
   * @return a regular expression that should match a buck test output line
   */
  public static Matcher<CharSequence> containsBuckTestOutputLine(
      String status,
      @Nullable Integer passedCount,
      @Nullable Integer skippedCount,
      @Nullable Integer failedCount,
      String testClassName) {

    return RegexMatcher.containsRegex(
        createBuckTestOutputLineRegex(
            status, passedCount, skippedCount, failedCount, testClassName));
  }
}
