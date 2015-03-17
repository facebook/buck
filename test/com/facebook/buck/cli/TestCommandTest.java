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

import static com.facebook.buck.util.BuckConstant.GEN_PATH;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.collection.IsIterableContainingInAnyOrder.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.java.DefaultJavaPackageFinder;
import com.facebook.buck.java.FakeJavaLibrary;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.JavaTestDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleSuccess;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.FakeTestRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ImmutableLabel;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.ExecutionContext;
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
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import org.junit.BeforeClass;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;
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
import java.util.Map;
import java.util.concurrent.ExecutionException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public class TestCommandTest {

  private static ImmutableSortedSet<String> pathsFromRoot;
  private static ImmutableSet<String> pathElements;

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

    ImmutableSet<String> result = TestCommand.getPathToSourceFolders(
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

    ImmutableSet<String> result = TestCommand.getPathToSourceFolders(
        javaLibrary, Optional.of(defaultJavaPackageFinder), new FakeProjectFilesystem());

    assertEquals("All non-generated source files are under one source tmp.",
        ImmutableSet.of("./package/src/"), result);

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

    ImmutableSet<String> result = TestCommand.getPathToSourceFolders(
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

    ImmutableSet<String> result = TestCommand.getPathToSourceFolders(
        javaLibrary, Optional.of(defaultJavaPackageFinder), new FakeProjectFilesystem());

    assertEquals("The non-generated source files are under two different source folders.",
        ImmutableSet.of("./package/src-gen/", "./package/src/"), result);

    verify(defaultJavaPackageFinder);
  }

  private TestCommandOptions getOptions(String...args) throws CmdLineException {
    TestCommandOptions options = new TestCommandOptions(new FakeBuckConfig());
    new CmdLineParserAdditionalOptions(options).parseArgument(args);
    return options;
  }

  private ActionGraph createDependencyGraphFromBuildRules(Iterable<? extends BuildRule> rules) {
    MutableDirectedGraph<BuildRule> graph = new MutableDirectedGraph<>();
    for (BuildRule rule : rules) {
      for (BuildRule dep : rule.getDeps()) {
        graph.addEdge(rule, dep);
      }
    }

    return new ActionGraph(graph);
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
    TestCommand.writeXmlOutput(testResultsList, writer);
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
  public void testGetCandidateRulesByIncludedLabels() throws CmdLineException {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    FakeTestRule rule1 = new FakeTestRule(
        JavaTestDescription.TYPE,
        ImmutableSet.<Label>of(ImmutableLabel.of("windows"), ImmutableLabel.of("linux")),
        BuildTargetFactory.newInstance("//:for"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of()
    );

    FakeTestRule rule2 = new FakeTestRule(
        JavaTestDescription.TYPE,
        ImmutableSet.<Label>of(ImmutableLabel.of("android")),
        BuildTargetFactory.newInstance("//:teh"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of(rule1)
    );

    FakeTestRule rule3 = new FakeTestRule(
        JavaTestDescription.TYPE,
        ImmutableSet.<Label>of(ImmutableLabel.of("windows")),
        BuildTargetFactory.newInstance("//:lulz"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of(rule2)
    );

    Iterable<FakeTestRule> rules = Lists.newArrayList(rule1, rule2, rule3);
    ActionGraph graph = createDependencyGraphFromBuildRules(rules);
    TestCommandOptions options = getOptions("--include", "linux", "windows");

    Iterable<TestRule> result = TestCommand.filterTestRules(options,
        TestCommand.getCandidateRules(graph));
    assertThat(result, containsInAnyOrder((TestRule) rule1, rule3));
  }

  @Test
  public void testFilterBuilds() throws CmdLineException {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    TestCommandOptions options = getOptions("--exclude", "linux", "windows");

    TestRule rule1 = new FakeTestRule(
        JavaTestDescription.TYPE,
        ImmutableSet.<Label>of(ImmutableLabel.of("windows"), ImmutableLabel.of("linux")),
        BuildTargetFactory.newInstance("//:for"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of()
    );

    TestRule rule2 = new FakeTestRule(
        JavaTestDescription.TYPE,
        ImmutableSet.<Label>of(ImmutableLabel.of("android")),
        BuildTargetFactory.newInstance("//:teh"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of()
    );

    TestRule rule3 = new FakeTestRule(
        JavaTestDescription.TYPE,
        ImmutableSet.<Label>of(ImmutableLabel.of("windows")),
        BuildTargetFactory.newInstance("//:lulz"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of()
    );

    List<TestRule> testRules = ImmutableList.of(rule1, rule2, rule3);

    Iterable<TestRule> result = TestCommand.filterTestRules(options, testRules);
    assertThat(result, contains(rule2));
  }

  @Test
  public void testLabelConjunctionsWithInclude() throws CmdLineException {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    TestCommandOptions options = getOptions("--include", "windows+linux");

    TestRule rule1 = new FakeTestRule(
        JavaTestDescription.TYPE,
        ImmutableSet.<Label>of(ImmutableLabel.of("windows"), ImmutableLabel.of("linux")),
        BuildTargetFactory.newInstance("//:for"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of()
    );

    TestRule rule2 = new FakeTestRule(
        JavaTestDescription.TYPE,
        ImmutableSet.<Label>of(ImmutableLabel.of("windows")),
        BuildTargetFactory.newInstance("//:lulz"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of()
    );

    List<TestRule> testRules = ImmutableList.of(rule1, rule2);

    Iterable<TestRule> result = TestCommand.filterTestRules(options, testRules);
    assertEquals(ImmutableSet.of(rule1), result);
  }

  @Test
  public void testLabelConjunctionsWithExclude() throws CmdLineException {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    TestCommandOptions options = getOptions("--exclude", "windows+linux");

    TestRule rule1 = new FakeTestRule(
        JavaTestDescription.TYPE,
        ImmutableSet.<Label>of(ImmutableLabel.of("windows"), ImmutableLabel.of("linux")),
        BuildTargetFactory.newInstance("//:for"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of()
    );

    TestRule rule2 = new FakeTestRule(
        JavaTestDescription.TYPE,
        ImmutableSet.<Label>of(ImmutableLabel.of("windows")),
        BuildTargetFactory.newInstance("//:lulz"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of()
    );

    List<TestRule> testRules = ImmutableList.of(rule1, rule2);

    Iterable<TestRule> result = TestCommand.filterTestRules(options, testRules);
    assertEquals(ImmutableSet.of(rule2), result);
  }

  @Test
  public void testLabelPriority() throws CmdLineException {
    TestCommandOptions options = getOptions("--exclude", "c", "--include", "a+b");

    TestRule rule = new FakeTestRule(
        JavaTestDescription.TYPE,
        ImmutableSet.<Label>of(
            ImmutableLabel.of("a"),
            ImmutableLabel.of("b"),
            ImmutableLabel.of("c")),
        BuildTargetFactory.newInstance("//:for"),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSortedSet.<BuildRule>of()
    );

    List<TestRule> testRules = ImmutableList.of(rule);

    Iterable<TestRule> result = TestCommand.filterTestRules(options, testRules);
    assertEquals(ImmutableSet.of(), result);
  }

  @Test
  public void testLabelPlingSyntax() throws CmdLineException {
    TestCommandOptions options = getOptions("--labels", "!c", "a+b");

    TestRule rule = new FakeTestRule(
        JavaTestDescription.TYPE,
        ImmutableSet.<Label>of(
            ImmutableLabel.of("a"),
            ImmutableLabel.of("b"),
            ImmutableLabel.of("c")),
        BuildTargetFactory.newInstance("//:for"),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSortedSet.<BuildRule>of()
    );

    List<TestRule> testRules = ImmutableList.of(rule);

    Iterable<TestRule> result = TestCommand.filterTestRules(options, testRules);
    assertEquals(ImmutableSet.of(), result);
  }

  @Test
  public void testNoTransitiveTests() throws CmdLineException {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    TestCommandOptions options = getOptions("--exclude-transitive-tests", "//:wow");

    FakeTestRule rule1 = new FakeTestRule(
        JavaTestDescription.TYPE,
        ImmutableSet.<Label>of(ImmutableLabel.of("windows"), ImmutableLabel.of("linux")),
        BuildTargetFactory.newInstance("//:for"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of()
    );

    FakeTestRule rule2 = new FakeTestRule(
        JavaTestDescription.TYPE,
        ImmutableSet.<Label>of(ImmutableLabel.of("windows")),
        BuildTargetFactory.newInstance("//:lulz"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of(rule1)
    );

    FakeTestRule rule3 = new FakeTestRule(
        JavaTestDescription.TYPE,
        ImmutableSet.<Label>of(ImmutableLabel.of("linux")),
        BuildTargetFactory.newInstance("//:wow"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of(rule2)
    );

    List<TestRule> testRules = ImmutableList.<TestRule>of(rule1, rule2, rule3);
    Iterable<TestRule> filtered = TestCommand.filterTestRules(options, testRules);

    assertEquals(rule3, Iterables.getOnlyElement(filtered));
  }

  @Test
  public void testNoTransitiveTestsWhenLabelExcludeWins() throws CmdLineException {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    TestCommandOptions options = getOptions("--labels", "!linux", "--always-exclude",
        "--exclude-transitive-tests", "//:for", "//:lulz");

    FakeTestRule rule1 = new FakeTestRule(
        JavaTestDescription.TYPE,
        ImmutableSet.<Label>of(ImmutableLabel.of("windows"), ImmutableLabel.of("linux")),
        BuildTargetFactory.newInstance("//:for"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of()
    );

    FakeTestRule rule2 = new FakeTestRule(
        JavaTestDescription.TYPE,
        ImmutableSet.<Label>of(ImmutableLabel.of("windows")),
        BuildTargetFactory.newInstance("//:lulz"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of(rule1)
    );

    List<TestRule> testRules = ImmutableList.<TestRule>of(rule1, rule2);
    Iterable<TestRule> filtered = TestCommand.filterTestRules(options, testRules);

    assertEquals(rule2, Iterables.getOnlyElement(filtered));
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
        TestCommand.isTestRunRequiredForTest(
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
        JavaTestDescription.TYPE,
        ImmutableSet.<Label>of(ImmutableLabel.of("windows")),
        BuildTargetFactory.newInstance("//:lulz"),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSortedSet.<BuildRule>of()
    );

    CachingBuildEngine cachingBuildEngine = createMock(CachingBuildEngine.class);
    expect(cachingBuildEngine.getBuildRuleResult(BuildTargetFactory.newInstance("//:lulz")))
        .andReturn(new BuildRuleSuccess(testRule, BuildRuleSuccess.Type.FETCHED_FROM_CACHE));
    replay(executionContext, cachingBuildEngine);

    assertTrue(
        "A cache hit updates the build artifact but not the test results. " +
            "Therefore, the test should be re-run to ensure the test results are up to date.",
        TestCommand.isTestRunRequiredForTest(
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
        JavaTestDescription.TYPE,
        ImmutableSet.<Label>of(ImmutableLabel.of("windows")),
        BuildTargetFactory.newInstance("//:lulz"),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSortedSet.<BuildRule>of()
    );

    CachingBuildEngine cachingBuildEngine = createMock(CachingBuildEngine.class);
    expect(cachingBuildEngine.getBuildRuleResult(BuildTargetFactory.newInstance("//:lulz")))
        .andReturn(new BuildRuleSuccess(testRule, BuildRuleSuccess.Type.BUILT_LOCALLY));
    replay(executionContext, cachingBuildEngine);

    assertTrue(
        "A test built locally should always run regardless of any cached result. ",
        TestCommand.isTestRunRequiredForTest(
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
        JavaTestDescription.TYPE,
        ImmutableSet.<Label>of(ImmutableLabel.of("windows")),
        BuildTargetFactory.newInstance("//:lulz"),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSortedSet.<BuildRule>of()
    ) {

      @Override
      public boolean hasTestResultFiles(ExecutionContext context) {
        return true;
      }
    };

    TestRuleKeyFileHelper testRuleKeyFileHelper = createNiceMock(TestRuleKeyFileHelper.class);
    expect(testRuleKeyFileHelper.isRuleKeyInDir(testRule)).andReturn(false);

    CachingBuildEngine cachingBuildEngine = createMock(CachingBuildEngine.class);
    expect(cachingBuildEngine.getBuildRuleResult(BuildTargetFactory.newInstance("//:lulz")))
        .andReturn(new BuildRuleSuccess(testRule, BuildRuleSuccess.Type.MATCHING_RULE_KEY));
    replay(executionContext, cachingBuildEngine, testRuleKeyFileHelper);

    assertTrue(
        "A cached build should run the tests if the test output directory\'s rule key is not " +
            "present or does not matche the rule key for the test.",
        TestCommand.isTestRunRequiredForTest(
            testRule,
            cachingBuildEngine,
            executionContext,
            testRuleKeyFileHelper,
            /* results cache enabled */ true,
            /* running with test selectors */ false));

    verify(executionContext, cachingBuildEngine, testRuleKeyFileHelper);
  }

  @Test
  public void testIfAGlobalExcludeExcludesALabel() throws CmdLineException {
    BuckConfig config = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "test",
            ImmutableMap.of("excluded_labels", "e2e")));
    assertThat(config.getDefaultRawExcludedLabelSelectors(), contains("e2e"));
    TestCommandOptions options = new TestCommandOptions(config);

    new CmdLineParserAdditionalOptions(options).parseArgument();

    assertFalse(options.isMatchedByLabelOptions(ImmutableSet.<Label>of(ImmutableLabel.of("e2e"))));
  }

  @Test
  public void testIfALabelIsIncludedItShouldNotBeExcludedEvenIfTheExcludeIsGlobal()
      throws CmdLineException {
    BuckConfig config = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "test",
            ImmutableMap.of("excluded_labels", "e2e")));
    assertThat(config.getDefaultRawExcludedLabelSelectors(), contains("e2e"));
    TestCommandOptions options = new TestCommandOptions(config);

    new CmdLineParserAdditionalOptions(options).parseArgument("--include", "e2e");

    assertTrue(options.isMatchedByLabelOptions(ImmutableSet.<Label>of(ImmutableLabel.of("e2e"))));
  }

  @Test
  public void testIncludingATestOnTheCommandLineMeansYouWouldLikeItRun() throws CmdLineException {
    String excludedLabel = "exclude_me";
    BuckConfig config = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "test",
            ImmutableMap.of("excluded_labels", excludedLabel)));
    assertThat(config.getDefaultRawExcludedLabelSelectors(), contains(excludedLabel));
    TestCommandOptions options = new TestCommandOptions(config);

    new CmdLineParserAdditionalOptions(options).parseArgument("//example:test");

    FakeTestRule rule = new FakeTestRule(
        BuildRuleType.of("java_test"),
        /* labels */ ImmutableSet.<Label>of(ImmutableLabel.of(excludedLabel)),
        BuildTargetFactory.newInstance("//example:test"),
        new SourcePathResolver(new BuildRuleResolver()),
        /* deps */ ImmutableSortedSet.<BuildRule>of()
        /* visibility */);
    Iterable<TestRule> filtered =
        TestCommand.filterTestRules(options, ImmutableSet.<TestRule>of(rule));

    assertEquals(rule, Iterables.getOnlyElement(filtered));
  }

  @Test
  public void shouldAlwaysDefaultToOneThreadWhenRunningTestsWithDebugFlag()
      throws CmdLineException {
    TestCommandOptions options = getOptions("-j", "15");

    assertEquals(15, options.getNumTestThreads());

    options = getOptions("-j", "15", "--debug");

    assertEquals(1, options.getNumTestThreads());
  }
}
