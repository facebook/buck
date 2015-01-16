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

import com.facebook.buck.command.Build;
import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.DefaultJavaPackageFinder;
import com.facebook.buck.java.GenerateCodeCoverageReportStep;
import com.facebook.buck.java.JUnitStep;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.JavaTest;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.TargetNodePredicateSpec;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleSuccess;
import com.facebook.buck.rules.IndividualTestEvent;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.rules.TargetGraphTransformer;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.rules.TestRunEvent;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.CoverageReportFormat;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.result.groups.TestResultsGrouper;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Verbosity;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

public class TestCommand extends AbstractCommandRunner<TestCommandOptions> {

  public static final int TEST_FAILURES_EXIT_CODE = 42;
  private final TargetGraphTransformer<ActionGraph> targetGraphTransformer;

  public TestCommand(CommandRunnerParams params) {
    super(params);

    this.targetGraphTransformer = new TargetGraphToActionGraph(
        params.getBuckEventBus(),
        new BuildTargetNodeToBuildRuleTransformer());
  }

  @Override
  TestCommandOptions createOptions(BuckConfig buckConfig) {
    return new TestCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(final TestCommandOptions options)
      throws IOException, InterruptedException {
    // If the user asked to run all of the tests, use a special method for that that is optimized to
    // parse all of the build files and traverse the action graph to find all of the tests to
    // run.
    if (options.isRunAllTests()) {
      try {
        return runAllTests(options);
      } catch (BuildTargetException | BuildFileParseException | ExecutionException e) {
        console.printBuildFailureWithoutStacktrace(e);
        return 1;
      }
    }

    BuildCommand buildCommand = new BuildCommand(getCommandRunnerParams());

    int exitCode = buildCommand.runCommandWithOptions(options);
    if (exitCode != 0) {
      return exitCode;
    }

    Build build = buildCommand.getBuild();

    Iterable<TestRule> results = getCandidateRules(build.getActionGraph());

    results = filterTestRules(options, results);
    if (options.isDryRun()) {
      printMatchingTestRules(console, results);
    }

    BuildContext buildContext = Preconditions.checkNotNull(build.getBuildContext());
    ExecutionContext buildExecutionContext = build.getExecutionContext();
    ExecutionContext testExecutionContext = ExecutionContext.builder().
        setExecutionContext(buildExecutionContext).
        setTargetDevice(options.getTargetDeviceOptional()).
        build();

    try {
      return runTestsAndShutdownExecutor(results,
          buildContext,
          testExecutionContext,
          options);
    } catch (ExecutionException e) {
      console.printBuildFailureWithoutStacktrace(e);
      return 1;
    }
  }

  /**
   * Returns the ShellCommand object that is supposed to generate a code coverage report from data
   * obtained during the test run. This method will also generate a set of source paths to the class
   * files tested during the test run.
   */
  private Step getReportCommand(
      ImmutableSet<JavaLibrary> rulesUnderTest,
      Optional<DefaultJavaPackageFinder> defaultJavaPackageFinderOptional,
      ProjectFilesystem filesystem,
      Path outputDirectory,
      CoverageReportFormat format) {
    ImmutableSet.Builder<String> srcDirectories = ImmutableSet.builder();
    ImmutableSet.Builder<Path> pathsToClasses = ImmutableSet.builder();

    // Add all source directories of java libraries that we are testing to -sourcepath.
    for (JavaLibrary rule : rulesUnderTest) {
      ImmutableSet<String> sourceFolderPath =
          getPathToSourceFolders(rule, defaultJavaPackageFinderOptional, filesystem);
      if (!sourceFolderPath.isEmpty()) {
        srcDirectories.addAll(sourceFolderPath);
      }
      Path pathToOutput = rule.getPathToOutputFile();
      if (pathToOutput == null) {
        continue;
      }
      pathsToClasses.add(pathToOutput);
    }

    return new GenerateCodeCoverageReportStep(
        srcDirectories.build(),
        pathsToClasses.build(),
        outputDirectory,
        format);
  }

  /**
   * Returns a set of source folders of the java files of a library.
   */
  @VisibleForTesting
  static ImmutableSet<String> getPathToSourceFolders(
      JavaLibrary rule,
      Optional<DefaultJavaPackageFinder> defaultJavaPackageFinderOptional,
      ProjectFilesystem filesystem) {
    ImmutableSet<Path> javaSrcs = rule.getJavaSrcs();

    // A Java library rule with just resource files has an empty javaSrcs.
    if (javaSrcs.isEmpty()) {
      return ImmutableSet.of();
    }

    // If defaultJavaPackageFinderOptional is not present, then it could mean that there was an
    // error reading from the buck configuration file.
    if (!defaultJavaPackageFinderOptional.isPresent()) {
      throw new HumanReadableException(
          "Please include a [java] section with src_root property in the .buckconfig file.");
    }

    DefaultJavaPackageFinder defaultJavaPackageFinder = defaultJavaPackageFinderOptional.get();

    // Iterate through all source paths to make sure we are generating a complete set of source
    // folders for the source paths.
    Set<String> srcFolders = Sets.newHashSet();
    loopThroughSourcePath:
    for (Path javaSrcPath : javaSrcs) {
      if (!MorePaths.isGeneratedFile(javaSrcPath)) {
        // If the source path is already under a known source folder, then we can skip this
        // source path.
        for (String srcFolder : srcFolders) {
          if (javaSrcPath.startsWith(srcFolder)) {
            continue loopThroughSourcePath;
          }
        }

        // If the source path is under one of the source roots, then we can just add the source
        // root.
        ImmutableSortedSet<String> pathsFromRoot = defaultJavaPackageFinder.getPathsFromRoot();
        for (String root : pathsFromRoot) {
          if (javaSrcPath.startsWith(root)) {
            srcFolders.add(root);
            continue loopThroughSourcePath;
          }
        }

        // Traverse the file system from the parent directory of the java file until we hit the
        // parent of the src root directory.
        ImmutableSet<String> pathElements = defaultJavaPackageFinder.getPathElements();
        File directory = filesystem.getFileForRelativePath(javaSrcPath.getParent());
        while (directory != null && !pathElements.contains(directory.getName())) {
          directory = directory.getParentFile();
        }

        if (directory != null) {
          String directoryPath = directory.getPath();
          if (!directoryPath.endsWith("/")) {
            directoryPath += "/";
          }
          srcFolders.add(directoryPath);
        }
      }
    }

    return ImmutableSet.copyOf(srcFolders);
  }

  private int runAllTests(TestCommandOptions options) throws IOException,
      BuildTargetException, BuildFileParseException, ExecutionException, InterruptedException {
    // We won't have a list of targets until the build is already started, so BuildEvents will get
    // an empty list.
    ImmutableSet<BuildTarget> emptyTargetsList = ImmutableSet.of();

    // Post the build started event, setting it to the Parser recorded start time if appropriate.
    if (getParser().getParseStartTime().isPresent()) {
      getBuckEventBus().post(
          BuildEvent.started(emptyTargetsList),
          getParser().getParseStartTime().get());
    } else {
      getBuckEventBus().post(BuildEvent.started(emptyTargetsList));
    }

    // The first step is to parse all of the build files. This will populate the parser and find all
    // of the test rules.
    TargetGraph targetGraph = getParser().buildTargetGraphForTargetNodeSpecs(
        ImmutableList.of(
            new TargetNodePredicateSpec(
                new Predicate<TargetNode<?>>() {
                  @Override
                  public boolean apply(TargetNode<?> input) {
                    return input.getType().isTestRule();
                  }
                },
                getProjectFilesystem().getIgnorePaths())),
        options.getDefaultIncludes(),
        getBuckEventBus(),
        console,
        environment,
        options.getEnableProfiling());

    ActionGraph graph = targetGraphTransformer.apply(targetGraph);

    // Look up all of the test rules in the action graph.
    Iterable<TestRule> testRules = Iterables.filter(graph.getNodes(), TestRule.class);

    testRules = filterTestRules(options, testRules);
    if (options.isDryRun()) {
      printMatchingTestRules(console, testRules);
    }

    // Create artifact cache to initialize Cassandra connection, if appropriate.
    ArtifactCache artifactCache = getArtifactCache();

    try (Build build = options.createBuild(
        options.getBuckConfig(),
        graph,
        getProjectFilesystem(),
        getAndroidDirectoryResolver(),
        getBuildEngine(),
        artifactCache,
        console,
        getBuckEventBus(),
        options.getTargetDeviceOptional(),
        getCommandRunnerParams().getPlatform(),
        getCommandRunnerParams().getEnvironment(),
        getCommandRunnerParams().getObjectMapper(),
        getCommandRunnerParams().getClock())) {

      // Build all of the test rules.
      int exitCode = build.executeAndPrintFailuresToConsole(
          testRules,
          options.isKeepGoing(),
          console,
          options.getPathToBuildReport());

      getBuckEventBus().post(BuildEvent.finished(emptyTargetsList, exitCode));
      if (exitCode != 0) {
        return exitCode;
      }

      // Once all of the rules are built, then run the tests.
      return runTestsAndShutdownExecutor(testRules,
          Preconditions.checkNotNull(build.getBuildContext()),
          build.getExecutionContext(),
          options);
    }
  }

  private void printMatchingTestRules(Console console, Iterable<TestRule> testRules) {
    PrintStream out = console.getStdOut();
    ImmutableList<TestRule> list = ImmutableList.copyOf(testRules);
    out.println(String.format("MATCHING TEST RULES (%d):", list.size()));
    out.println("");
    if (list.isEmpty()) {
      out.println("  (none)");
    } else {
      for (TestRule testRule : testRules) {
        out.println("  " + testRule.getBuildTarget());
      }
    }
    out.println("");
  }

  @VisibleForTesting
  static Iterable<TestRule> getCandidateRules(
      ActionGraph graph) {
    AbstractBottomUpTraversal<BuildRule, List<TestRule>> traversal =
        new AbstractBottomUpTraversal<BuildRule, List<TestRule>>(graph) {

      private final List<TestRule> results = Lists.newArrayList();

      @Override
      public void visit(BuildRule buildRule) {
        TestRule testRule = null;
        if (buildRule instanceof TestRule) {
          testRule = (TestRule) buildRule;
        }
        if (testRule != null) {
          results.add(testRule);
        }
      }

      @Override
      public List<TestRule> getResult() {
        return results;
      }
    };
    traversal.traverse();
    return traversal.getResult();
  }

  @VisibleForTesting
  static Iterable<TestRule> filterTestRules(final TestCommandOptions options,
      Iterable<TestRule> testRules) {
    ImmutableSortedSet.Builder<TestRule> builder =
        ImmutableSortedSet.orderedBy(
            new Comparator<TestRule>() {
              @Override
              public int compare(TestRule o1, TestRule o2) {
                return o1.getBuildTarget().getFullyQualifiedName().compareTo(
                    o2.getBuildTarget().getFullyQualifiedName());
              }
            });

    // We always want to run the rules that are given on the command line. Always. Unless we don't
    // want to.
    if (!options.shouldExcludeWin()) {
      ImmutableSet<String> allTargets = options.getArgumentsFormattedAsBuildTargets();
      for (TestRule rule : testRules) {
        if (allTargets.contains(rule.getBuildTarget().getFullyQualifiedName())) {
          builder.add(rule);
        }
      }
    }

    // Filter out all test rules that contain labels we've excluded.
    builder.addAll(Iterables.filter(testRules, new Predicate<TestRule>() {
      @Override
      public boolean apply(TestRule rule) {
        return options.isMatchedByLabelOptions(rule.getLabels());
      }
    }));

    return builder.build();
  }

  private int runTestsAndShutdownExecutor(
      Iterable<TestRule> tests,
      BuildContext buildContext,
      ExecutionContext executionContext,
      TestCommandOptions options)
      throws IOException, ExecutionException, InterruptedException {

    try (DefaultStepRunner stepRunner =
            new DefaultStepRunner(executionContext, options.getNumThreads())) {
      return runTests(tests, buildContext, executionContext, stepRunner, options);
    }
  }

  @SuppressWarnings("PMD.EmptyCatchBlock")
  private int runTests(
      Iterable<TestRule> tests,
      BuildContext buildContext,
      ExecutionContext executionContext,
      StepRunner stepRunner,
      final TestCommandOptions options)
      throws IOException, ExecutionException, InterruptedException {

    if (options.isUsingOneTimeOutputDirectories()) {
      BuckConstant.setOneTimeTestSubdirectory(UUID.randomUUID().toString());
    }

    ImmutableSet<JavaLibrary> rulesUnderTest;
    // If needed, we first run instrumentation on the class files.
    if (options.isCodeCoverageEnabled()) {
      rulesUnderTest = getRulesUnderTest(tests);
      if (!rulesUnderTest.isEmpty()) {
        try {
          stepRunner.runStep(
              new MakeCleanDirectoryStep(JUnitStep.JACOCO_OUTPUT_DIR));
        } catch (StepFailedException e) {
          console.printBuildFailureWithoutStacktrace(e);
          return 1;
        }
      }
    } else {
      rulesUnderTest = ImmutableSet.of();
    }

    getBuckEventBus().post(TestRunEvent.started(
        options.isRunAllTests(),
        options.getTestSelectorList(),
        options.shouldExplainTestSelectorList(),
        options.getArgumentsFormattedAsBuildTargets()));

    // Start running all of the tests. The result of each java_test() rule is represented as a
    // ListenableFuture.
    List<ListenableFuture<TestResults>> results = Lists.newArrayList();

    // Unless `--verbose 0` is specified, print out test results as they become available.
    // Failures with the ListenableFuture should always be printed, as they indicate an error with
    // Buck, not the test being run.
    Verbosity verbosity = console.getVerbosity();
    final boolean printTestResults = (verbosity != Verbosity.SILENT);

    // For grouping results!
    TestResultsGrouper grouper = null;
    if (options.isIgnoreFailingDependencies()) {
      grouper = new TestResultsGrouper(tests);
    }

    TestRuleKeyFileHelper testRuleKeyFileHelper = new TestRuleKeyFileHelper(
        executionContext.getProjectFilesystem(),
        getBuildEngine());
    for (TestRule test : tests) {
      // Determine whether the test needs to be executed.
      boolean isTestRunRequired;
      isTestRunRequired = isTestRunRequiredForTest(
          test,
          getBuildEngine(),
          executionContext,
          testRuleKeyFileHelper,
          options.isResultsCacheEnabled(),
          !options.getTestSelectorList().isEmpty());


      List<Step> steps;
      if (isTestRunRequired) {
        getBuckEventBus().post(IndividualTestEvent.started(
            options.getArgumentsFormattedAsBuildTargets()));
        ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();
        BuildEngine cachingBuildEngine = getBuildEngine();
        Preconditions.checkState(cachingBuildEngine.isRuleBuilt(test.getBuildTarget()));
        List<Step> testSteps = test.runTests(
            buildContext,
            executionContext,
            options.isDryRun(),
            options.isShufflingTests(),
            options.getTestSelectorList());
        if (!testSteps.isEmpty()) {
          stepsBuilder.addAll(testSteps);
          stepsBuilder.add(testRuleKeyFileHelper.createRuleKeyInDirStep(test));
        }
        steps = stepsBuilder.build();
      } else {
        steps = ImmutableList.of();
      }

      // Always run the commands, even if the list of commands as empty. There may be zero commands
      // because the rule is cached, but its results must still be processed.
      ListenableFuture<TestResults> testResults =
          stepRunner.runStepsAndYieldResult(steps,
              getCachingStatusTransformingCallable(
                  isTestRunRequired,
                  test.interpretTestResults(executionContext,
                      /*isUsingTestSelectors*/ !options.getTestSelectorList().isEmpty(),
                      /*isDryRun*/ options.isDryRun())),
              test.getBuildTarget());
      FutureCallback<TestResults> onTestFinishedCallback =
          getFutureCallback(grouper, test, options, printTestResults);
      Futures.addCallback(testResults, onTestFinishedCallback);
      results.add(testResults);
    }

    // Block until all the tests have finished running.
    ListenableFuture<List<TestResults>> uberFuture = Futures.allAsList(results);
    List<TestResults> completedResults;
    try {
      completedResults = uberFuture.get();
    } catch (ExecutionException e) {
      e.printStackTrace(getStdErr());
      return 1;
    } catch (InterruptedException e) {
      try {
        uberFuture.cancel(true);
      } catch (CancellationException ignored) {
        // Rethrow original InterruptedException instead.
      }
      Thread.currentThread().interrupt();
      throw e;
    }

    getBuckEventBus().post(TestRunEvent.finished(
        options.getArgumentsFormattedAsBuildTargets(), completedResults));

    // Write out the results as XML, if requested.
    String path = options.getPathToXmlTestOutput();
    if (path != null) {
      try (Writer writer = Files.newWriter(
        new File(path),
        Charsets.UTF_8)) {
        writeXmlOutput(completedResults, writer);
      }
    }

    // Generate the code coverage report.
    if (options.isCodeCoverageEnabled() && !rulesUnderTest.isEmpty()) {
      try {
        Optional<DefaultJavaPackageFinder> defaultJavaPackageFinderOptional =
            options.getJavaPackageFinder();
        stepRunner.runStep(
            getReportCommand(rulesUnderTest,
                defaultJavaPackageFinderOptional,
                getProjectFilesystem(),
                JUnitStep.JACOCO_OUTPUT_DIR,
                options.getCoverageReportFormat()));
      } catch (StepFailedException e) {
        console.printBuildFailureWithoutStacktrace(e);
        return 1;
      }
    }

    boolean failures = Iterables.any(completedResults, new Predicate<TestResults>() {
      @Override
      public boolean apply(TestResults results) {
        return !results.isSuccess();
      }
    });

    boolean significantAssumptionViolations = false;
    if (options.getBuckConfig().isTreatingAssumptionsAsErrors()) {
      // Assumption-violations are only significant if we are treating them as errors.
      significantAssumptionViolations =
          Iterables.any(completedResults, new Predicate<TestResults>() {
        @Override
        public boolean apply(TestResults results) {
          return results.hasAssumptionViolations();
        }
      });
    }

    return (failures || significantAssumptionViolations) ? TEST_FAILURES_EXIT_CODE : 0;
  }

  private FutureCallback<TestResults> getFutureCallback(
      @Nullable final TestResultsGrouper grouper,
      final TestRule testRule,
      final TestCommandOptions options,
      final boolean printTestResults) {
    return new FutureCallback<TestResults>() {

      @Override
      public void onSuccess(TestResults testResults) {
        if (printTestResults) {
          if (grouper == null) {
            postTestResults(testResults);
          } else {
            Map<TestRule, TestResults> postableTestResultsMap = grouper.post(testRule, testResults);
            for (TestResults rr : postableTestResultsMap.values()) {
              postTestResults(rr);
            }
          }
        }
      }

      private void postTestResults(TestResults testResults) {
        getBuckEventBus().post(IndividualTestEvent.finished(
            options.getArgumentsFormattedAsBuildTargets(), testResults));
      }

      @Override
      public void onFailure(Throwable throwable) {
        // This should never happen, but if it does, that means that something has gone awry, so
        // we should bubble it up.
        throwable.printStackTrace(getStdErr());
      }
    };
  }

  private Callable<TestResults> getCachingStatusTransformingCallable(
      boolean isTestRunRequired,
      final Callable<TestResults> originalCallable) {
    if (isTestRunRequired) {
      return originalCallable;
    }
    return new Callable<TestResults>() {
      @Override
      public TestResults call() throws Exception {
        TestResults originalTestResults = originalCallable.call();
        ImmutableList<TestCaseSummary> cachedTestResults = FluentIterable
            .from(originalTestResults.getTestCases())
            .transform(TestCaseSummary.TO_CACHED_TRANSFORMATION)
            .toList();
        return new TestResults(
            originalTestResults.getBuildTarget(),
            cachedTestResults,
            originalTestResults.getContacts());
      }
    };
  }

  @VisibleForTesting
  static boolean isTestRunRequiredForTest(
      TestRule test,
      BuildEngine cachingBuildEngine,
      ExecutionContext executionContext,
      TestRuleKeyFileHelper testRuleKeyFileHelper,
      boolean isResultsCacheEnabled,
      boolean isRunningWithTestSelectors)
      throws IOException, ExecutionException, InterruptedException {
    boolean isTestRunRequired;
    BuildRuleSuccess success;
    if (executionContext.isDebugEnabled()) {
      // If debug is enabled, then we should always run the tests as the user is expecting to
      // hook up a debugger.
      isTestRunRequired = true;
    } else if (isRunningWithTestSelectors) {
      // As a feature to aid developers, we'll assume that when we are using test selectors,
      // we should always run each test (and never look at the cache.)
      // TODO(user) When #3090004 and #3436849 are closed we can respect the cache again.
      isTestRunRequired = true;
    } else if (((success = cachingBuildEngine.getBuildRuleResult(
        test.getBuildTarget())) != null) &&
            success.getType() == BuildRuleSuccess.Type.MATCHING_RULE_KEY &&
            isResultsCacheEnabled &&
            test.hasTestResultFiles(executionContext) &&
            testRuleKeyFileHelper.isRuleKeyInDir(test)) {
      // If this build rule's artifacts (which includes the rule's output and its test result
      // files) are up to date, then no commands are necessary to run the tests. The test result
      // files will be read from the XML files in interpretTestResults().
      isTestRunRequired = false;
    } else {
      isTestRunRequired = true;
    }
    return isTestRunRequired;
  }

  /**
   * Generates the set of Java library rules under test.
   */
  private ImmutableSet<JavaLibrary> getRulesUnderTest(Iterable<TestRule> tests) {
    ImmutableSet.Builder<JavaLibrary> rulesUnderTest = ImmutableSet.builder();

    // Gathering all rules whose source will be under test.
    for (TestRule test : tests) {
      if (test instanceof JavaTest) {
        JavaTest javaTest = (JavaTest) test;
        ImmutableSet<BuildRule> sourceUnderTest = javaTest.getSourceUnderTest();
        for (BuildRule buildRule : sourceUnderTest) {
          if (buildRule instanceof JavaLibrary) {
            JavaLibrary javaLibrary = (JavaLibrary) buildRule;
            rulesUnderTest.add(javaLibrary);
          } else {
            throw new HumanReadableException(
                "Test '%s' is a java_test() " +
                "but it is testing module '%s' " +
                "which is not a java_library()!",
                test.getBuildTarget(),
                buildRule.getBuildTarget());
          }
        }
      }
    }

    return rulesUnderTest.build();
  }

  /**
   * Writes the test results in XML format to the supplied writer.
   *
   * This method does NOT close the writer object.
   * @param allResults The test results.
   * @param writer The writer in which the XML data will be written to.
   */
  public static void writeXmlOutput(List<TestResults> allResults, Writer writer)
      throws IOException {
    try {
      // Build the XML output.
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      DocumentBuilder docBuilder = dbf.newDocumentBuilder();
      Document doc = docBuilder.newDocument();
      // Create the <tests> tag. All test data will be within this tag.
      Element testsEl = doc.createElement("tests");
      doc.appendChild(testsEl);

      for (TestResults results : allResults) {
        for (TestCaseSummary testCase : results.getTestCases()) {
          // Create the <test name="..." status="..." time="..."> tag.
          // This records a single test case result in the test suite.
          Element testEl = doc.createElement("test");
          testEl.setAttribute("name", testCase.getTestCaseName());
          testEl.setAttribute("status", testCase.isSuccess() ? "PASS" : "FAIL");
          testEl.setAttribute("time", Long.toString(testCase.getTotalTime()));
          testsEl.appendChild(testEl);

          // Loop through the test case and add XML data (name, message, and
          // stacktrace) for each individual test, if present.
          addExtraXmlInfo(testCase, testEl);
        }
      }

      // Write XML to the writer.
      TransformerFactory tf = TransformerFactory.newInstance();
      Transformer transformer = tf.newTransformer();
      transformer.transform(new DOMSource(doc), new StreamResult(writer));
    } catch (TransformerException | ParserConfigurationException ex) {
      throw new IOException("Unable to build the XML document!");
    }
  }

  /**
   * A helper method that adds extra XML.
   *
   * This includes a test name, time (in ms), message, and stack trace, when
   * present.
   * Example:
   *
   * <pre>
   * &lt;testresult name="failed_test" time="200">
   *   &lt;message>Reason for test failure&lt;/message>
   *   &lt;stacktrace>Stacktrace here&lt;/stacktrace>
   * &lt;/testresult>
   * </pre>
   *
   * @param testCase The test case summary containing one or more tests.
   * @param testEl The XML element object for the <test> tag, in which extra
   *     information tags will be added.
   */
  @VisibleForTesting
  static void addExtraXmlInfo(TestCaseSummary testCase, Element testEl) {
    Document doc = testEl.getOwnerDocument();
    // Loop through the test case and extract test data.
    for (TestResultSummary testResult : testCase.getTestResults()) {
      // Extract the test name and time.
      String name = Strings.nullToEmpty(testResult.getTestName());
      String time = Long.toString(testResult.getTime());

      // Create the tag: <testresult name="..." time="...">
      Element testResultEl = doc.createElement("testresult");
      testResultEl.setAttribute("name", name);
      testResultEl.setAttribute("time", time);
      testEl.appendChild(testResultEl);

      // Create the tag: <message>(Error message here)</message>
      Element messageEl = doc.createElement("message");
      String message = Strings.nullToEmpty(testResult.getMessage());
      messageEl.appendChild(doc.createTextNode(message));
      testResultEl.appendChild(messageEl);

      // Create the tag: <stacktrace>(Stacktrace here)</stacktrace>
      Element stacktraceEl = doc.createElement("stacktrace");
      String stacktrace = Strings.nullToEmpty(testResult.getStacktrace());
      stacktraceEl.appendChild(doc.createTextNode(stacktrace));
      testResultEl.appendChild(stacktraceEl);
    }
  }

  @Override
  String getUsageIntro() {
    return "Specify build rules to test.";
  }
}
