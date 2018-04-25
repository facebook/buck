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

import static com.facebook.buck.testutil.OutputHelper.containsBuckTestOutputLine;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.RegexMatcher;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Test to demonstrate how, with or without the use of --filter, a class that contains no @Test
 * methods will never result in an internal NoTestsRemainException being returned to the user as an
 * error. See {@link JUnitRunner#combineResults} for why this is weird.
 */
public class TestSelectorsTestlessClassesTest {

  private ProjectWorkspace workspace;

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Before
  public void setupWorkspace() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "test_selectors_testless_classes", temporaryFolder);
    workspace.setUp();
  }

  @Test
  public void shouldNotFailWhenNotUsingAFilter() throws IOException {
    ProcessResult result = workspace.runBuckCommand("test", "--all");
    result.assertSuccess(
        "Testless classes should not cause NoTestsRemainException, "
            + "when filtering is *NOT* used!");
    assertThat(
        "Should not list classes without tests under junit 4.8.2",
        result.getStderr(),
        not(RegexMatcher.containsRegex("com.example.ClassWithoutTestsA")));
    assertThat(
        "Should not list classes without tests under junit 4.11",
        result.getStderr(),
        not(RegexMatcher.containsRegex("com.example.ClassWithoutTestsB")));
  }

  @Test
  public void shouldNotFailWhenUsingAFilterThatIncludesNothing() throws IOException {
    ProcessResult result = workspace.runBuckCommand("test", "--all", "--filter", "XYZ");
    result.assertSuccess(
        "Testless classes should not cause NoTestsRemainException, "
            + "even when filtering *IS* used, but it includes no actual tests!");
    assertThat(
        "None of the tests should be mentioned in the output",
        result.getStderr(),
        not(containsString("com.example")));
  }

  @Test
  public void shouldNotFailWhenUsingAFilterThatIncludesSomething() throws IOException {
    ProcessResult result = workspace.runBuckCommand("test", "--all", "--filter", "com.example.+");
    result.assertSuccess(
        "Testless classes should not cause NoTestsRemainException, "
            + "even when filtering *IS* used, and it includes real tests!");
    assertThat(
        "Tests should be mentioned in the junit 4.8.2 output",
        result.getStderr(),
        containsBuckTestOutputLine("PASS", 2, 0, 0, "com.example.ClassWithTestsA"));
    assertThat(
        "Tests should be mentioned in the junit 4.11 output",
        result.getStderr(),
        containsBuckTestOutputLine("PASS", 2, 0, 0, "com.example.ClassWithTestsB"));
  }
}
