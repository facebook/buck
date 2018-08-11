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

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.engine.BuildEngine;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.test.event.IndividualTestEvent;
import com.facebook.buck.core.test.event.TestRunEvent;
import com.facebook.buck.core.test.event.TestStatusMessageEvent;
import com.facebook.buck.core.test.event.TestSummaryEvent;
import com.facebook.buck.core.test.rule.TestRule;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.CompilerOutputPaths;
import com.facebook.buck.jvm.java.DefaultJavaPackageFinder;
import com.facebook.buck.jvm.java.GenerateCodeCoverageReportStep;
import com.facebook.buck.jvm.java.JacocoConstants;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibraryWithTests;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.log.Logger;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.CoverageReportFormat;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRuleEvent;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.TestStatusMessage;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.util.Threads;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.facebook.buck.util.types.Either;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Utility class for running tests from {@link TestRule}s which have been built. */
public class TestRunning {

  private static final Logger LOG = Logger.get(TestRunning.class);

  // Utility class; do not instantiate.
  private TestRunning() {}

  @SuppressWarnings("PMD.EmptyCatchBlock")
  public static int runTests(
      CommandRunnerParams params,
      Iterable<TestRule> tests,
      ExecutionContext executionContext,
      TestRunningOptions options,
      ListeningExecutorService service,
      BuildEngine buildEngine,
      StepRunner stepRunner,
      BuildContext buildContext,
      SourcePathRuleFinder ruleFinder)
      throws IOException, InterruptedException {

    ImmutableSet<JavaLibrary> rulesUnderTestForCoverage;
    // If needed, we first run instrumentation on the class files.
    if (options.isCodeCoverageEnabled()) {
      rulesUnderTestForCoverage = getRulesUnderTest(tests);
      if (!rulesUnderTestForCoverage.isEmpty()) {
        try {
          // We'll use the filesystem of the first rule under test. This will fail if there are any
          // tests from a different repo, but it'll help us bootstrap ourselves to being able to
          // support multiple repos
          // TODO(t8220837): Support tests in multiple repos
          JavaLibrary library = rulesUnderTestForCoverage.iterator().next();
          for (Step step :
              MakeCleanDirectoryStep.of(
                  BuildCellRelativePath.fromCellRelativePath(
                      buildContext.getBuildCellRootPath(),
                      library.getProjectFilesystem(),
                      JacocoConstants.getJacocoOutputDir(library.getProjectFilesystem())))) {
            stepRunner.runStepForBuildTarget(executionContext, step, Optional.empty());
          }
        } catch (StepFailedException e) {
          params
              .getBuckEventBus()
              .post(ConsoleEvent.severe(Throwables.getRootCause(e).getLocalizedMessage()));
          return 1;
        }
      }
    } else {
      rulesUnderTestForCoverage = ImmutableSet.of();
    }

    ImmutableSet<String> testTargets =
        FluentIterable.from(tests)
            .transform(BuildRule::getBuildTarget)
            .transform(Object::toString)
            .toSet();

    int totalNumberOfTests = Iterables.size(tests);

    params
        .getBuckEventBus()
        .post(
            TestRunEvent.started(
                options.isRunAllTests(),
                options.getTestSelectorList(),
                options.shouldExplainTestSelectorList(),
                testTargets));

    // Start running all of the tests. The result of each java_test() rule is represented as a
    // ListenableFuture.
    List<ListenableFuture<TestResults>> results = new ArrayList<>();

    AtomicInteger lastReportedTestSequenceNumber = new AtomicInteger();
    List<TestRun> separateTestRuns = new ArrayList<>();
    List<TestRun> parallelTestRuns = new ArrayList<>();
    for (TestRule test : tests) {
      // Determine whether the test needs to be executed.
      Callable<TestResults> resultsInterpreter =
          getCachingCallable(
              test.interpretTestResults(
                  executionContext,
                  buildContext.getSourcePathResolver(),
                  /*isUsingTestSelectors*/ !options.getTestSelectorList().isEmpty()));

      Map<String, UUID> testUUIDMap = new HashMap<>();
      AtomicReference<TestStatusMessageEvent.Started> currentTestStatusMessageEvent =
          new AtomicReference<>();
      TestRule.TestReportingCallback testReportingCallback =
          new TestRule.TestReportingCallback() {
            @Override
            public void testsDidBegin() {
              LOG.debug("Tests for rule %s began", test.getBuildTarget());
            }

            @Override
            public void statusDidBegin(TestStatusMessage didBeginMessage) {
              LOG.debug("Test status did begin: %s", didBeginMessage);
              TestStatusMessageEvent.Started startedEvent =
                  TestStatusMessageEvent.started(didBeginMessage);
              TestStatusMessageEvent.Started previousEvent =
                  currentTestStatusMessageEvent.getAndSet(startedEvent);
              Preconditions.checkState(
                  previousEvent == null,
                  "Received begin status before end status (%s)",
                  previousEvent);
              params.getBuckEventBus().post(startedEvent);

              String message = didBeginMessage.getMessage();
              if (message.toLowerCase().contains("debugger")) {
                executionContext
                    .getStdErr()
                    .println(executionContext.getAnsi().asWarningText(message));
              }
            }

            @Override
            public void statusDidEnd(TestStatusMessage didEndMessage) {
              LOG.debug("Test status did end: %s", didEndMessage);
              TestStatusMessageEvent.Started previousEvent =
                  currentTestStatusMessageEvent.getAndSet(null);
              Preconditions.checkState(
                  previousEvent != null,
                  "Received end status before begin status (%s)",
                  previousEvent);
              params
                  .getBuckEventBus()
                  .post(TestStatusMessageEvent.finished(previousEvent, didEndMessage));
            }

            @Override
            public void testDidBegin(String testCaseName, String testName) {
              LOG.debug(
                  "Test rule %s test case %s test name %s began",
                  test.getBuildTarget(), testCaseName, testName);
              UUID testUUID = UUID.randomUUID();
              // UUID is immutable and thread-safe as of Java 7, so it's
              // safe to stash in a map and use later:
              //
              // http://bugs.java.com/view_bug.do?bug_id=6611830
              testUUIDMap.put(testCaseName + ":" + testName, testUUID);
              params
                  .getBuckEventBus()
                  .post(TestSummaryEvent.started(testUUID, testCaseName, testName));
            }

            @Override
            public void testDidEnd(TestResultSummary testResultSummary) {
              LOG.debug("Test rule %s test did end: %s", test.getBuildTarget(), testResultSummary);
              UUID testUUID =
                  testUUIDMap.get(
                      testResultSummary.getTestCaseName() + ":" + testResultSummary.getTestName());
              Preconditions.checkNotNull(testUUID);
              params.getBuckEventBus().post(TestSummaryEvent.finished(testUUID, testResultSummary));
            }

            @Override
            public void testsDidEnd(List<TestCaseSummary> testCaseSummaries) {
              LOG.debug("Test rule %s tests did end: %s", test.getBuildTarget(), testCaseSummaries);
            }
          };

      List<Step> steps;
      params.getBuckEventBus().post(IndividualTestEvent.started(testTargets));
      ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();
      Preconditions.checkState(buildEngine.isRuleBuilt(test.getBuildTarget()));
      List<Step> testSteps =
          test.runTests(executionContext, options, buildContext, testReportingCallback);
      if (!testSteps.isEmpty()) {
        stepsBuilder.addAll(testSteps);
      }
      steps = stepsBuilder.build();

      TestRun testRun = TestRun.of(test, steps, resultsInterpreter, testReportingCallback);

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
          runStepsAndYieldResult(
              stepRunner,
              executionContext,
              testRun.getSteps(),
              testRun.getTestResultsCallable(),
              testRun.getTest().getBuildTarget(),
              params.getBuckEventBus(),
              service);
      results.add(
          transformTestResults(
              params,
              testResults,
              testRun.getTest(),
              testRun.getTestReportingCallback(),
              testTargets,
              lastReportedTestSequenceNumber,
              totalNumberOfTests));
    }

    ListenableFuture<List<TestResults>> parallelTestStepsFuture = Futures.allAsList(results);

    List<TestResults> completedResults = new ArrayList<>();

    ListeningExecutorService directExecutorService = MoreExecutors.newDirectExecutorService();
    ListenableFuture<Void> uberFuture =
        MoreFutures.addListenableCallback(
            parallelTestStepsFuture,
            new FutureCallback<List<TestResults>>() {
              @Override
              public void onSuccess(List<TestResults> parallelTestResults) {
                LOG.debug("Parallel tests completed, running separate tests...");
                completedResults.addAll(parallelTestResults);
                List<ListenableFuture<TestResults>> separateResultsList = new ArrayList<>();
                for (TestRun testRun : separateTestRuns) {
                  separateResultsList.add(
                      transformTestResults(
                          params,
                          runStepsAndYieldResult(
                              stepRunner,
                              executionContext,
                              testRun.getSteps(),
                              testRun.getTestResultsCallable(),
                              testRun.getTest().getBuildTarget(),
                              params.getBuckEventBus(),
                              directExecutorService),
                          testRun.getTest(),
                          testRun.getTestReportingCallback(),
                          testTargets,
                          lastReportedTestSequenceNumber,
                          totalNumberOfTests));
                }
                ListenableFuture<List<TestResults>> serialResults =
                    Futures.allAsList(separateResultsList);
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
                  Threads.interruptCurrentThread();
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
      Threads.interruptCurrentThread();
      throw e;
    }

    params.getBuckEventBus().post(TestRunEvent.finished(testTargets, completedResults));

    // Write out the results as XML, if requested.
    Optional<String> path = options.getPathToXmlTestOutput();
    if (path.isPresent()) {
      try (Writer writer = Files.newWriter(new File(path.get()), Charsets.UTF_8)) {
        writeXmlOutput(completedResults, writer);
      }
    }

    // Generate the code coverage report.
    if (options.isCodeCoverageEnabled() && !rulesUnderTestForCoverage.isEmpty()) {
      try {
        JavaBuckConfig javaBuckConfig = params.getBuckConfig().getView(JavaBuckConfig.class);
        DefaultJavaPackageFinder defaultJavaPackageFinder =
            javaBuckConfig.createDefaultJavaPackageFinder();
        stepRunner.runStepForBuildTarget(
            executionContext,
            getReportCommand(
                rulesUnderTestForCoverage,
                defaultJavaPackageFinder,
                javaBuckConfig.getDefaultJavaOptions().getJavaRuntimeLauncher(),
                params.getCell().getFilesystem(),
                buildContext.getSourcePathResolver(),
                ruleFinder,
                JacocoConstants.getJacocoOutputDir(params.getCell().getFilesystem()),
                options.getCoverageReportFormats(),
                options.getCoverageReportTitle(),
                javaBuckConfig.getDefaultJavacOptions().getSpoolMode()
                    == JavacOptions.SpoolMode.INTERMEDIATE_TO_DISK,
                options.getCoverageIncludes(),
                options.getCoverageExcludes()),
            Optional.empty());
      } catch (StepFailedException e) {
        params
            .getBuckEventBus()
            .post(ConsoleEvent.severe(Throwables.getRootCause(e).getLocalizedMessage()));
        return 1;
      }
    }

    boolean failures =
        Iterables.any(
            completedResults,
            results1 -> {
              LOG.debug("Checking result %s for failure", results1);
              return !results1.isSuccess();
            });

    // TODO(buck_team): convert to ExitCode
    return failures ? 32 : 0;
  }

  private static ListenableFuture<TestResults> transformTestResults(
      CommandRunnerParams params,
      ListenableFuture<TestResults> originalTestResults,
      TestRule testRule,
      TestRule.TestReportingCallback testReportingCallback,
      ImmutableSet<String> testTargets,
      AtomicInteger lastReportedTestSequenceNumber,
      int totalNumberOfTests) {

    SettableFuture<TestResults> transformedTestResults = SettableFuture.create();
    FutureCallback<TestResults> callback =
        new FutureCallback<TestResults>() {

          private TestResults postTestResults(TestResults testResults) {
            if (!testRule.supportsStreamingTests()) {
              // For test rules which don't support streaming tests, we'll
              // stream test summary events after interpreting the
              // results.
              LOG.debug("Simulating streaming test events for rule %s", testRule);
              testReportingCallback.testsDidBegin();
              for (TestCaseSummary testCaseSummary : testResults.getTestCases()) {
                for (TestResultSummary testResultSummary : testCaseSummary.getTestResults()) {
                  testReportingCallback.testDidBegin(
                      testResultSummary.getTestCaseName(), testResultSummary.getTestName());
                  testReportingCallback.testDidEnd(testResultSummary);
                }
              }
              testReportingCallback.testsDidEnd(testResults.getTestCases());
              LOG.debug("Done simulating streaming test events for rule %s", testRule);
            }
            TestResults transformedTestResults =
                TestResults.builder()
                    .from(testResults)
                    .setSequenceNumber(lastReportedTestSequenceNumber.incrementAndGet())
                    .setTotalNumberOfTests(totalNumberOfTests)
                    .build();
            params
                .getBuckEventBus()
                .post(IndividualTestEvent.finished(testTargets, transformedTestResults));
            return transformedTestResults;
          }

          @Override
          public void onSuccess(TestResults testResults) {
            LOG.debug("Transforming successful test results %s", testResults);
            postTestResults(testResults);
            transformedTestResults.set(testResults);
          }

          @Override
          public void onFailure(Throwable throwable) {
            LOG.warn(throwable, "Test command step failed, marking %s as failed", testRule);
            // If the test command steps themselves fail, report this as special test result.
            TestResults testResults =
                TestResults.of(
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
                                    Throwables.getStackTraceAsString(throwable),
                                    "",
                                    "")))),
                    testRule.getContacts(),
                    testRule
                        .getLabels()
                        .stream()
                        .map(Object::toString)
                        .collect(ImmutableSet.toImmutableSet()));
            TestResults newTestResults = postTestResults(testResults);
            transformedTestResults.set(newTestResults);
          }
        };
    Futures.addCallback(originalTestResults, callback);
    return transformedTestResults;
  }

  private static Callable<TestResults> getCachingCallable(Callable<TestResults> callable) {
    return new Callable<TestResults>() {
      @Nullable private Either<TestResults, Exception> result = null;

      @Override
      public synchronized TestResults call() throws Exception {
        if (result == null) {
          try {
            result = Either.ofLeft(callable.call());
          } catch (Exception t) {
            result = Either.ofRight(t);
          }
        }
        if (result.isRight()) {
          throw result.getRight();
        }
        return result.getLeft();
      }
    };
  }

  /** Generates the set of Java library rules under test. */
  private static ImmutableSet<JavaLibrary> getRulesUnderTest(Iterable<TestRule> tests) {
    ImmutableSet.Builder<JavaLibrary> rulesUnderTest = ImmutableSet.builder();

    // Gathering all rules whose source will be under test.
    for (TestRule test : tests) {
      if (test instanceof JavaTest) {
        // Look at the transitive dependencies for `tests` attribute that refers to this test.
        JavaTest javaTest = (JavaTest) test;

        ImmutableSet<JavaLibrary> transitiveDeps =
            javaTest.getCompiledTestsLibrary().getTransitiveClasspathDeps();
        for (JavaLibrary dep : transitiveDeps) {
          if (dep instanceof JavaLibraryWithTests) {
            ImmutableSortedSet<BuildTarget> depTests = ((JavaLibraryWithTests) dep).getTests();
            if (depTests.contains(test.getBuildTarget())) {
              rulesUnderTest.add(dep);
            }
          }
        }
      }
    }

    return rulesUnderTest.build();
  }

  /**
   * Writes the test results in XML format to the supplied writer.
   *
   * <p>This method does NOT close the writer object.
   *
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
          testEl.setAttribute("target", results.getBuildTarget().getFullyQualifiedName());
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
   * <p>This includes a test name, time (in ms), message, and stack trace, when present. Example:
   *
   * <pre>
   * &lt;testresult name="failed_test" time="200">
   *   &lt;message>Reason for test failure&lt;/message>
   *   &lt;stacktrace>Stacktrace here&lt;/stacktrace>
   * &lt;/testresult>
   * </pre>
   *
   * @param testCase The test case summary containing one or more tests.
   * @param testEl The XML element object for the <test> tag, in which extra information tags will
   *     be added.
   */
  @VisibleForTesting
  static void addExtraXmlInfo(TestCaseSummary testCase, Element testEl) {
    Document doc = testEl.getOwnerDocument();
    // Loop through the test case and extract test data.
    for (TestResultSummary testResult : testCase.getTestResults()) {
      // Extract the test name and time.
      String name = Strings.nullToEmpty(testResult.getTestName());
      String time = Long.toString(testResult.getTime());
      String status = testResult.isSuccess() ? "PASS" : "FAIL";

      // Create the tag: <testresult name="..." time="...">
      Element testResultEl = doc.createElement("testresult");
      testResultEl.setAttribute("name", name);
      testResultEl.setAttribute("time", time);
      testResultEl.setAttribute("status", status);
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
      DefaultJavaPackageFinder defaultJavaPackageFinder,
      Tool javaRuntimeLauncher,
      ProjectFilesystem filesystem,
      SourcePathResolver sourcePathResolver,
      SourcePathRuleFinder ruleFinder,
      Path outputDirectory,
      Set<CoverageReportFormat> formats,
      String title,
      boolean useIntermediateClassesDir,
      Optional<String> coverageIncludes,
      Optional<String> coverageExcludes) {
    ImmutableSet.Builder<String> srcDirectories = ImmutableSet.builder();
    ImmutableSet.Builder<Path> pathsToJars = ImmutableSet.builder();

    // Add all source directories of java libraries that we are testing to -sourcepath.
    for (JavaLibrary rule : rulesUnderTest) {
      ImmutableSet<String> sourceFolderPath =
          getPathToSourceFolders(rule, sourcePathResolver, ruleFinder, defaultJavaPackageFinder);
      if (!sourceFolderPath.isEmpty()) {
        srcDirectories.addAll(sourceFolderPath);
      }
      Path classesItem = null;

      if (useIntermediateClassesDir) {
        classesItem = CompilerOutputPaths.getClassesDir(rule.getBuildTarget(), filesystem);
      } else {
        SourcePath path = rule.getSourcePathToOutput();
        if (path != null) {
          classesItem = sourcePathResolver.getRelativePath(path);
        }
      }
      if (classesItem == null) {
        continue;
      }
      pathsToJars.add(classesItem);
    }

    return new GenerateCodeCoverageReportStep(
        javaRuntimeLauncher.getCommandPrefix(sourcePathResolver),
        filesystem,
        srcDirectories.build(),
        pathsToJars.build(),
        outputDirectory,
        formats,
        title,
        coverageIncludes,
        coverageExcludes);
  }

  /** Returns a set of source folders of the java files of a library. */
  @VisibleForTesting
  static ImmutableSet<String> getPathToSourceFolders(
      JavaLibrary rule,
      SourcePathResolver sourcePathResolver,
      SourcePathRuleFinder ruleFinder,
      DefaultJavaPackageFinder defaultJavaPackageFinder) {
    ImmutableSet<SourcePath> javaSrcs = rule.getJavaSrcs();

    // A Java library rule with just resource files has an empty javaSrcs.
    if (javaSrcs.isEmpty()) {
      return ImmutableSet.of();
    }

    // Iterate through all source paths to make sure we are generating a complete set of source
    // folders for the source paths.
    Set<String> srcFolders = new HashSet<>();
    loopThroughSourcePath:
    for (SourcePath javaSrcPath : javaSrcs) {
      if (ruleFinder.getRule(javaSrcPath).isPresent()) {
        continue;
      }

      Path javaSrcRelativePath = sourcePathResolver.getRelativePath(javaSrcPath);

      // If the source path is already under a known source folder, then we can skip this
      // source path.
      for (String srcFolder : srcFolders) {
        if (javaSrcRelativePath.startsWith(srcFolder)) {
          continue loopThroughSourcePath;
        }
      }

      // If the source path is under one of the source roots, then we can just add the source
      // root.
      ImmutableSortedSet<String> pathsFromRoot = defaultJavaPackageFinder.getPathsFromRoot();
      for (String root : pathsFromRoot) {
        if (javaSrcRelativePath.startsWith(root)) {
          srcFolders.add(root);
          continue loopThroughSourcePath;
        }
      }

      // Traverse the file system from the parent directory of the java file until we hit the
      // parent of the src root directory.
      ImmutableSet<String> pathElements = defaultJavaPackageFinder.getPathElements();
      Path directory = sourcePathResolver.getAbsolutePath(javaSrcPath).getParent();
      if (pathElements.isEmpty()) {
        continue;
      }

      while (directory != null
          && directory.getFileName() != null
          && !pathElements.contains(directory.getFileName().toString())) {
        directory = directory.getParent();
      }

      if (directory == null || directory.getFileName() == null) {
        continue;
      }

      String directoryPath = directory.toString();
      if (!directoryPath.endsWith("/")) {
        directoryPath += "/";
      }
      srcFolders.add(directoryPath);
    }

    return ImmutableSet.copyOf(srcFolders);
  }

  private static ListenableFuture<TestResults> runStepsAndYieldResult(
      StepRunner stepRunner,
      ExecutionContext context,
      List<Step> steps,
      Callable<TestResults> interpretResults,
      BuildTarget buildTarget,
      BuckEventBus eventBus,
      ListeningExecutorService listeningExecutorService) {
    Preconditions.checkState(!listeningExecutorService.isShutdown());
    Callable<TestResults> callable =
        () -> {
          LOG.debug("Test steps will run for %s", buildTarget);
          eventBus.post(TestRuleEvent.started(buildTarget));
          for (Step step : steps) {
            stepRunner.runStepForBuildTarget(context, step, Optional.of(buildTarget));
          }
          LOG.debug("Test steps did run for %s", buildTarget);
          eventBus.post(TestRuleEvent.finished(buildTarget));

          return interpretResults.call();
        };

    return listeningExecutorService.submit(callable);
  }
}
