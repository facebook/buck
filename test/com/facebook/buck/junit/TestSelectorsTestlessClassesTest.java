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

package com.facebook.buck.junit;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

/**
 * Test to demonstrate how, with or without the use of --filter, a class that contains no @Test
 * methods will never result in an internal NoTestsRemainException being returned to the user as an
 * error.  See {@link JUnitRunner#interpretResults(java.util.List)} for why this is weird.
 */
public class TestSelectorsTestlessClassesTest {

  private ProjectWorkspace workspace;

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Before
  public void setupWorkspace() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "test_selectors_testless_classes", temporaryFolder);
    workspace.setUp();
  }

  @Test
  public void shouldNotFailWhenNotUsingAFilter() throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("test", "--all");
    result.assertSuccess(
        "Testless classes should not cause NoTestsRemainException, " +
        "when filtering is *NOT* used!");
    assertThat(result.getStderr(), containsString(
        "PASS   <100ms  0 Passed   0 Skipped   0 Failed   com.example.ClassWithoutTestsA"));
    assertThat(result.getStderr(), containsString(
        "PASS   <100ms  0 Passed   0 Skipped   0 Failed   com.example.ClassWithoutTestsB"));
  }

  @Test
  public void shouldNotFailWhenUsingAFilterThatIncludesNothing() throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("test", "--all", "--filter", "XYZ");
    result.assertSuccess(
        "Testless classes should not cause NoTestsRemainException, " +
        "even when filtering *IS* used, but it includes no actual tests!");
    assertThat("None of the tests should be mentioned in the output",
        result.getStderr(),
        not(containsString("com.example")));
  }

  @Test
  public void shouldNotFailWhenUsingAFilterThatIncludesSomething() throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("test", "--all", "--filter", "com.example");
    result.assertSuccess(
        "Testless classes should not cause NoTestsRemainException, " +
        "even when filtering *IS* used, and it includes real tests!");
    assertThat("Some tests should be mentioned in the output",
        result.getStderr(),
        containsString("com.example"));
  }
}
