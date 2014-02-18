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

package com.facebook.buck.junit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.XmlDomParser;

import org.junit.Rule;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.FileReader;
import java.io.IOException;

public class RunWithAnnotationIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Test
  public void testSimpleSuiteRun2TestCases() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "runwith", temporaryFolder);
    workspace.setUp();

    ProcessResult suiteTestResult = workspace.runBuckCommand("test", "//:SimpleSuiteTest");
    suiteTestResult.assertSuccess("Test should pass");
    assertThat(suiteTestResult.getStderr(), containsString("2 Passed"));
  }

  @Test
  public void testFailingSuiteRun3TestCasesWith1Failure() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "runwith", temporaryFolder);
    workspace.setUp();

    ProcessResult suiteTestResult = workspace.runBuckCommand("test", "//:FailingSuiteTest");
    suiteTestResult.assertTestFailure("Test should fail because of one of subtests failure");
    assertThat(suiteTestResult.getStderr(), containsString("2 Passed"));
    assertThat(suiteTestResult.getStderr(), containsString("1 Failed"));
  }

  @Test
  public void testParametrizedTestRun4Cases() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "runwith", temporaryFolder);
    workspace.setUp();

    ProcessResult suiteTestResult = workspace.runBuckCommand("test", "//:ParametrizedTest");
    suiteTestResult.assertSuccess("Test should pass");
    assertThat(suiteTestResult.getStderr(), containsString("4 Passed"));

    Document doc = XmlDomParser.parse(new InputSource(new FileReader(workspace.getFile(
        "buck-out/gen/__java_test_ParametrizedTest_output__/com.example.ParametrizedTest.xml"))),
        false);

    NodeList testNodes = doc.getElementsByTagName("test");
    assertEquals(4, testNodes.getLength());

    for (int i = 0; i < testNodes.getLength(); i++) {
      Node testNode = testNodes.item(i);

      String expectedName = String.format("parametrizedTest[%d]", i);
      assertEquals(expectedName, testNode.getAttributes().getNamedItem("name").getTextContent());

      String expectedStdout = String.format("Parameter: %d\n", i);
      assertEquals(
          expectedStdout,
          ((Element) testNode).getElementsByTagName("stdout").item(0).getTextContent());
    }

  }

}
