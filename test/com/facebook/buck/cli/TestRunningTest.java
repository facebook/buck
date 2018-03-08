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

package com.facebook.buck.cli;

import static com.facebook.buck.rules.BuildRuleSuccessType.BUILT_LOCALLY;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.DefaultJavaPackageFinder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaLibraryDescriptionArg;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildResult;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildEngine;
import com.facebook.buck.rules.FakeTestRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.shell.GenruleDescription;
import com.facebook.buck.shell.GenruleDescriptionArg;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.ExecutionOrderAwareFakeStep;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.test.FakeTestResults;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.ExitCode;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.hamcrest.Matchers;
import org.junit.BeforeClass;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class TestRunningTest {

  private static ImmutableSortedSet<String> pathsFromRoot;
  private static ImmutableSet<String> pathElements;

  private static final TestRunningOptions DEFAULT_OPTIONS = TestRunningOptions.builder().build();
  private static final Logger LOG = Logger.get(TestRunningTest.class);

  @BeforeClass
  public static void setUp() {
    pathsFromRoot = ImmutableSortedSet.of("java/");
    pathElements = ImmutableSet.of("src", "src-gen");
  }

  /**
   * If the source paths specified are all generated files, then our path to source tmp should be
   * absent.
   */
  @Test
  public void testGeneratedSourceFile() {
    BuildTarget genSrcTarget = BuildTargetFactory.newInstance("//:gensrc");

    TargetNode<GenruleDescriptionArg, GenruleDescription> sourceGenerator =
        GenruleBuilder.newGenruleBuilder(genSrcTarget)
            .setOut("com/facebook/GeneratedFile.java")
            .build();

    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//:lib");
    TargetNode<JavaLibraryDescriptionArg, JavaLibraryDescription> javaLibraryNode =
        JavaLibraryBuilder.createBuilder(javaLibraryTarget).addSrcTarget(genSrcTarget).build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(sourceGenerator, javaLibraryNode);

    BuildRuleResolver ruleResolver = new TestBuildRuleResolver(targetGraph);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);
    JavaLibrary javaLibrary = (JavaLibrary) ruleResolver.requireRule(javaLibraryTarget);

    DefaultJavaPackageFinder defaultJavaPackageFinder = createMock(DefaultJavaPackageFinder.class);

    Object[] mocks = new Object[] {defaultJavaPackageFinder};
    replay(mocks);

    ImmutableSet<String> result =
        TestRunning.getPathToSourceFolders(
            javaLibrary, resolver, ruleFinder, defaultJavaPackageFinder);

    assertThat(
        "No path should be returned if the library contains only generated files.",
        result,
        Matchers.empty());

    verify(mocks);
  }

  /**
   * If the source paths specified are all for non-generated files then we should return the correct
   * source tmp corresponding to a non-generated source path.
   */
  @Test
  public void testNonGeneratedSourceFile() {
    Path pathToNonGenFile = Paths.get("package/src/SourceFile1.java");

    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//foo:bar");
    TargetNode<JavaLibraryDescriptionArg, JavaLibraryDescription> javaLibraryNode =
        JavaLibraryBuilder.createBuilder(javaLibraryTarget).addSrc(pathToNonGenFile).build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(javaLibraryNode);

    BuildRuleResolver ruleResolver = new TestBuildRuleResolver(targetGraph);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);
    JavaLibrary javaLibrary = (JavaLibrary) ruleResolver.requireRule(javaLibraryTarget);

    DefaultJavaPackageFinder defaultJavaPackageFinder = createMock(DefaultJavaPackageFinder.class);
    expect(defaultJavaPackageFinder.getPathsFromRoot()).andReturn(pathsFromRoot);
    expect(defaultJavaPackageFinder.getPathElements()).andReturn(pathElements);

    replay(defaultJavaPackageFinder);

    ImmutableSet<String> result =
        TestRunning.getPathToSourceFolders(
            javaLibrary, resolver, ruleFinder, defaultJavaPackageFinder);

    String expected = javaLibrary.getProjectFilesystem().getRootPath().resolve("package/src") + "/";
    assertEquals(
        "All non-generated source files are under one source tmp.",
        ImmutableSet.of(expected),
        result);

    verify(defaultJavaPackageFinder);
  }

  @Test
  public void testNonGeneratedSourceFileWithoutPathElements() {
    Path pathToNonGenFile = Paths.get("package/src/SourceFile1.java");

    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//foo:bar");
    TargetNode<JavaLibraryDescriptionArg, JavaLibraryDescription> javaLibraryNode =
        JavaLibraryBuilder.createBuilder(javaLibraryTarget).addSrc(pathToNonGenFile).build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(javaLibraryNode);

    BuildRuleResolver ruleResolver = new TestBuildRuleResolver(targetGraph);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);
    JavaLibrary javaLibrary = (JavaLibrary) ruleResolver.requireRule(javaLibraryTarget);

    DefaultJavaPackageFinder defaultJavaPackageFinder = createMock(DefaultJavaPackageFinder.class);
    expect(defaultJavaPackageFinder.getPathsFromRoot()).andReturn(pathsFromRoot);
    expect(defaultJavaPackageFinder.getPathElements()).andReturn(ImmutableSet.of("/"));

    replay(defaultJavaPackageFinder);

    TestRunning.getPathToSourceFolders(javaLibrary, resolver, ruleFinder, defaultJavaPackageFinder);

    verify(defaultJavaPackageFinder);
  }
  /**
   * If the source paths specified are from the new unified source tmp then we should return the
   * correct source tmp corresponding to the unified source path.
   */
  @Test
  public void testUnifiedSourceFile() {
    Path pathToNonGenFile = Paths.get("java/package/src/SourceFile1.java");

    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//foo:bar");
    TargetNode<JavaLibraryDescriptionArg, JavaLibraryDescription> javaLibraryNode =
        JavaLibraryBuilder.createBuilder(javaLibraryTarget).addSrc(pathToNonGenFile).build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(javaLibraryNode);

    BuildRuleResolver ruleResolver = new TestBuildRuleResolver(targetGraph);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);
    JavaLibrary javaLibrary = (JavaLibrary) ruleResolver.requireRule(javaLibraryTarget);

    DefaultJavaPackageFinder defaultJavaPackageFinder = createMock(DefaultJavaPackageFinder.class);
    expect(defaultJavaPackageFinder.getPathsFromRoot()).andReturn(pathsFromRoot);

    Object[] mocks = new Object[] {defaultJavaPackageFinder};
    replay(mocks);

    ImmutableSet<String> result =
        TestRunning.getPathToSourceFolders(
            javaLibrary, resolver, ruleFinder, defaultJavaPackageFinder);

    assertEquals(
        "All non-generated source files are under one source tmp.",
        ImmutableSet.of("java/"),
        result);

    verify(mocks);
  }

  /**
   * If the source paths specified contains one source path to a non-generated file then we should
   * return the correct source tmp corresponding to that non-generated source path. Especially when
   * the generated file comes first in the ordered set.
   */
  @Test
  public void testMixedSourceFile() {
    BuildTarget genSrcTarget = BuildTargetFactory.newInstance("//:gensrc");

    TargetNode<GenruleDescriptionArg, GenruleDescription> sourceGenerator =
        GenruleBuilder.newGenruleBuilder(genSrcTarget)
            .setOut("com/facebook/GeneratedFile.java")
            .build();

    Path pathToNonGenFile1 = Paths.get("package/src/SourceFile1.java");
    Path pathToNonGenFile2 = Paths.get("package/src-gen/SourceFile2.java");

    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//foo:bar");
    TargetNode<JavaLibraryDescriptionArg, JavaLibraryDescription> javaLibraryNode =
        JavaLibraryBuilder.createBuilder(javaLibraryTarget)
            .addSrc(pathToNonGenFile1)
            .addSrc(pathToNonGenFile2)
            .addSrcTarget(genSrcTarget)
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(sourceGenerator, javaLibraryNode);

    BuildRuleResolver ruleResolver = new TestBuildRuleResolver(targetGraph);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);
    JavaLibrary javaLibrary = (JavaLibrary) ruleResolver.requireRule(javaLibraryTarget);

    DefaultJavaPackageFinder defaultJavaPackageFinder = createMock(DefaultJavaPackageFinder.class);
    expect(defaultJavaPackageFinder.getPathsFromRoot()).andReturn(pathsFromRoot).times(2);
    expect(defaultJavaPackageFinder.getPathElements()).andReturn(pathElements).times(2);

    replay(defaultJavaPackageFinder);

    ImmutableSet<String> result =
        TestRunning.getPathToSourceFolders(
            javaLibrary, resolver, ruleFinder, defaultJavaPackageFinder);

    Path rootPath = javaLibrary.getProjectFilesystem().getRootPath();
    ImmutableSet<String> expected =
        ImmutableSet.of(
            rootPath.resolve("package/src-gen") + "/", rootPath.resolve("package/src") + "/");

    assertEquals(
        "The non-generated source files are under two different source folders.", expected, result);

    verify(defaultJavaPackageFinder);
  }

  /** Tests the --xml flag, ensuring that test result data is correctly formatted. */
  @Test
  public void testXmlGeneration() throws Exception {
    // Set up sample test data.
    TestResultSummary result1 =
        new TestResultSummary(
            /* testCaseName */ "TestCase",
            /* testName */ "passTest",
            /* type */ ResultType.SUCCESS,
            /* time */ 5000,
            /* message */ null,
            /* stacktrace */ null,
            /* stdOut */ null,
            /* stdErr */ null);
    TestResultSummary result2 =
        new TestResultSummary(
            /* testCaseName */ "TestCase",
            /* testName */ "failWithMsg",
            /* type */ ResultType.FAILURE,
            /* time */ 7000,
            /* message */ "Index out of bounds!",
            /* stacktrace */ "Stacktrace",
            /* stdOut */ null,
            /* stdErr */ null);
    TestResultSummary result3 =
        new TestResultSummary(
            /* testCaseName */ "TestCase",
            /* testName */ "failNoMsg",
            /* isSuccess */
            /* type */ ResultType.SUCCESS,
            /* time */ 4000,
            /* message */ null,
            /* stacktrace */ null,
            /* stdOut */ null,
            /* stdErr */ null);
    List<TestResultSummary> resultList = ImmutableList.of(result1, result2, result3);

    TestCaseSummary testCase = new TestCaseSummary("TestCase", resultList);
    List<TestCaseSummary> testCases = ImmutableList.of(testCase);

    TestResults testResults = FakeTestResults.of(testCases);
    List<TestResults> testResultsList = ImmutableList.of(testResults);

    // Call the XML generation method with our test data.
    StringWriter writer = new StringWriter();
    TestRunning.writeXmlOutput(testResultsList, writer);
    ByteArrayInputStream resultStream = new ByteArrayInputStream(writer.toString().getBytes());

    // Convert the raw XML data into a DOM object, which we will check.
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    DocumentBuilder docBuilder = dbf.newDocumentBuilder();
    Document doc = docBuilder.parse(resultStream);

    // Check for exactly one <tests> tag.
    NodeList testsList = doc.getElementsByTagName("tests");
    assertEquals(testsList.getLength(), 1);

    // Check for exactly one <test> tag.
    Element testsEl = (Element) testsList.item(0);
    NodeList testList = testsEl.getElementsByTagName("test");
    assertEquals(testList.getLength(), 1);

    // Check the target has been set
    Element testEl = (Element) testList.item(0);
    assertEquals(testEl.getAttribute("target"), "//foo/bar:baz");

    // Check for exactly three <testresult> tags.
    // There should be two failures and one success.
    NodeList resultsList = testEl.getElementsByTagName("testresult");
    assertEquals(resultsList.getLength(), 3);

    // Verify the text elements of the first <testresult> tag.
    Element passResultEl = (Element) resultsList.item(0);
    assertEquals(passResultEl.getAttribute("name"), "passTest");
    assertEquals(passResultEl.getAttribute("time"), "5000");
    assertEquals(passResultEl.getAttribute("status"), "PASS");
    checkXmlTextContents(passResultEl, "message", "");
    checkXmlTextContents(passResultEl, "stacktrace", "");

    // Verify the text elements of the second <testresult> tag.
    assertEquals(testEl.getAttribute("name"), "TestCase");
    Element failResultEl1 = (Element) resultsList.item(1);
    assertEquals(failResultEl1.getAttribute("name"), "failWithMsg");
    assertEquals(failResultEl1.getAttribute("time"), "7000");
    assertEquals(failResultEl1.getAttribute("status"), "FAIL");
    checkXmlTextContents(failResultEl1, "message", "Index out of bounds!");
    checkXmlTextContents(failResultEl1, "stacktrace", "Stacktrace");

    // Verify the text elements of the third <testresult> tag.
    Element failResultEl2 = (Element) resultsList.item(2);
    assertEquals(failResultEl2.getAttribute("name"), "failNoMsg");
    assertEquals(failResultEl2.getAttribute("time"), "4000");
    assertEquals(failResultEl2.getAttribute("status"), "PASS");
    checkXmlTextContents(failResultEl2, "message", "");
    checkXmlTextContents(failResultEl2, "stacktrace", "");
  }

  /** Helper method for testXMLGeneration(). Used to verify the message and stacktrace fields */
  private void checkXmlTextContents(
      Element testResult, String attributeName, String expectedValue) {
    // Check for exactly one text element.
    NodeList fieldMatchList = testResult.getElementsByTagName(attributeName);
    assertEquals(fieldMatchList.getLength(), 1);
    Element fieldEl = (Element) fieldMatchList.item(0);

    // Check that the value within the text element is as expected.
    Node firstChild = fieldEl.getFirstChild();
    String expectedStr = Strings.nullToEmpty(expectedValue);
    assertTrue(
        ((firstChild == null) && (expectedStr.equals("")))
            || ((firstChild != null) && expectedStr.equals(firstChild.getNodeValue())));
  }

  @Test
  public void whenAllTestsAreSeparateTestsRunInOrder() throws Exception {
    CommandRunnerParams commandRunnerParams = CommandRunnerParamsForTesting.builder().build();

    AtomicInteger atomicExecutionOrder = new AtomicInteger(0);
    ExecutionOrderAwareFakeStep separateTestStep1 =
        new ExecutionOrderAwareFakeStep("teststep1", "teststep1", 0, atomicExecutionOrder);
    TestResults fakeTestResults =
        FakeTestResults.of(
            ImmutableList.of(
                new TestCaseSummary(
                    "TestCase",
                    ImmutableList.of(
                        new TestResultSummary(
                            "TestCaseResult",
                            "passTest",
                            ResultType.SUCCESS,
                            5000,
                            null,
                            null,
                            null,
                            null)))));
    BuildTarget separateTest1Target = BuildTargetFactory.newInstance("//:test1");
    FakeTestRule separateTest1 =
        new FakeTestRule(
            separateTest1Target,
            new FakeProjectFilesystem(),
            TestBuildRuleParams.create(),
            ImmutableSet.of(),
            Optional.of(Paths.get("separateTestStep1OutputDir")),
            true, // runTestSeparately
            ImmutableList.of(separateTestStep1),
            () -> fakeTestResults);

    ExecutionOrderAwareFakeStep separateTestStep2 =
        new ExecutionOrderAwareFakeStep("teststep2", "teststep2", 0, atomicExecutionOrder);
    BuildTarget separateTest2Target = BuildTargetFactory.newInstance("//:test2");
    FakeTestRule separateTest2 =
        new FakeTestRule(
            separateTest2Target,
            new FakeProjectFilesystem(),
            TestBuildRuleParams.create(),
            ImmutableSet.of(),
            Optional.of(Paths.get("separateTestStep2OutputDir")),
            true, // runTestSeparately
            ImmutableList.of(separateTestStep2),
            () -> fakeTestResults);

    ExecutionOrderAwareFakeStep separateTestStep3 =
        new ExecutionOrderAwareFakeStep("teststep3", "teststep3", 0, atomicExecutionOrder);
    BuildTarget separateTest3Target = BuildTargetFactory.newInstance("//:test3");
    FakeTestRule separateTest3 =
        new FakeTestRule(
            separateTest3Target,
            new FakeProjectFilesystem(),
            TestBuildRuleParams.create(),
            ImmutableSet.of(),
            Optional.of(Paths.get("separateTestStep3OutputDir")),
            true, // runTestSeparately
            ImmutableList.of(separateTestStep3),
            () -> fakeTestResults);

    // We explicitly use an actual thread pool here; the logic should ensure the
    // separate tests are run in the correct order.
    ListeningExecutorService service =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3));
    FakeBuildEngine fakeBuildEngine =
        new FakeBuildEngine(
            ImmutableMap.of(
                separateTest1Target,
                BuildResult.success(separateTest1, BUILT_LOCALLY, CacheResult.miss()),
                separateTest2Target,
                BuildResult.success(separateTest2, BUILT_LOCALLY, CacheResult.miss()),
                separateTest3Target,
                BuildResult.success(separateTest3, BUILT_LOCALLY, CacheResult.miss())));
    ExecutionContext fakeExecutionContext = TestExecutionContext.newInstance();
    DefaultStepRunner stepRunner = new DefaultStepRunner();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestBuildRuleResolver());
    int ret =
        TestRunning.runTests(
            commandRunnerParams,
            ImmutableList.of(separateTest1, separateTest2, separateTest3),
            fakeExecutionContext,
            DEFAULT_OPTIONS,
            service,
            fakeBuildEngine,
            stepRunner,
            FakeBuildContext.withSourcePathResolver(DefaultSourcePathResolver.from(ruleFinder)),
            ruleFinder);

    assertThat(ret, equalTo(0));
    assertThat(separateTestStep1.getExecutionBeginOrder(), equalTo(Optional.of(0)));
    assertThat(separateTestStep1.getExecutionEndOrder(), equalTo(Optional.of(1)));
    assertThat(separateTestStep2.getExecutionBeginOrder(), equalTo(Optional.of(2)));
    assertThat(separateTestStep2.getExecutionEndOrder(), equalTo(Optional.of(3)));
    assertThat(separateTestStep3.getExecutionBeginOrder(), equalTo(Optional.of(4)));
    assertThat(separateTestStep3.getExecutionEndOrder(), equalTo(Optional.of(5)));
  }

  @Test
  public void whenSomeTestsAreSeparateThenSeparateTestsRunAtEnd() throws Exception {
    CommandRunnerParams commandRunnerParams = CommandRunnerParamsForTesting.builder().build();

    AtomicInteger atomicExecutionOrder = new AtomicInteger(0);
    ExecutionOrderAwareFakeStep separateTestStep1 =
        new ExecutionOrderAwareFakeStep("teststep1", "teststep1", 0, atomicExecutionOrder);
    TestResults fakeTestResults =
        FakeTestResults.of(
            ImmutableList.of(
                new TestCaseSummary(
                    "TestCase",
                    ImmutableList.of(
                        new TestResultSummary(
                            "TestCaseResult",
                            "passTest",
                            ResultType.SUCCESS,
                            5000,
                            null,
                            null,
                            null,
                            null)))));

    BuildTarget separateTest1Target = BuildTargetFactory.newInstance("//:test1");
    FakeTestRule separateTest1 =
        new FakeTestRule(
            separateTest1Target,
            new FakeProjectFilesystem(),
            TestBuildRuleParams.create(),
            ImmutableSet.of(),
            Optional.of(Paths.get("separateTestStep1OutputDir")),
            true, // runTestSeparately
            ImmutableList.of(separateTestStep1),
            () -> fakeTestResults);

    ExecutionOrderAwareFakeStep separateTestStep2 =
        new ExecutionOrderAwareFakeStep("teststep2", "teststep2", 0, atomicExecutionOrder);
    BuildTarget separateTest2Target = BuildTargetFactory.newInstance("//:test2");
    FakeTestRule separateTest2 =
        new FakeTestRule(
            separateTest2Target,
            new FakeProjectFilesystem(),
            TestBuildRuleParams.create(),
            ImmutableSet.of(),
            Optional.of(Paths.get("separateTestStep2OutputDir")),
            true, // runTestSeparately
            ImmutableList.of(separateTestStep2),
            () -> fakeTestResults);

    ExecutionOrderAwareFakeStep separateTestStep3 =
        new ExecutionOrderAwareFakeStep("teststep3", "teststep3", 0, atomicExecutionOrder);
    BuildTarget separateTest3Target = BuildTargetFactory.newInstance("//:test3");
    FakeTestRule separateTest3 =
        new FakeTestRule(
            separateTest3Target,
            new FakeProjectFilesystem(),
            TestBuildRuleParams.create(),
            ImmutableSet.of(),
            Optional.of(Paths.get("separateTestStep3OutputDir")),
            true, // runTestSeparately
            ImmutableList.of(separateTestStep3),
            () -> fakeTestResults);

    ExecutionOrderAwareFakeStep parallelTestStep1 =
        new ExecutionOrderAwareFakeStep(
            "parallelteststep1", "parallelteststep1", 0, atomicExecutionOrder);
    BuildTarget parallelTest1Target = BuildTargetFactory.newInstance("//:paralleltest1");
    FakeTestRule parallelTest1 =
        new FakeTestRule(
            parallelTest1Target,
            new FakeProjectFilesystem(),
            TestBuildRuleParams.create(),
            ImmutableSet.of(),
            Optional.of(Paths.get("parallelTestStep1OutputDir")),
            false, // runTestSeparately
            ImmutableList.of(parallelTestStep1),
            () -> fakeTestResults);

    ExecutionOrderAwareFakeStep parallelTestStep2 =
        new ExecutionOrderAwareFakeStep(
            "parallelteststep2", "parallelteststep2", 0, atomicExecutionOrder);
    BuildTarget parallelTest2Target = BuildTargetFactory.newInstance("//:paralleltest2");
    FakeTestRule parallelTest2 =
        new FakeTestRule(
            parallelTest2Target,
            new FakeProjectFilesystem(),
            TestBuildRuleParams.create(),
            ImmutableSet.of(),
            Optional.of(Paths.get("parallelTestStep2OutputDir")),
            false, // runTestSeparately
            ImmutableList.of(parallelTestStep2),
            () -> fakeTestResults);

    ExecutionOrderAwareFakeStep parallelTestStep3 =
        new ExecutionOrderAwareFakeStep(
            "parallelteststep3", "parallelteststep3", 0, atomicExecutionOrder);
    BuildTarget parallelTest3Target = BuildTargetFactory.newInstance("//:paralleltest3");
    FakeTestRule parallelTest3 =
        new FakeTestRule(
            parallelTest3Target,
            new FakeProjectFilesystem(),
            TestBuildRuleParams.create(),
            ImmutableSet.of(),
            Optional.of(Paths.get("parallelTestStep3OutputDir")),
            false, // runTestSeparately
            ImmutableList.of(parallelTestStep3),
            () -> fakeTestResults);

    // We explicitly use an actual thread pool here; the logic should ensure the
    // separate tests are run in the correct order.
    ListeningExecutorService service =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3));
    FakeBuildEngine fakeBuildEngine =
        new FakeBuildEngine(
            ImmutableMap.<BuildTarget, BuildResult>builder()
                .put(
                    separateTest1Target,
                    BuildResult.success(separateTest1, BUILT_LOCALLY, CacheResult.miss()))
                .put(
                    separateTest2Target,
                    BuildResult.success(separateTest2, BUILT_LOCALLY, CacheResult.miss()))
                .put(
                    separateTest3Target,
                    BuildResult.success(separateTest3, BUILT_LOCALLY, CacheResult.miss()))
                .put(
                    parallelTest1Target,
                    BuildResult.success(parallelTest1, BUILT_LOCALLY, CacheResult.miss()))
                .put(
                    parallelTest2Target,
                    BuildResult.success(parallelTest2, BUILT_LOCALLY, CacheResult.miss()))
                .put(
                    parallelTest3Target,
                    BuildResult.success(parallelTest3, BUILT_LOCALLY, CacheResult.miss()))
                .build());
    ExecutionContext fakeExecutionContext = TestExecutionContext.newInstance();
    DefaultStepRunner stepRunner = new DefaultStepRunner();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestBuildRuleResolver());
    int ret =
        TestRunning.runTests(
            commandRunnerParams,
            ImmutableList.of(
                separateTest1,
                parallelTest1,
                separateTest2,
                parallelTest2,
                separateTest3,
                parallelTest3),
            fakeExecutionContext,
            DEFAULT_OPTIONS,
            service,
            fakeBuildEngine,
            stepRunner,
            FakeBuildContext.withSourcePathResolver(DefaultSourcePathResolver.from(ruleFinder)),
            ruleFinder);

    assertThat(ret, equalTo(0));

    // The tests not marked as separate could run in any order -- but they must run
    // before the separate test steps.
    ImmutableSet<Optional<Integer>> expectedParallelStepExecutionOrderSet =
        ImmutableSet.<Optional<Integer>>builder()
            .add(Optional.of(0))
            .add(Optional.of(1))
            .add(Optional.of(2))
            .add(Optional.of(3))
            .add(Optional.of(4))
            .add(Optional.of(5))
            .build();

    ImmutableSet<Optional<Integer>> actualParallelStepExecutionOrderSet =
        ImmutableSet.<Optional<Integer>>builder()
            .add(parallelTestStep1.getExecutionBeginOrder())
            .add(parallelTestStep1.getExecutionEndOrder())
            .add(parallelTestStep2.getExecutionBeginOrder())
            .add(parallelTestStep2.getExecutionEndOrder())
            .add(parallelTestStep3.getExecutionBeginOrder())
            .add(parallelTestStep3.getExecutionEndOrder())
            .build();

    LOG.debug(
        "Expected parallel execution order: %s Actual parallel execution order: %s",
        expectedParallelStepExecutionOrderSet, actualParallelStepExecutionOrderSet);

    // We allow the parallel steps to begin and end in any order (note the thread
    // pool of size 3 above), so we use a set.
    assertThat(actualParallelStepExecutionOrderSet, equalTo(expectedParallelStepExecutionOrderSet));

    // The separate test steps must begin and end in a specific order, so we use a list.
    ImmutableList<Optional<Integer>> expectedSeparateStepExecutionOrderList =
        ImmutableList.<Optional<Integer>>builder()
            .add(Optional.of(6))
            .add(Optional.of(7))
            .add(Optional.of(8))
            .add(Optional.of(9))
            .add(Optional.of(10))
            .add(Optional.of(11))
            .build();

    ImmutableList<Optional<Integer>> actualSeparateStepExecutionOrderList =
        ImmutableList.<Optional<Integer>>builder()
            .add(separateTestStep1.getExecutionBeginOrder())
            .add(separateTestStep1.getExecutionEndOrder())
            .add(separateTestStep2.getExecutionBeginOrder())
            .add(separateTestStep2.getExecutionEndOrder())
            .add(separateTestStep3.getExecutionBeginOrder())
            .add(separateTestStep3.getExecutionEndOrder())
            .build();

    LOG.debug(
        "Expected separate execution order: %s Actual separate execution order: %s",
        expectedSeparateStepExecutionOrderList, actualSeparateStepExecutionOrderList);

    assertThat(
        actualSeparateStepExecutionOrderList, equalTo(expectedSeparateStepExecutionOrderList));
  }

  @Test
  public void whenSeparateTestFailsThenBuildFails() throws Exception {
    CommandRunnerParams commandRunnerParams = CommandRunnerParamsForTesting.builder().build();
    TestResults failingTestResults =
        FakeTestResults.of(
            ImmutableList.of(
                new TestCaseSummary(
                    "TestCase",
                    ImmutableList.of(
                        new TestResultSummary(
                            "TestCaseResult",
                            "failTest",
                            ResultType.FAILURE,
                            5000,
                            null,
                            null,
                            null,
                            null)))));
    BuildTarget failingTestTarget = BuildTargetFactory.newInstance("//:failingtest");
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestBuildRuleResolver());
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);
    FakeTestRule failingTest =
        new FakeTestRule(
            failingTestTarget,
            new FakeProjectFilesystem(),
            TestBuildRuleParams.create(),
            ImmutableSet.of(),
            Optional.of(Paths.get("failingTestStep1OutputDir")),
            true, // runTestSeparately
            ImmutableList.of(),
            () -> failingTestResults);

    ListeningExecutorService service =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3));
    FakeBuildEngine fakeBuildEngine =
        new FakeBuildEngine(
            ImmutableMap.of(
                failingTestTarget,
                BuildResult.success(failingTest, BUILT_LOCALLY, CacheResult.miss())));
    ExecutionContext fakeExecutionContext = TestExecutionContext.newInstance();
    DefaultStepRunner stepRunner = new DefaultStepRunner();
    int ret =
        TestRunning.runTests(
            commandRunnerParams,
            ImmutableList.of(failingTest),
            fakeExecutionContext,
            DEFAULT_OPTIONS,
            service,
            fakeBuildEngine,
            stepRunner,
            FakeBuildContext.withSourcePathResolver(resolver),
            ruleFinder);

    assertThat(ret, equalTo(ExitCode.TEST_ERROR.getCode()));
  }
}
