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

package com.facebook.buck.testutil.integration;

import static com.facebook.buck.util.MoreStringsForTests.normalizeNewlines;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.testutil.OutputHelper;
import com.facebook.buck.testutil.ProcessResult;
import java.io.IOException;

/**
 * A collection of {@code assert} functions which make it easy to compare Buck output to expected
 * output contained in code or a file.
 */
public final class ProcessOutputAssertions {

  private ProcessOutputAssertions() {}

  /**
   * Asserts that the result succeeded and that the lines printed to stdout are the same as those in
   * the specified file, though not necessarily in the same order.
   */
  public static void assertOutputMatchesFileContents(
      String expectedOutputFile, ProcessResult result, ProjectWorkspace workspace)
      throws IOException {
    result.assertSuccess();

    assertEquals(
        normalizeNewlines(workspace.getFileContents(expectedOutputFile)),
        OutputHelper.normalizeOutputLines(normalizeNewlines(result.getStdout())));
  }

  /** Same as {@link #assertOutputMatchesFileContents} but doesn't attempt to sort output */
  public static void assertOutputMatchesFileContentsExactly(
      String expectedOutputFile, ProcessResult result, ProjectWorkspace workspace)
      throws IOException {
    result.assertSuccess();

    assertEquals(
        normalizeNewlines(workspace.getFileContents(expectedOutputFile)),
        normalizeNewlines(result.getStdout()));
  }

  /**
   * Asserts that the result succeeded and that the content of the JSON output is equal to that in
   * the specified file. Note that this method does not require the contents of the expected output
   * file or the process output to be sorted in any particular order.
   */
  public static void assertJSONOutputMatchesFileContents(
      String expectedOutputFile, ProcessResult result, ProjectWorkspace workspace)
      throws IOException {
    result.assertSuccess();

    assertEquals(
        OutputHelper.parseJSON(workspace.getFileContents(expectedOutputFile)),
        OutputHelper.parseJSON(result.getStdout()));
  }

  /**
   * Asserts that the result succeeded and that the lines printed to stdout are identical to {@code
   * sortedExpectedOutput}. The stdout of {@code result} is sorted by line before being compared to
   * {@code sortedExpectedOutput} to ensure deterministic results.
   */
  public static void assertOutputMatches(String sortedExpectedOutput, ProcessResult result) {
    result.assertSuccess();

    assertEquals(
        sortedExpectedOutput,
        OutputHelper.normalizeOutputLines(normalizeNewlines(result.getStdout())).trim());
  }

  /**
   * Asserts that the result succeeded and that the lines printed to stdout are identical to {@code
   * sortedExpectedOutput} with the exception of path separators. Path separators in the expected
   * output are converted to the separator for the current platform before being compared to the
   * stdout of the result.
   */
  public static void assertOutputMatchesPaths(String sortedExpectedOutput, ProcessResult result) {
    assertOutputMatches(MorePaths.pathWithPlatformSeparators(sortedExpectedOutput), result);
  }

  /**
   * Asserts that the result succeeded and that the string printed to stdout is exactly identical to
   * {@code expectedOutput}. No sorting of lines is done, though this method does normalize newlines
   * (standardizing on the Unix newlines).
   */
  public static void assertOutputMatchesExactly(String expectedOutput, ProcessResult result) {
    result.assertSuccess();

    assertEquals(normalizeNewlines(expectedOutput), normalizeNewlines(result.getStdout()));
  }

  /**
   * Asserts that the result failed and that the error message contains the substring {@code
   * expectedSubstring}. The substring should be short but unique, as error messages are likely to
   * have things like stack traces which will differ on every run.
   */
  public static void assertParseErrorWithMessageSubstring(
      String expectedSubstring, ProcessResult result) {
    result.assertParseError();

    assertTrue(
        String.format(
            "Expected error message to contain the string '%s'. Message: %s",
            expectedSubstring, result.getStderr()),
        result.getStderr().contains(expectedSubstring));
  }
}
