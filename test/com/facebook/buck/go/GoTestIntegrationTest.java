/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.go;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import java.io.IOException;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class GoTestIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  public ProjectWorkspace workspace;

  @Before
  public void ensureGoIsAvailable() throws IOException, InterruptedException {
    GoAssumptions.assumeGoCompilerAvailable();
  }

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "go_test", tmp);
    workspace.setUp();
  }

  @Test
  public void testGoTest() throws IOException {
    // This test should pass.
    ProcessResult result1 = workspace.runBuckCommand("test", "//:test-success");
    result1.assertSuccess();
    workspace.resetBuildLogFile();

    // This test should fail.
    ProcessResult result2 = workspace.runBuckCommand("test", "//:test-failure");
    result2.assertTestFailure();
    assertThat(
        "`buck test` should fail because TestAdd2() failed.",
        result2.getStderr(),
        containsString("TestAdd2"));
  }

  @Test
  public void testGoTestAfterChange() throws IOException {
    // This test should pass.
    workspace.runBuckCommand("test", "//:test-success").assertSuccess();

    workspace.replaceFileContents("buck_base/base.go", "n1 + n2", "n1 + n2 + 1");
    workspace.runBuckCommand("test", "//:test-success").assertTestFailure();

    workspace.replaceFileContents("buck_base/base.go", "n1 + n2 + 1", "n1 + n2 * 1");
    workspace.runBuckCommand("test", "//:test-success").assertSuccess();
  }

  @Ignore
  @Test
  public void testGoInternalTest() throws IOException {
    ProcessResult result1 = workspace.runBuckCommand("test", "//:test-success-internal");
    result1.assertSuccess();
  }

  @Test
  public void testWithResources() throws IOException {
    ProcessResult result1 = workspace.runBuckCommand("test", "//:test-with-resources");
    result1.assertSuccess();
  }

  @Test(expected = HumanReadableException.class)
  public void testGoInternalTestInTestList() throws IOException {
    workspace.runBuckCommand("test", "//:test-success-bad");
  }

  @Test
  public void testGoTestTimeout() throws IOException {
    ProcessResult result = workspace.runBuckCommand("test", "//:test-spinning");
    result.assertTestFailure("test timed out after 500ms");
  }

  @Test
  public void testGoPanic() throws IOException {
    ProcessResult result2 = workspace.runBuckCommand("test", "//:test-panic");
    result2.assertTestFailure();
    assertThat(
        "`buck test` should fail because TestPanic() failed.",
        result2.getStderr(),
        containsString("TestPanic"));
  }

  @Test
  public void testSubTests() throws IOException {
    GoAssumptions.assumeGoVersionAtLeast("1.7.0");
    ProcessResult result = workspace.runBuckCommand("test", "//:subtests");
    result.assertSuccess();
  }

  @Test
  public void testIndirectDeps() throws IOException {
    ProcessResult result = workspace.runBuckCommand("test", "//add:test-add13");
    result.assertSuccess();
  }

  @Test
  public void testLibWithCgoDeps() throws IOException {
    ProcessResult result = workspace.runBuckCommand("test", "//cgo/lib:all_tests");
    result.assertSuccess();
  }

  @Test
  public void testGenRuleAsSrc() throws IOException {
    ProcessResult result = workspace.runBuckCommand("test", "//genrule_as_src:test");
    result.assertSuccess();
  }

  @Test
  public void testGenRuleWithLibAsSrc() throws IOException {
    ProcessResult result = workspace.runBuckCommand("test", "//genrule_wtih_lib_as_src:test");
    result.assertSuccess();
  }
}
