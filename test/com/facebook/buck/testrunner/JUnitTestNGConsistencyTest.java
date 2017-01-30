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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;

import com.facebook.buck.testutil.OutputHelper;
import com.facebook.buck.testutil.RegexMatcher;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Tests the behavior of JUnit and TestNG tests, making sure they behave consistently
 * with each other.
 */
public class JUnitTestNGConsistencyTest {

  @Rule
  public TemporaryPaths temporaryFolder = new TemporaryPaths();

  ProjectWorkspace workspace;

  @Before
  public void setUpWorkspace() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "consistency", temporaryFolder);
    workspace.setUp();
  }

  private void assertDoesNotContainClass(
      String reason, ProjectWorkspace.ProcessResult result, String className) {
    Pattern pattern = Pattern.compile("(?m)" + Pattern.quote(className) + "$");
    assertThat(reason, result.getStderr(), not(RegexMatcher.containsPattern(pattern)));
  }

  @Test
  public void checkPassingClassesForJUnitTests() throws IOException {
    checkPassingClassesReported("junit");
  }

  @Test
  public void checkPassingClassesForTestNGTests() throws IOException {
    checkPassingClassesReported("testng");
  }

  private void checkPassingClassesReported(String type) throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("test", "//" + type + ":passing");
    result.assertSuccess();

    // AbstractClassPassing and its inner classes
    assertDoesNotContainClass(
        "Outer class is abstract",
        result, "com.example.AbstractClassPassing");
    assertThat("Should include static inner class that extends abstract outer class.",
        result.getStderr(), OutputHelper.containsBuckTestOutputLine(
            "PASS", 2, 1, 0,
            "com.example.AbstractClassPassing$StaticInnerClass"));

    // AbstractTest
    assertDoesNotContainClass(
        "Class is abstract",
        result, "com.example.AbstractTest");

    // HasPassingInnerTests
    assertDoesNotContainClass(
        "Class has no tests",
        result, "com.example.HasPassingInnerTests");
    assertDoesNotContainClass(
        "Class is abstract",
        result, "com.example.HasFailingInnerTests$AbstractInner");
    assertThat("Should include static inner class that extends another inner class.",
        result.getStderr(), OutputHelper.containsBuckTestOutputLine(
            "PASS", 1, 1, 0,
            "com.example.HasPassingInnerTests$ConcreteInner"));

    // InterfaceTest
    assertDoesNotContainClass(
        "Class is an interface, even though it has default @Test methods",
        result, "com.example.InterfaceTest");

    // NotATestClass
    assertDoesNotContainClass(
        "Should not match on class or method just because it has the word 'Test'",
        result, "com.example.NotATestClass");
    assertDoesNotContainClass(
        "Should not match on inner class or method just because it has the word 'Test'",
        result, "com.example.NotATestClass$InnerNotATest");

    // PartiallyIgnoredPassingTest
    assertThat("Both would pass, but one is ignored",
        result.getStderr(), OutputHelper.containsBuckTestOutputLine(
            "PASS", 1, 1, 0,
            "com.example.PartiallyIgnoredPassingTest"));

    // TotallyIgnoredTest
    assertThat("Should report on class that has tests, but all are ignored",
        result.getStderr(), OutputHelper.containsBuckTestOutputLine(
            "NOTESTS", 0, 2, 0,
            "com.example.TotallyIgnoredTest"));
  }

  @Test
  public void checkFailingClassesForJUnitTests() throws IOException {
    checkFailingClassesReported("junit");
  }

  @Test
  public void checkFailingClassesForTestNGTests() throws IOException {
    checkFailingClassesReported("testng");
  }


  private void checkFailingClassesReported(String type) throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("test", "//" + type + ":failing");
    result.assertTestFailure();

    // AbstractClassFailing and its inner classes
    assertDoesNotContainClass(
        "No tests in outer class",
        result, "com.example.AbstractClassFailing");
    assertThat("Should include inner classes with un-ignored failing tests",
        result.getStderr(), OutputHelper.containsBuckTestOutputLine(
            "FAIL", 0, 1, 1,
            "com.example.AbstractClassFailing$StaticInnerClassWithAFailingTest"));
    assertThat("Should include static inner classes that can't be instantiated",
        result.getStderr(), OutputHelper.containsBuckTestOutputLine(
            "FAIL", 0, 0, 1,
            "com.example.AbstractClassFailing$StaticInnerClassThatFailsToInstantiate"));
    assertDoesNotContainClass(
        "Non-static inner class can't be instantiated",
        result, "com.example.AbstractClassFailing$NonStaticInnerClassThatCannotBeInstantiated");

    // HasFailingInnerTests and its inner classes
    assertDoesNotContainClass(
        "Class has no tests",
        result, "com.example.HasFailingInnerTests");
    assertDoesNotContainClass(
        "Class is abstract",
        result, "com.example.HasFailingInnerTests$AbstractInner");
    assertThat("One fails, one would fail but is ignored.",
        result.getStderr(), OutputHelper.containsBuckTestOutputLine(
            "FAIL", 0, 1, 1,
            "com.example.HasFailingInnerTests$ConcreteInner"));

    // PartiallyIgnoredFailingTest
    assertThat("One fails, one would succeed but is ignored, ",
        result.getStderr(), OutputHelper.containsBuckTestOutputLine(
            "FAIL", 0, 1, 1,
            "com.example.PartiallyIgnoredFailingTest"));
  }
}
