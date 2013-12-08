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
import com.facebook.buck.java.DefaultJavaPackageFinder;
import com.facebook.buck.java.GenerateCodeCoverageReportStep;
import com.facebook.buck.java.InstrumentStep;
import com.facebook.buck.java.JUnitStep;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.java.JavaTestRule;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.parser.RawRulePredicate;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleSuccess;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.IndividualTestEvent;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.rules.TestRunEvent;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.Verbosity;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
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
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

public class TestCommand extends AbstractCommandRunner<TestCommandOptions> {

  public TestCommand(CommandRunnerParams params) {
    super(params);
  }

  @Override
  TestCommandOptions createOptions(BuckConfig buckConfig) {
    return new TestCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(final TestCommandOptions options) throws IOException {
    // If the user asked to run all of the tests, use a special method for that that is optimized to
    // parse all of the build files and traverse the dependency graph to find all of the tests to
    // run.
    if (options.isRunAllTests()) {
      try {
        return runAllTests(options);
      } catch (BuildTargetException | BuildFileParseException e) {
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

    Iterable<TestRule> results = getCandidateRulesByIncludedLabels(
        build.getDependencyGraph(), options.getIncludedLabels());

    results = filterTestRules(options, results);

    BuildContext buildContext = build.getBuildContext();
    ExecutionContext buildExecutionContext = build.getExecutionContext();
    ExecutionContext testExecutionContext = ExecutionContext.builder().
        setExecutionContext(buildExecutionContext).
        setTargetDevice(options.getTargetDeviceOptional()).
        build();

    return runTestsAndShutdownExecutor(results,
        buildContext,
        testExecutionContext,
        options);
  }

  /**
   * Returns the ShellCommand object that is supposed to instrument the class files that the list
   * of tests is supposed to be testing. From TestRule objects, we derive the class file folders
   * and generate a EMMA instr shell command object, which can run in a CommandRunner.
   */
  private Step getInstrumentCommand(
      ImmutableSet<JavaLibraryRule> rulesUnderTest, ProjectFilesystem projectFilesystem) {
    ImmutableSet.Builder<Path> pathsToInstrumentedClasses = ImmutableSet.builder();

    // Add all JAR files produced by java libraries that we are testing to -instrpath.
    for (JavaLibraryRule path : rulesUnderTest) {
      String pathToOutput = path.getPathToOutputFile();
      if (pathToOutput == null) {
        continue;
      }
      pathsToInstrumentedClasses.add(projectFilesystem.getPathRelativizer().apply(pathToOutput));
    }

    // Run EMMA instrumentation. This will instrument the classes we generated in the build command.
    // TODO(user): Output instrumented class files in different folder and change junit classdir.
    return new InstrumentStep("overwrite", pathsToInstrumentedClasses.build());
  }

  /**
   * Returns the ShellCommand object that is supposed to generate a code coverage report from data
   * obtained during the test run. This method will also generate a set of source paths to the class
   * files tested during the test run.
   */
  private Step getReportCommand(
      ImmutableSet<JavaLibraryRule> rulesUnderTest,
      Optional<DefaultJavaPackageFinder> defaultJavaPackageFinderOptional,
      ProjectFilesystem projectFilesystem,
      String outputDirectory) {
    ImmutableSet.Builder<String> srcDirectories = ImmutableSet.builder();
    ImmutableSet.Builder<Path> pathsToClasses = ImmutableSet.builder();

    // Add all source directories of java libraries that we are testing to -sourcepath.
    for (JavaLibraryRule rule : rulesUnderTest) {
      ImmutableSet<String> sourceFolderPath =
          getPathToSourceFolders(rule, defaultJavaPackageFinderOptional, projectFilesystem);
      if (!sourceFolderPath.isEmpty()) {
        srcDirectories.addAll(sourceFolderPath);
      }
      String pathToOutput = rule.getPathToOutputFile();
      if (pathToOutput == null) {
        continue;
      }
      pathsToClasses.add(Paths.get(pathToOutput));
    }

    return new GenerateCodeCoverageReportStep(srcDirectories.build(),
        pathsToClasses.build(),
        outputDirectory);
  }

  /**
   * Returns a set of source folders of the java files of a library.
   */
  @VisibleForTesting
  static ImmutableSet<String> getPathToSourceFolders(
      JavaLibraryRule rule,
      Optional<DefaultJavaPackageFinder> defaultJavaPackageFinderOptional,
      ProjectFilesystem projectFilesystem) {
    ImmutableSet<String> javaSrcPaths = rule.getJavaSrcs();

    // A Java library rule with just resource files has an empty javaSrcPaths.
    if (javaSrcPaths.isEmpty()) {
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
    for (String javaSrcPath : javaSrcPaths) {
      if (!JavaTestRule.isGeneratedFile(javaSrcPath)) {

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
        File directory = projectFilesystem.getFileForRelativePath(javaSrcPath).getParentFile();
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
      BuildTargetException, BuildFileParseException {
    Logging.setLoggingLevelForVerbosity(console.getVerbosity());

    // Create artifact cache to initialize Cassandra connection, if appropriate.
    ArtifactCache artifactCache = getArtifactCache();

    // The first step is to parse all of the build files. This will populate the parser and find all
    // of the test rules.
    RawRulePredicate predicate = new RawRulePredicate() {
      @Override
      public boolean isMatch(
          Map<String, Object> rawParseData,
          BuildRuleType buildRuleType,
          BuildTarget buildTarget) {
        return buildRuleType.isTestRule();
      }
    };
    PartialGraph partialGraph = PartialGraph.createPartialGraph(predicate,
        getProjectFilesystem(),
        options.getDefaultIncludes(),
        getParser(),
        getBuckEventBus());

    final DependencyGraph graph = partialGraph.getDependencyGraph();

    // Look up all of the test rules in the dependency graph.
    Iterable<TestRule> testRules = Iterables.transform(partialGraph.getTargets(),
        new Function<BuildTarget, TestRule>() {
      @Override public TestRule apply(BuildTarget buildTarget) {
        return (TestRule)graph.findBuildRuleByTarget(buildTarget);
      }
    });

    testRules = filterTestRules(options, testRules);

    // Build all of the test rules.
    Build build = options.createBuild(options.getBuckConfig(),
        graph,
        getProjectFilesystem(),
        artifactCache,
        console,
        getBuckEventBus(),
        options.getTargetDeviceOptional(),
        getCommandRunnerParams().getPlatform());
    int exitCode = BuildCommand.executeBuildAndPrintAnyFailuresToConsole(build, console);
    if (exitCode != 0) {
      return exitCode;
    }

    // Once all of the rules are built, then run the tests.
    return runTestsAndShutdownExecutor(testRules,
        build.getBuildContext(),
        build.getExecutionContext(),
        options);
  }

  @VisibleForTesting
  static Iterable<TestRule> getCandidateRulesByIncludedLabels(
      DependencyGraph graph, final ImmutableSet<String> includedLabels) {
    AbstractBottomUpTraversal<BuildRule, List<TestRule>> traversal =
        new AbstractBottomUpTraversal<BuildRule, List<TestRule>>(graph) {

      private final List<TestRule> results = Lists.newArrayList();

      @Override
      public void visit(BuildRule buildRule) {
        if (buildRule instanceof TestRule) {
          TestRule testRule = (TestRule)buildRule;
          // If includedSet not empty, only select test rules that contain included label.
          if (includedLabels.isEmpty() ||
              !Sets.intersection(testRule.getLabels(), includedLabels).isEmpty()) {
            results.add(testRule);
          }
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
    ImmutableSortedSet.Builder<TestRule> builder = ImmutableSortedSet.naturalOrder();

    // We always want to run the rules that are given on the command line. Always.
    List<String> allTargets = options.getArgumentsFormattedAsBuildTargets();
    for (TestRule rule : testRules) {
      if (allTargets.contains(rule.getFullyQualifiedName())) {
        builder.add(rule);
      }
    }

    // Filter out all test rules that contain labels we've excluded.
    builder.addAll(Iterables.filter(testRules, new Predicate<TestRule>() {
      @Override
      public boolean apply(TestRule rule) {
        return Sets.intersection(rule.getLabels(), options.getExcludedLabels()).isEmpty();
      }
    }));

    return builder.build();
  }

  private int runTestsAndShutdownExecutor(
      Iterable<TestRule> tests,
      BuildContext buildContext,
      ExecutionContext executionContext,
      TestCommandOptions options) throws IOException {
    StepRunner stepRunner = new DefaultStepRunner(executionContext,
        options.createListeningExecutorService());
    try {
      return runTests(tests, buildContext, executionContext, stepRunner, options);
    } finally {
      // Note: we need to use shutdown() instead of shutdownNow() to ensure that tasks submitted to
      // the Execution Service are completed.
      stepRunner.getListeningExecutorService().shutdown();
    }
  }

  private int runTests(
      Iterable<TestRule> tests,
      BuildContext buildContext,
      ExecutionContext executionContext,
      StepRunner stepRunner,
      final TestCommandOptions options) throws IOException {
    ImmutableSet<JavaLibraryRule> rulesUnderTest;
    // If needed, we first run instrumentation on the class files.
    if (options.isCodeCoverageEnabled()) {
      rulesUnderTest = getRulesUnderTest(tests);
      if (!rulesUnderTest.isEmpty()) {
        try {
          if (options.isJacocoEnabled()) {
            stepRunner.runStep(
                new MakeCleanDirectoryStep(JUnitStep.JACOCO_OUTPUT_DIR));
          } else {
            stepRunner.runStep(
                new MakeCleanDirectoryStep(JUnitStep.EMMA_OUTPUT_DIR));
            stepRunner.runStep(
                getInstrumentCommand(
                    rulesUnderTest,
                    executionContext.getProjectFilesystem()));
          }
        } catch (StepFailedException e) {
          console.printBuildFailureWithoutStacktrace(e);
          return 1;
        }
      }
    } else {
      rulesUnderTest = ImmutableSet.of();
    }

    getBuckEventBus().post(TestRunEvent.started(
        options.isRunAllTests(), options.getArgumentsFormattedAsBuildTargets()));

    // Start running all of the tests. The result of each java_test() rule is represented as a
    // ListenableFuture.
    List<ListenableFuture<TestResults>> results = Lists.newArrayList();

    // Unless `--verbose 0` is specified, print out test results as they become available.
    // Failures with the ListenableFuture should always be printed, as they indicate an error with
    // Buck, not the test being run.
    Verbosity verbosity = console.getVerbosity();
    final boolean printTestResults = (verbosity != Verbosity.SILENT);
    FutureCallback<TestResults> onTestFinishedCallback = new FutureCallback<TestResults>() {

      @Override
      public void onSuccess(TestResults testResults) {
        if (printTestResults) {
          getBuckEventBus().post(IndividualTestEvent.finished(
              options.getArgumentsFormattedAsBuildTargets(), testResults));
        }
      }

      @Override
      public void onFailure(Throwable throwable) {
        // This should never happen, but if it does, that means that something has gone awry, so
        // we should bubble it up.
        throwable.printStackTrace(getStdErr());
      }
    };

    TestRuleKeyFileHelper testRuleKeyFileHelper = new TestRuleKeyFileHelper(
        executionContext.getProjectFilesystem());
    for (TestRule test : tests) {
      List<Step> steps;

      // Determine whether the test needs to be executed.
      boolean isTestRunRequired =
          isTestRunRequiredForTest(test, executionContext, testRuleKeyFileHelper);
      if (isTestRunRequired) {
        getBuckEventBus().post(IndividualTestEvent.started(
            options.getArgumentsFormattedAsBuildTargets()));
        ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();
        List<Step> testSteps = test.runTests(buildContext, executionContext);
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
                  test.interpretTestResults(executionContext)),
              test.getBuildTarget());
      Futures.addCallback(testResults, onTestFinishedCallback);
      results.add(testResults);
    }

    // Block until all the tests have finished running.
    ListenableFuture<List<TestResults>> uberFuture = Futures.allAsList(results);
    List<TestResults> completedResults;
    try {
      completedResults = uberFuture.get();
    } catch (InterruptedException e) {
      e.printStackTrace(getStdErr());
      return 1;
    } catch (ExecutionException e) {
      e.printStackTrace(getStdErr());
      return 1;
    }

    getBuckEventBus().post(TestRunEvent.finished(
        options.getArgumentsFormattedAsBuildTargets(), completedResults));

    // Write out the results as XML, if requested.
    if (options.getPathToXmlTestOutput() != null) {
      try (Writer writer = Files.newWriter(
        new File(options.getPathToXmlTestOutput()),
        Charsets.UTF_8)) {
        writeXmlOutput(completedResults, writer);
      }
    }

    // Generate the code coverage report.
    if (options.isCodeCoverageEnabled() && !rulesUnderTest.isEmpty()) {
      try {
        Optional<DefaultJavaPackageFinder> defaultJavaPackageFinderOptional =
            options.getJavaPackageFinder();
        String outputDirectory;
        if (options.isJacocoEnabled()) {
          outputDirectory = JUnitStep.JACOCO_OUTPUT_DIR;
        } else {
          outputDirectory = JUnitStep.EMMA_OUTPUT_DIR;
        }
        stepRunner.runStep(
            getReportCommand(rulesUnderTest,
                defaultJavaPackageFinderOptional, getProjectFilesystem(), outputDirectory));
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

    return failures ? 1 : 0;
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
      ExecutionContext executionContext,
      TestRuleKeyFileHelper testRuleKeyFileHelper)
      throws IOException {
    boolean isTestRunRequired;
    BuildRuleSuccess.Type successType;
    if (executionContext.isDebugEnabled()) {
      // If debug is enabled, then we should always run the tests as the user is expecting to
      // hook up a debugger.
      isTestRunRequired = true;
    } else if (((successType = test.getBuildResultType()) != null)
               && successType == BuildRuleSuccess.Type.MATCHING_RULE_KEY
               && test.hasTestResultFiles(executionContext)
               && testRuleKeyFileHelper.isRuleKeyInDir(test)) {
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
   * Generates the set of rules under test.
   */
  private ImmutableSet<JavaLibraryRule> getRulesUnderTest(Iterable<TestRule> tests) {
    ImmutableSet.Builder<JavaLibraryRule> rulesUnderTest = ImmutableSet.builder();

    // Gathering all rules whose source will be under test.
    for (TestRule test : tests) {
      if (test instanceof JavaTestRule) {
        JavaTestRule javaTestRule = (JavaTestRule) test;
        rulesUnderTest.addAll(javaTestRule.getSourceUnderTest());
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
