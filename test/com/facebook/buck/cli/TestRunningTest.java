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
import static com.facebook.buck.rules.BuildRuleSuccessType.FETCHED_FROM_CACHE;
import static com.facebook.buck.rules.BuildRuleSuccessType.MATCHING_RULE_KEY;
import static com.facebook.buck.util.BuckConstant.GEN_PATH;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.java.DefaultJavaPackageFinder;
import com.facebook.buck.java.FakeJavaLibrary;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildResult;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CacheResult;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildEngine;
import com.facebook.buck.rules.FakeTestRule;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.ExecutionOrderAwareFakeStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import org.junit.BeforeClass;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

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
   * If the source paths specified are all generated files, then our path to source tmp
   * should be absent.
   */
  @Test
  public void testGeneratedSourceFile() {
    Path pathToGenFile = GEN_PATH.resolve("GeneratedFile.java");
    assertTrue(MorePaths.isGeneratedFile(pathToGenFile));

    ImmutableSortedSet<Path> javaSrcs = ImmutableSortedSet.of(pathToGenFile);
    JavaLibrary javaLibrary = new FakeJavaLibrary(
        BuildTarget.builder("//foo", "bar").build(),
        new SourcePathResolver(new BuildRuleResolver())).setJavaSrcs(javaSrcs);

    DefaultJavaPackageFinder defaultJavaPackageFinder =
        createMock(DefaultJavaPackageFinder.class);

    Object[] mocks = new Object[] {defaultJavaPackageFinder};
    replay(mocks);

    ImmutableSet<String> result = TestRunning.getPathToSourceFolders(
        javaLibrary, Optional.of(defaultJavaPackageFinder), new FakeProjectFilesystem());

    assertTrue("No path should be returned if the library contains only generated files.",
        result.isEmpty());

    verify(mocks);
  }

  /**
   * If the source paths specified are all for non-generated files then we should return
   * the correct source tmp corresponding to a non-generated source path.
   */
  @Test
  public void testNonGeneratedSourceFile() {
    Path pathToNonGenFile = Paths.get("package/src/SourceFile1.java");
    assertFalse(MorePaths.isGeneratedFile(pathToNonGenFile));

    ImmutableSortedSet<Path> javaSrcs = ImmutableSortedSet.of(pathToNonGenFile);
    JavaLibrary javaLibrary = new FakeJavaLibrary(
        BuildTarget.builder("//foo", "bar").build(),
        new SourcePathResolver(new BuildRuleResolver())).setJavaSrcs(javaSrcs);

    DefaultJavaPackageFinder defaultJavaPackageFinder =
        createMock(DefaultJavaPackageFinder.class);
    expect(defaultJavaPackageFinder.getPathsFromRoot()).andReturn(pathsFromRoot);
    expect(defaultJavaPackageFinder.getPathElements()).andReturn(pathElements);

    replay(defaultJavaPackageFinder);

    ImmutableSet<String> result = TestRunning.getPathToSourceFolders(
        javaLibrary, Optional.of(defaultJavaPackageFinder), new FakeProjectFilesystem());

    String expected = javaLibrary.getProjectFilesystem().getRootPath().resolve("package/src") + "/";
    assertEquals("All non-generated source files are under one source tmp.",
        ImmutableSet.of(expected), result);

    verify(defaultJavaPackageFinder);
  }

  /**
   * If the source paths specified are from the new unified source tmp then we should return
   * the correct source tmp corresponding to the unified source path.
   */
  @Test
  public void testUnifiedSourceFile() {
    Path pathToNonGenFile = Paths.get("java/package/SourceFile1.java");
    assertFalse(MorePaths.isGeneratedFile(pathToNonGenFile));

    ImmutableSortedSet<Path> javaSrcs = ImmutableSortedSet.of(pathToNonGenFile);
    JavaLibrary javaLibrary = new FakeJavaLibrary(
        BuildTarget.builder("//foo", "bar").build(),
        new SourcePathResolver(new BuildRuleResolver())).setJavaSrcs(javaSrcs);

    DefaultJavaPackageFinder defaultJavaPackageFinder =
        createMock(DefaultJavaPackageFinder.class);
    expect(defaultJavaPackageFinder.getPathsFromRoot()).andReturn(pathsFromRoot);

    Object[] mocks = new Object[] {defaultJavaPackageFinder};
    replay(mocks);

    ImmutableSet<String> result = TestRunning.getPathToSourceFolders(
        javaLibrary, Optional.of(defaultJavaPackageFinder), new FakeProjectFilesystem());

    assertEquals("All non-generated source files are under one source tmp.",
        ImmutableSet.of("java/"), result);

    verify(mocks);
  }

  /**
   * If the source paths specified contains one source path to a non-generated file then
   * we should return the correct source tmp corresponding to that non-generated source path.
   * Especially when the generated file comes first in the ordered set.
   */
  @Test
  public void testMixedSourceFile() {
    Path pathToGenFile = GEN_PATH.resolve("com/facebook/GeneratedFile.java");
    Path pathToNonGenFile1 = Paths.get("package/src/SourceFile1.java");
    Path pathToNonGenFile2 = Paths.get("package/src-gen/SourceFile2.java");

    ImmutableSortedSet<Path> javaSrcs = ImmutableSortedSet.of(
        pathToGenFile, pathToNonGenFile1, pathToNonGenFile2);

    DefaultJavaPackageFinder defaultJavaPackageFinder =
        createMock(DefaultJavaPackageFinder.class);
    expect(defaultJavaPackageFinder.getPathsFromRoot()).andReturn(pathsFromRoot).times(2);
    expect(defaultJavaPackageFinder.getPathElements()).andReturn(pathElements).times(2);

    JavaLibrary javaLibrary = new FakeJavaLibrary(
        BuildTarget.builder("//foo", "bar").build(),
        new SourcePathResolver(new BuildRuleResolver())).setJavaSrcs(javaSrcs);

    replay(defaultJavaPackageFinder);

    ImmutableSet<String> result = TestRunning.getPathToSourceFolders(
        javaLibrary, Optional.of(defaultJavaPackageFinder), new FakeProjectFilesystem());

    Path rootPath = javaLibrary.getProjectFilesystem().getRootPath();
    ImmutableSet<String> expected = ImmutableSet.of(
        rootPath.resolve("package/src-gen").toString() + "/",
        rootPath.resolve("package/src").toString() + "/");

    assertEquals("The non-generated source files are under two different source folders.",
        expected, result);

    verify(defaultJavaPackageFinder);
  }

  /**
   * Tests the --xml flag, ensuring that test result data is correctly
   * formatted.
   */
  @Test
  public void testXmlGeneration() throws Exception {
    // Set up sample test data.
    TestResultSummary result1 = new TestResultSummary(
        /* testCaseName */ "TestCase",
        /* testName */ "passTest",
        /* type */ ResultType.SUCCESS,
        /* time */ 5000,
        /* message */ null,
        /* stacktrace */ null,
        /* stdOut */ null,
        /* stdErr */ null);
    TestResultSummary result2 = new TestResultSummary(
        /* testCaseName */ "TestCase",
        /* testName */ "failWithMsg",
        /* type */ ResultType.FAILURE,
        /* time */ 7000,
        /* message */ "Index out of bounds!",
        /* stacktrace */ "Stacktrace",
        /* stdOut */ null,
        /* stdErr */ null);
    TestResultSummary result3 = new TestResultSummary(
        /* testCaseName */ "TestCase",
        /* testName */ "failNoMsg",
        /* isSuccess */
        /* type */ ResultType.SUCCESS,
        /* time */ 4000,
        /* message */ null,
        /* stacktrace */ null,
        /* stdOut */ null,
        /* stdErr */ null);
    List<TestResultSummary> resultList = ImmutableList.of(
      result1,
      result2,
      result3);

    TestCaseSummary testCase = new TestCaseSummary("TestCase", resultList);
    List<TestCaseSummary> testCases = ImmutableList.of(testCase);

    TestResults testResults = new TestResults(testCases);
    List<TestResults> testResultsList = ImmutableList.of(testResults);

    // Call the XML generation method with our test data.
    StringWriter writer = new StringWriter();
    TestRunning.writeXmlOutput(testResultsList, writer);
    ByteArrayInputStream resultStream = new ByteArrayInputStream(
      writer.toString().getBytes());

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

    // Check for exactly three <testresult> tags.
    // There should be two failures and one success.
    Element testEl = (Element) testList.item(0);
    NodeList resultsList = testEl.getElementsByTagName("testresult");
    assertEquals(resultsList.getLength(), 3);

    // Verify the text elements of the first <testresult> tag.
    Element passResultEl = (Element) resultsList.item(0);
    assertEquals(passResultEl.getAttribute("name"), "passTest");
    assertEquals(passResultEl.getAttribute("time"), "5000");
    checkXmlTextContents(passResultEl, "message", "");
    checkXmlTextContents(passResultEl, "stacktrace", "");

    // Verify the text elements of the second <testresult> tag.
    assertEquals(testEl.getAttribute("name"), "TestCase");
    Element failResultEl1 = (Element) resultsList.item(1);
    assertEquals(failResultEl1.getAttribute("name"), "failWithMsg");
    assertEquals(failResultEl1.getAttribute("time"), "7000");
    checkXmlTextContents(failResultEl1, "message", "Index out of bounds!");
    checkXmlTextContents(failResultEl1, "stacktrace", "Stacktrace");

    // Verify the text elements of the third <testresult> tag.
    Element failResultEl2 = (Element) resultsList.item(2);
    assertEquals(failResultEl2.getAttribute("name"), "failNoMsg");
    assertEquals(failResultEl2.getAttribute("time"), "4000");
    checkXmlTextContents(failResultEl2, "message", "");
    checkXmlTextContents(failResultEl2, "stacktrace", "");
  }

  /**
   * Helper method for testXMLGeneration().
   * Used to verify the message and stacktrace fields
   */
  private void checkXmlTextContents(Element testResult,
      String attributeName,
      String expectedValue) {
    // Check for exactly one text element.
    NodeList fieldMatchList = testResult.getElementsByTagName(attributeName);
    assertEquals(fieldMatchList.getLength(), 1);
    Element fieldEl = (Element) fieldMatchList.item(0);

    // Check that the value within the text element is as expected.
    Node firstChild = fieldEl.getFirstChild();
    String expectedStr = Strings.nullToEmpty(expectedValue);
    assertTrue(
      ((firstChild == null) && (expectedStr.equals(""))) ||
      ((firstChild != null) && expectedStr.equals(firstChild.getNodeValue())));
  }

  @Test
  public void testIsTestRunRequiredForTestInDebugMode()
      throws IOException, ExecutionException, InterruptedException {
    ExecutionContext executionContext = createMock(ExecutionContext.class);
    expect(executionContext.isDebugEnabled()).andReturn(true);

    replay(executionContext);

    assertTrue(
        "In debug mode, test should always run regardless of any cached results since " +
            "the user is expecting to hook up a debugger.",
        TestRunning.isTestRunRequiredForTest(
            createMock(TestRule.class),
            createMock(CachingBuildEngine.class),
            executionContext,
            createMock(TestRuleKeyFileHelper.class),
            true,
            false));

    verify(executionContext);
  }

  @Test
  public void testIsTestRunRequiredForTestBuiltFromCacheIfHasTestResultFiles()
      throws IOException, ExecutionException, InterruptedException {
    ExecutionContext executionContext = createMock(ExecutionContext.class);
    expect(executionContext.isDebugEnabled()).andReturn(false);

    FakeTestRule testRule = new FakeTestRule(
        ImmutableSet.<Label>of(Label.of("windows")),
        BuildTargetFactory.newInstance("//:lulz"),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSortedSet.<BuildRule>of());

    CachingBuildEngine cachingBuildEngine = createMock(CachingBuildEngine.class);
    BuildResult result = BuildResult.success(testRule, FETCHED_FROM_CACHE, CacheResult.hit("dir"));
    expect(cachingBuildEngine.getBuildRuleResult(BuildTargetFactory.newInstance("//:lulz")))
        .andReturn(result);
    replay(executionContext, cachingBuildEngine);

    assertTrue(
        "A cache hit updates the build artifact but not the test results. " +
            "Therefore, the test should be re-run to ensure the test results are up to date.",
        TestRunning.isTestRunRequiredForTest(
            testRule,
            cachingBuildEngine,
            executionContext,
            createMock(TestRuleKeyFileHelper.class),
            /* results cache enabled */ true,
            /* running with test selectors */ false));

    verify(executionContext, cachingBuildEngine);
  }

  @Test
  public void testIsTestRunRequiredForTestBuiltLocally()
      throws IOException, ExecutionException, InterruptedException {
    ExecutionContext executionContext = createMock(ExecutionContext.class);
    expect(executionContext.isDebugEnabled()).andReturn(false);

    FakeTestRule testRule = new FakeTestRule(
        ImmutableSet.<Label>of(Label.of("windows")),
        BuildTargetFactory.newInstance("//:lulz"),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSortedSet.<BuildRule>of());

    CachingBuildEngine cachingBuildEngine = createMock(CachingBuildEngine.class);
    BuildResult result = BuildResult.success(testRule, BUILT_LOCALLY, CacheResult.skip());
    expect(cachingBuildEngine.getBuildRuleResult(BuildTargetFactory.newInstance("//:lulz")))
        .andReturn(result);
    replay(executionContext, cachingBuildEngine);

    assertTrue(
        "A test built locally should always run regardless of any cached result. ",
        TestRunning.isTestRunRequiredForTest(
            testRule,
            cachingBuildEngine,
            executionContext,
            createMock(TestRuleKeyFileHelper.class),
            /* results cache enabled */ true,
            /* running with test selectors */ false));

    verify(executionContext, cachingBuildEngine);
  }

  @Test
  public void testIsTestRunRequiredIfRuleKeyNotPresent()
      throws IOException, ExecutionException, InterruptedException {
    ExecutionContext executionContext = createMock(ExecutionContext.class);
    expect(executionContext.isDebugEnabled()).andReturn(false);

    FakeTestRule testRule = new FakeTestRule(
        ImmutableSet.<Label>of(Label.of("windows")),
        BuildTargetFactory.newInstance("//:lulz"),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSortedSet.<BuildRule>of()) {

      @Override
      public boolean hasTestResultFiles(ExecutionContext context) {
        return true;
      }
    };

    TestRuleKeyFileHelper testRuleKeyFileHelper = createNiceMock(TestRuleKeyFileHelper.class);
    expect(testRuleKeyFileHelper.isRuleKeyInDir(testRule)).andReturn(false);

    CachingBuildEngine cachingBuildEngine = createMock(CachingBuildEngine.class);
    BuildResult result = BuildResult.success(testRule, MATCHING_RULE_KEY, CacheResult.skip());
    expect(cachingBuildEngine.getBuildRuleResult(BuildTargetFactory.newInstance("//:lulz")))
        .andReturn(result);
    replay(executionContext, cachingBuildEngine, testRuleKeyFileHelper);

    assertTrue(
        "A cached build should run the tests if the test output directory\'s rule key is not " +
            "present or does not matche the rule key for the test.",
        TestRunning.isTestRunRequiredForTest(
            testRule,
            cachingBuildEngine,
            executionContext,
            testRuleKeyFileHelper,
            /* results cache enabled */ true,
            /* running with test selectors */ false));

    verify(executionContext, cachingBuildEngine, testRuleKeyFileHelper);
  }

  @Test
  public void whenAllTestsAreSeparateTestsRunInOrder() throws Exception {
    CommandRunnerParams commandRunnerParams = CommandRunnerParamsForTesting
        .builder()
        .build();

    AtomicInteger atomicExecutionOrder = new AtomicInteger(0);
    ExecutionOrderAwareFakeStep separateTestStep1 =
        new ExecutionOrderAwareFakeStep(
            "teststep1",
            "teststep1",
            0,
            atomicExecutionOrder);
    final TestResults fakeTestResults =
        new TestResults(
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
    FakeTestRule separateTest1 = new FakeTestRule(
        BuildRuleParamsFactory.createTrivialBuildRuleParams(separateTest1Target),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSet.<Label>of(),
        Optional.of(Paths.get("separateTestStep1OutputDir")),
        true, // runTestSeparately
        ImmutableList.<Step>of(separateTestStep1),
        new Callable<TestResults>() {
          @Override
          public TestResults call() {
            return fakeTestResults;
          }
        });

    ExecutionOrderAwareFakeStep separateTestStep2 =
        new ExecutionOrderAwareFakeStep(
            "teststep2",
            "teststep2",
            0,
            atomicExecutionOrder);
    BuildTarget separateTest2Target = BuildTargetFactory.newInstance("//:test2");
    FakeTestRule separateTest2 = new FakeTestRule(
        BuildRuleParamsFactory.createTrivialBuildRuleParams(separateTest2Target),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSet.<Label>of(),
        Optional.of(Paths.get("separateTestStep2OutputDir")),
        true, // runTestSeparately
        ImmutableList.<Step>of(separateTestStep2),
        new Callable<TestResults>() {
          @Override
          public TestResults call() {
            return fakeTestResults;
          }
        });

    ExecutionOrderAwareFakeStep separateTestStep3 =
        new ExecutionOrderAwareFakeStep(
            "teststep3",
            "teststep3",
            0,
            atomicExecutionOrder);
    BuildTarget separateTest3Target = BuildTargetFactory.newInstance("//:test3");
    FakeTestRule separateTest3 = new FakeTestRule(
        BuildRuleParamsFactory.createTrivialBuildRuleParams(separateTest3Target),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSet.<Label>of(),
        Optional.of(Paths.get("separateTestStep3OutputDir")),
        true, // runTestSeparately
        ImmutableList.<Step>of(separateTestStep3),
        new Callable<TestResults>() {
          @Override
          public TestResults call() {
            return fakeTestResults;
          }
        });

    // We explicitly use an actual thread pool here; the logic should ensure the
    // separate tests are run in the correct order.
    ListeningExecutorService service =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3));
    FakeBuildEngine fakeBuildEngine = new FakeBuildEngine(
        ImmutableMap.of(
            separateTest1Target,
            BuildResult.success(separateTest1, BUILT_LOCALLY, CacheResult.skip()),
            separateTest2Target,
            BuildResult.success(separateTest2, BUILT_LOCALLY, CacheResult.skip()),
            separateTest3Target,
            BuildResult.success(separateTest3, BUILT_LOCALLY, CacheResult.skip())
        ),
        ImmutableMap.of(
            separateTest1Target, new RuleKey("00"),
            separateTest2Target, new RuleKey("00"),
            separateTest3Target, new RuleKey("00")
        ));
    ExecutionContext fakeExecutionContext = TestExecutionContext.newBuilder()
        .setProjectFilesystem(new FakeProjectFilesystem())
        .build();
    DefaultStepRunner stepRunner = new DefaultStepRunner(fakeExecutionContext);
    int ret = TestRunning.runTests(
        commandRunnerParams,
        ImmutableList.<TestRule>of(separateTest1, separateTest2, separateTest3),
        FakeBuildContext.NOOP_CONTEXT,
        fakeExecutionContext,
        DEFAULT_OPTIONS,
        service,
        fakeBuildEngine,
        stepRunner);

    assertThat(ret, equalTo(0));
    assertThat(
        separateTestStep1.getExecutionBeginOrder(),
        equalTo(Optional.of(0)));
    assertThat(
        separateTestStep1.getExecutionEndOrder(),
        equalTo(Optional.of(1)));
    assertThat(
        separateTestStep2.getExecutionBeginOrder(),
        equalTo(Optional.of(2)));
    assertThat(
        separateTestStep2.getExecutionEndOrder(),
        equalTo(Optional.of(3)));
    assertThat(
        separateTestStep3.getExecutionBeginOrder(),
        equalTo(Optional.of(4)));
    assertThat(
        separateTestStep3.getExecutionEndOrder(),
        equalTo(Optional.of(5)));
  }

  @Test
  public void whenSomeTestsAreSeparateThenSeparateTestsRunAtEnd() throws Exception {
    CommandRunnerParams commandRunnerParams = CommandRunnerParamsForTesting
        .builder()
        .build();

    AtomicInteger atomicExecutionOrder = new AtomicInteger(0);
    ExecutionOrderAwareFakeStep separateTestStep1 =
        new ExecutionOrderAwareFakeStep(
            "teststep1",
            "teststep1",
            0,
            atomicExecutionOrder);
    final TestResults fakeTestResults =
        new TestResults(
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
    FakeTestRule separateTest1 = new FakeTestRule(
        BuildRuleParamsFactory.createTrivialBuildRuleParams(separateTest1Target),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSet.<Label>of(),
        Optional.of(Paths.get("separateTestStep1OutputDir")),
        true, // runTestSeparately
        ImmutableList.<Step>of(separateTestStep1),
        new Callable<TestResults>() {
          @Override
          public TestResults call() {
            return fakeTestResults;
          }
        });

    ExecutionOrderAwareFakeStep separateTestStep2 =
        new ExecutionOrderAwareFakeStep(
            "teststep2",
            "teststep2",
            0,
            atomicExecutionOrder);
    BuildTarget separateTest2Target = BuildTargetFactory.newInstance("//:test2");
    FakeTestRule separateTest2 = new FakeTestRule(
        BuildRuleParamsFactory.createTrivialBuildRuleParams(separateTest2Target),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSet.<Label>of(),
        Optional.of(Paths.get("separateTestStep2OutputDir")),
        true, // runTestSeparately
        ImmutableList.<Step>of(separateTestStep2),
        new Callable<TestResults>() {
          @Override
          public TestResults call() {
            return fakeTestResults;
          }
        });

    ExecutionOrderAwareFakeStep separateTestStep3 =
        new ExecutionOrderAwareFakeStep(
            "teststep3",
            "teststep3",
            0,
            atomicExecutionOrder);
    BuildTarget separateTest3Target = BuildTargetFactory.newInstance("//:test3");
    FakeTestRule separateTest3 = new FakeTestRule(
        BuildRuleParamsFactory.createTrivialBuildRuleParams(separateTest3Target),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSet.<Label>of(),
        Optional.of(Paths.get("separateTestStep3OutputDir")),
        true, // runTestSeparately
        ImmutableList.<Step>of(separateTestStep3),
        new Callable<TestResults>() {
          @Override
          public TestResults call() {
            return fakeTestResults;
          }
        });

    ExecutionOrderAwareFakeStep parallelTestStep1 =
        new ExecutionOrderAwareFakeStep(
            "parallelteststep1",
            "parallelteststep1",
            0,
            atomicExecutionOrder);
    BuildTarget parallelTest1Target = BuildTargetFactory.newInstance("//:paralleltest1");
    FakeTestRule parallelTest1 = new FakeTestRule(
        BuildRuleParamsFactory.createTrivialBuildRuleParams(parallelTest1Target),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSet.<Label>of(),
        Optional.of(Paths.get("parallelTestStep1OutputDir")),
        false, // runTestSeparately
        ImmutableList.<Step>of(parallelTestStep1),
        new Callable<TestResults>() {
          @Override
          public TestResults call() {
            return fakeTestResults;
          }
        });

    ExecutionOrderAwareFakeStep parallelTestStep2 =
        new ExecutionOrderAwareFakeStep(
            "parallelteststep2",
            "parallelteststep2",
            0,
            atomicExecutionOrder);
    BuildTarget parallelTest2Target = BuildTargetFactory.newInstance("//:paralleltest2");
    FakeTestRule parallelTest2 = new FakeTestRule(
        BuildRuleParamsFactory.createTrivialBuildRuleParams(parallelTest2Target),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSet.<Label>of(),
        Optional.of(Paths.get("parallelTestStep2OutputDir")),
        false, // runTestSeparately
        ImmutableList.<Step>of(parallelTestStep2),
        new Callable<TestResults>() {
          @Override
          public TestResults call() {
            return fakeTestResults;
          }
        });

    ExecutionOrderAwareFakeStep parallelTestStep3 =
        new ExecutionOrderAwareFakeStep(
            "parallelteststep3",
            "parallelteststep3",
            0,
            atomicExecutionOrder);
    BuildTarget parallelTest3Target = BuildTargetFactory.newInstance("//:paralleltest3");
    FakeTestRule parallelTest3 = new FakeTestRule(
        BuildRuleParamsFactory.createTrivialBuildRuleParams(parallelTest3Target),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSet.<Label>of(),
        Optional.of(Paths.get("parallelTestStep3OutputDir")),
        false, // runTestSeparately
        ImmutableList.<Step>of(parallelTestStep3),
        new Callable<TestResults>() {
          @Override
          public TestResults call() {
            return fakeTestResults;
          }
        });

    // We explicitly use an actual thread pool here; the logic should ensure the
    // separate tests are run in the correct order.
    ListeningExecutorService service =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3));
    FakeBuildEngine fakeBuildEngine = new FakeBuildEngine(
        ImmutableMap.<BuildTarget, BuildResult>builder()
            .put(
                separateTest1Target,
                BuildResult.success(separateTest1, BUILT_LOCALLY, CacheResult.skip()))
            .put(
                separateTest2Target,
                BuildResult.success(separateTest2, BUILT_LOCALLY, CacheResult.skip()))
            .put(
                separateTest3Target,
                BuildResult.success(separateTest3, BUILT_LOCALLY, CacheResult.skip()))
            .put(
                parallelTest1Target,
                BuildResult.success(parallelTest1, BUILT_LOCALLY, CacheResult.skip()))
            .put(
                parallelTest2Target,
                BuildResult.success(parallelTest2, BUILT_LOCALLY, CacheResult.skip()))
            .put(
                parallelTest3Target,
                BuildResult.success(parallelTest3, BUILT_LOCALLY, CacheResult.skip()))
            .build(),
        ImmutableMap.<BuildTarget, RuleKey>builder()
            .put(separateTest1Target, new RuleKey("00"))
            .put(separateTest2Target, new RuleKey("00"))
            .put(separateTest3Target, new RuleKey("00"))
            .put(parallelTest1Target, new RuleKey("00"))
            .put(parallelTest2Target, new RuleKey("00"))
            .put(parallelTest3Target, new RuleKey("00"))
            .build());
    ExecutionContext fakeExecutionContext = TestExecutionContext.newBuilder()
        .setProjectFilesystem(new FakeProjectFilesystem())
        .build();
    DefaultStepRunner stepRunner = new DefaultStepRunner(fakeExecutionContext);
    int ret = TestRunning.runTests(
        commandRunnerParams,
        ImmutableList.<TestRule>of(
            separateTest1,
            parallelTest1,
            separateTest2,
            parallelTest2,
            separateTest3,
            parallelTest3),
        FakeBuildContext.NOOP_CONTEXT,
        fakeExecutionContext,
        DEFAULT_OPTIONS,
        service,
        fakeBuildEngine,
        stepRunner);

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
        expectedParallelStepExecutionOrderSet,
        actualParallelStepExecutionOrderSet);

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
        expectedSeparateStepExecutionOrderList,
        actualSeparateStepExecutionOrderList);

    assertThat(
        actualSeparateStepExecutionOrderList,
        equalTo(expectedSeparateStepExecutionOrderList));
  }

  @Test
  public void whenSeparateTestFailsThenBuildFails() throws Exception {
    CommandRunnerParams commandRunnerParams = CommandRunnerParamsForTesting
        .builder()
        .build();
    final TestResults failingTestResults =
        new TestResults(
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
    FakeTestRule failingTest = new FakeTestRule(
        BuildRuleParamsFactory.createTrivialBuildRuleParams(failingTestTarget),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSet.<Label>of(),
        Optional.of(Paths.get("failingTestStep1OutputDir")),
        true, // runTestSeparately
        ImmutableList.<Step>of(),
        new Callable<TestResults>() {
          @Override
          public TestResults call() {
            return failingTestResults;
          }
        });

    ListeningExecutorService service =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3));
    FakeBuildEngine fakeBuildEngine = new FakeBuildEngine(
        ImmutableMap.of(
            failingTestTarget, BuildResult.success(failingTest, BUILT_LOCALLY, CacheResult.skip())
        ),
        ImmutableMap.of(
            failingTestTarget, new RuleKey("00")
        ));
    ExecutionContext fakeExecutionContext = TestExecutionContext.newBuilder()
        .setProjectFilesystem(new FakeProjectFilesystem())
        .build();
    DefaultStepRunner stepRunner = new DefaultStepRunner(fakeExecutionContext);
    int ret = TestRunning.runTests(
        commandRunnerParams,
        ImmutableList.<TestRule>of(failingTest),
        FakeBuildContext.NOOP_CONTEXT,
        fakeExecutionContext,
        DEFAULT_OPTIONS,
        service,
        fakeBuildEngine,
        stepRunner);

    assertThat(ret, equalTo(TestRunning.TEST_FAILURES_EXIT_CODE));
  }
}
