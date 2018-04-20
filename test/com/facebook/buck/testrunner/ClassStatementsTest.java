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

package com.facebook.buck.testrunner;

import static com.facebook.buck.testutil.OutputHelper.createBuckTestOutputLineRegex;
import static com.facebook.buck.testutil.RegexMatcher.containsRegex;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Rule;
import org.junit.Test;

public class ClassStatementsTest {

  private static final int BEFORE_CLASS_EXPECTED_RUNTIME = 250;

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void shouldFailWhenBeforeClassThrows() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "class_statements", temporaryFolder, true);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("test", "--all");
    result.assertTestFailure("Tests should fail");

    String stderr = result.getStderr();
    assertThat("Explanatory exception output is present", stderr, containsString("BOOM!"));

    assertThat(
        "Some tests still pass",
        stderr,
        containsRegex(
            createBuckTestOutputLineRegex("PASS", 3, 0, 0, "com.example.EverythingOKTest")));

    assertThat(
        stderr,
        containsRegex(
            createBuckTestOutputLineRegex("FAIL", 0, 0, 1, "com.example.HasBeforeClassFailure")));

    // Find the ms or s runtime.
    int actualRuntimeMs;
    Matcher matcher = Pattern.compile("^FAIL +(\\d+)ms", Pattern.MULTILINE).matcher(stderr);
    if (matcher.find()) {
      actualRuntimeMs = Integer.parseInt(matcher.group(1));
    } else {
      matcher = Pattern.compile("^FAIL +(\\d|\\.)+s", Pattern.MULTILINE).matcher(stderr);
      matcher.find();
      actualRuntimeMs = (int) (Double.parseDouble(matcher.group(1)) * 1000);
    }

    assertThat(
        "Reported a runtime that's at least the expected run time",
        actualRuntimeMs,
        greaterThanOrEqualTo(BEFORE_CLASS_EXPECTED_RUNTIME));
  }
}
