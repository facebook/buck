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

package com.facebook.buck.cli;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.DefaultJavaPackageFinder;
import com.facebook.buck.java.GenerateCodeCoverageReportStep;
import com.facebook.buck.java.JUnitStep;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.JavaTest;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.rules.BuildResult;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.rules.IndividualTestEvent;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.rules.TestRunEvent;
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
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Verbosity;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Functions;
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
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * Utility class for running tests from {@link TestRule}s which have been built.
 */
public class TestRunning {

  public static final int TEST_FAILURES_EXIT_CODE = 42;

  private static final Logger LOG = Logger.get(TestRunning.class);

  // Utility class; do not instantiate.
  private TestRunning() { }

  @SuppressWarnings("PMD.EmptyCatchBlock")
  public static int runTests(
      final CommandRunnerParams params,
      Iterable<TestRule> tests,
      BuildContext buildContext,
      ExecutionContext executionContext,
      final TestRunningOptions options,
      ListeningExecutorService service,
      BuildEngine buildEngine,
      final StepRunner stepRunner)
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
          stepRunner.runStepForBuildTarget(
              new MakeCleanDirectoryStep(JUnitStep.JACOCO_OUTPUT_DIR),
              Optional.<BuildTarget>absent());
        } catch (StepFailedException e) {
          params.getConsole().printBuildFailureWithoutStacktrace(e);
          return 1;
        }
      }
    } else {
      rulesUnderTest = ImmutableSet.of();
    }

    final ImmutableSet<String> testTargets =
        FluentIterable.from(tests)
            .transform(HasBuildTarget.TO_TARGET)
            .transform(Functions.toStringFunction())
            .toSet();

    final int totalNumberOfTests = Iterables.size(tests);

    params.getBuckEventBus().post(
        TestRunEvent.started(
            options.isRunAllTests(),
            options.getTestSelectorList(),
            options.shouldExplainTestSelectorList(),
            testTargets));

    // Start running all of the tests. The result of each java_test() rule is represented as a
    // ListenableFuture.
    List<ListenableFuture<TestResults>> results = Lists.newArrayList();

    // Unless `--verbose 0` is specified, print out test results as they become available.
    // Failures with the ListenableFuture should always be printed, as they indicate an error with
    // Buck, not the test being run.
    Verbosity verbosity = params.getConsole().getVerbosity();
    final boolean printTestResults = (verbosity != Verbosity.SILENT);

    // For grouping results!
    final TestResultsGrouper grouper;
    if (options.isIgnoreFailingDependencies()) {
      grouper = new TestResultsGrouper(tests);
    } else {
      grouper = null;
    }

    TestRuleKeyFileHelper testRuleKeyFileHelper = new TestRuleKeyFileHelper(
        executionContext.getProjectFilesystem(),
        buildEngine);
    final AtomicInteger lastReportedTestSequenceNumber = new AtomicInteger();
    final List<TestRun> separateTestRuns = Lists.newArrayList();
    List<TestRun> parallelTestRuns = Lists.newArrayList();
    for (TestRule test : tests) {
      // Determine whether the test needs to be executed.
      boolean isTestRunRequired;
      isTestRunRequired = isTestRunRequiredForTest(
          test,
          buildEngine,
          executionContext,
          testRuleKeyFileHelper,
          options.isResultsCacheEnabled(),
          !options.getTestSelectorList().isEmpty());


      List<Step> steps;
      if (isTestRunRequired) {
        params.getBuckEventBus().post(IndividualTestEvent.started(testTargets));
        ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();
        Preconditions.checkState(buildEngine.isRuleBuilt(test.getBuildTarget()));
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

      TestRun testRun = TestRun.of(
          test,
          steps,
          getCachingStatusTransformingCallable(
              isTestRunRequired,
              test.interpretTestResults(
                  executionContext,
                  /*isUsingTestSelectors*/ !options.getTestSelectorList().isEmpty(),
                  /*isDryRun*/ options.isDryRun())));


      // Always run the commands, even if the list of commands as empty. There may be zero
      // commands because the rule is cached, but its results must still be processed.
      if (test.runTestSeparately()) {
        LOG.debug("Running test %s in serial", test);
        separateTestRuns.add(testRun);
      } else {
        LOG.debug("Running test %s in parallel", test);
        parallelTestRuns.add(testRun);
      }
    }

    for (TestRun testRun : parallelTestRuns) {
      ListenableFuture<TestResults> testResults =
          stepRunner.runStepsAndYieldResult(
              testRun.getSteps(),
              testRun.getTestResultsCallable(),
              Optional.of(testRun.getTest().getBuildTarget()),
              service);
        results.add(
            transformTestResults(
                params,
                testResults,
                grouper,
                testRun.getTest(),
                testTargets,
                printTestResults,
                lastReportedTestSequenceNumber,
                totalNumberOfTests));
    }


    ListenableFuture<List<TestResults>> parallelTestStepsFuture = Futures.allAsList(results);

    final List<TestResults> completedResults = Lists.newArrayList();

    final ListeningExecutorService directExecutorService = MoreExecutors.newDirectExecutorService();
    ListenableFuture<Void> uberFuture = stepRunner.addCallback(
        parallelTestStepsFuture,
        new FutureCallback<List<TestResults>>() {
          @Override
          public void onSuccess(List<TestResults> parallelTestResults) {
            LOG.debug("Parallel tests completed, running separate tests...");
            completedResults.addAll(parallelTestResults);
            List<ListenableFuture<TestResults>> separateResultsList = Lists.newArrayList();
            for (TestRun testRun : separateTestRuns) {
              separateResultsList.add(
                  transformTestResults(
                      params,
                      stepRunner.runStepsAndYieldResult(
                          testRun.getSteps(),
                          testRun.getTestResultsCallable(),
                          Optional.of(testRun.getTest().getBuildTarget()),
                          directExecutorService),
                      grouper,
                      testRun.getTest(),
                      testTargets,
                      printTestResults,
                      lastReportedTestSequenceNumber,
                      totalNumberOfTests));
            }
            ListenableFuture<List<TestResults>> serialResults = Futures.allAsList(
                separateResultsList);
            try {
              completedResults.addAll(serialResults.get());
            } catch (ExecutionException e) {
              LOG.error(e, "Error fetching serial test results");
              throw new HumanReadableException(e, "Error fetching serial test results");
            } catch (InterruptedException e) {
              LOG.error(e, "Interrupted fetching serial test results");
              try {
                serialResults.cancel(true);
              } catch (CancellationException ignored) {
                // Rethrow original InterruptedException instead.
              }
              Thread.currentThread().interrupt();
              throw new HumanReadableException(e, "Test cancelled");
            }
            LOG.debug("Done running serial tests.");
          }
          @Override
          public void onFailure(Throwable e) {
            LOG.error(e, "Parallel tests failed, not running serial tests");
            throw new HumanReadableException(e, "Parallel tests failed");
          }
        },
        directExecutorService);

    try {
      // Block until all the tests have finished running.
      uberFuture.get();
    } catch (ExecutionException e) {
      e.printStackTrace(params.getConsole().getStdErr());
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

    params.getBuckEventBus().post(TestRunEvent.finished(testTargets, completedResults));

    // Write out the results as XML, if requested.
    Optional<String> path = options.getPathToXmlTestOutput();
    if (path.isPresent()) {
      try (Writer writer = Files.newWriter(
        new File(path.get()),
        Charsets.UTF_8)) {
        writeXmlOutput(completedResults, writer);
      }
    }

    // Generate the code coverage report.
    if (options.isCodeCoverageEnabled() && !rulesUnderTest.isEmpty()) {
      try {
        Optional<DefaultJavaPackageFinder> defaultJavaPackageFinderOptional =
            Optional.fromNullable(params.getBuckConfig().createDefaultJavaPackageFinder());
        stepRunner.runStepForBuildTarget(
            getReportCommand(
                rulesUnderTest,
                defaultJavaPackageFinderOptional,
                params.getRepository().getFilesystem(),
                JUnitStep.JACOCO_OUTPUT_DIR,
                options.getCoverageReportFormat()),
            Optional.<BuildTarget>absent());
      } catch (StepFailedException e) {
        params.getConsole().printBuildFailureWithoutStacktrace(e);
        return 1;
      }
    }

    boolean failures = Iterables.any(completedResults, new Predicate<TestResults>() {
      @Override
      public boolean apply(TestResults results) {
        LOG.debug("Checking result %s for failure", results);
        return !results.isSuccess();
      }
    });

    return failures ? TEST_FAILURES_EXIT_CODE : 0;
  }

  private static ListenableFuture<TestResults> transformTestResults(
      final CommandRunnerParams params,
      ListenableFuture<TestResults> originalTestResults,
      @Nullable final TestResultsGrouper grouper,
      final TestRule testRule,
      final ImmutableSet<String> testTargets,
      final boolean printTestResults,
      final AtomicInteger lastReportedTestSequenceNumber,
      final int totalNumberOfTests) {

    final SettableFuture<TestResults> transformedTestResults = SettableFuture.create();
    FutureCallback<TestResults> callback = new FutureCallback<TestResults>() {

      private void postTestResults(TestResults testResults) {
        testResults.setSequenceNumber(lastReportedTestSequenceNumber.incrementAndGet());
        testResults.setTotalNumberOfTests(totalNumberOfTests);
        params.getBuckEventBus().post(
            IndividualTestEvent.finished(
                testTargets,
                testResults));
      }

      private String getStackTrace(Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sw.toString();
      }

      @Override
      public void onSuccess(TestResults testResults) {
        LOG.debug("Transforming successful test results %s", testResults);
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
        transformedTestResults.set(testResults);
      }

      @Override
      public void onFailure(Throwable throwable) {
        LOG.warn(throwable, "Test command step failed, marking %s as failed", testRule);
        // If the test command steps themselves fail, report this as special test result.
        TestResults testResults =
            new TestResults(
                testRule.getBuildTarget(),
                ImmutableList.of(
                    new TestCaseSummary(
                        testRule.getBuildTarget().toString(),
                        ImmutableList.of(
                            new TestResultSummary(
                                testRule.getBuildTarget().toString(),
                                "main",
                                ResultType.FAILURE,
                                0L,
                                throwable.getMessage(),
                                getStackTrace(throwable),
                                "",
                                "")))),
                testRule.getContacts(),
                FluentIterable.from(
                    testRule.getLabels()).transform(Functions.toStringFunction()).toSet());
        if (printTestResults) {
          postTestResults(testResults);
        }
        transformedTestResults.set(testResults);
      }
    };
    Futures.addCallback(originalTestResults, callback);
    return transformedTestResults;
  }

  private static Callable<TestResults> getCachingStatusTransformingCallable(
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
            originalTestResults.getContacts(),
            originalTestResults.getLabels());
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
    BuildResult result;
    if (executionContext.isDebugEnabled()) {
      // If debug is enabled, then we should always run the tests as the user is expecting to
      // hook up a debugger.
      isTestRunRequired = true;
    } else if (isRunningWithTestSelectors) {
      // As a feature to aid developers, we'll assume that when we are using test selectors,
      // we should always run each test (and never look at the cache.)
      // TODO(user) When #3090004 and #3436849 are closed we can respect the cache again.
      isTestRunRequired = true;
    } else if (((result = cachingBuildEngine.getBuildRuleResult(
        test.getBuildTarget())) != null) &&
            result.getSuccess() == BuildRuleSuccessType.MATCHING_RULE_KEY &&
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
  private static ImmutableSet<JavaLibrary> getRulesUnderTest(Iterable<TestRule> tests) {
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

  /**
   * Returns the ShellCommand object that is supposed to generate a code coverage report from data
   * obtained during the test run. This method will also generate a set of source paths to the class
   * files tested during the test run.
   */
  private static Step getReportCommand(
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
      Path pathToOutput = rule.getPathToOutput();
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
}
