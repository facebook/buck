/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.util.autosparse;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AutoSparseStateEventsTest {
  private static String OUTPUT_TEMPLATE =
      String.join(
          "\n",
          "hg sparse -Tjson --import-rules /some/path --traceback finished with unexpected result:",
          "exit code: 42",
          "stdout:",
          "%s",
          "stderr:",
          "%s",
          "Traceback (most recent call last):",
          "  File \"/path/to/file1.py\", line 42, in frobnar",
          "    return adjust_the_jinglejangle(81)",
          "  File \"/path/to/file2.py\", line 19, in adjust_the_jinglejangle",
          "    raise SomeExceptionOrOther()",
          "SomeExceptionOrOther: failure message goes here",
          "abort: %s",
          "",
          "");
  private static String FAILURE_INTRO =
      "hg sparse failed to refresh your working copy, due to the following problems:";

  @Test
  public void emptyFailureDetailsTest() {
    AutoSparseStateEvents.SparseRefreshFailed event =
        makeOne("foo the bar the baz", "spam ham and eggs", "more nonsense output");
    assertEquals("", event.getFailureDetails());
  }

  @Test
  public void pendingFailure() {
    String pending_lines =
        String.join(
            "\n", "pending changes to 'foo/bar/baz.xml'", "pending changes to 'spam/ham/eggs.c'");
    String exception_line =
        "cannot change sparseness due to pending changes (delete the files or "
            + "use --force to bring them back dirty)";

    AutoSparseStateEvents.SparseRefreshFailed event = makeOne("", pending_lines, exception_line);

    assertEquals(
        String.join("\n", FAILURE_INTRO, "", pending_lines, exception_line, ""),
        event.getFailureDetails());
  }

  @Test
  public void noSuitableResponse() {
    String no_suitable_response_lines =
        String.join(
            "\n",
            "remote: Permission denied, please try again.",
            "remote: Permission denied, please try again."
                + "remote: Received disconnect from 2001:0db8:85a3:0000:0000:8a2e:0370:7334 port "
                + "22:2: Too many authentication failures for username from "
                + "2001:0db8:85a3:0000:0000:8a2e:0370:7335 port 57716 ssh2",
            "remote: Authentication failed.",
            "");
    String exception_line = "no suitable response from remote hg!";

    AutoSparseStateEvents.SparseRefreshFailed event =
        makeOne(no_suitable_response_lines, "", exception_line);

    assertEquals(
        String.join("\n", FAILURE_INTRO, "", no_suitable_response_lines.trim(), exception_line, ""),
        event.getFailureDetails());
  }

  private AutoSparseStateEvents.SparseRefreshFailed makeOne(
      String stdout, String stderr, String exception) {
    AutoSparseStateEvents.SparseRefreshStarted start =
        new AutoSparseStateEvents.SparseRefreshStarted();
    String output = String.format(OUTPUT_TEMPLATE, stdout, stderr, exception);
    return new AutoSparseStateEvents.SparseRefreshFailed(start, output);
  }
}
