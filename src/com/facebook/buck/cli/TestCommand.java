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
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.BuildFileSpec;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.TargetNodePredicateSpec;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.ExternalTestRunnerRule;
import com.facebook.buck.rules.ExternalTestRunnerTestSpec;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodes;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.AdbOptions;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.TargetDevice;
import com.facebook.buck.step.TargetDeviceOptions;
import com.facebook.buck.test.CoverageReportFormat;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ForwardingProcessListener;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

public class TestCommand extends BuildCommand {

  public static final String USE_RESULTS_CACHE = "use_results_cache";

  private static final Logger LOG = Logger.get(TestCommand.class);

  @Option(name = "--all",
          usage =
              "Whether all of the tests should be run. " +
              "If no targets are given, --all is implied")
  private boolean all = false;

  @Option(name = "--code-coverage", usage = "Whether code coverage information will be generated.")
  private boolean isCodeCoverageEnabled = false;

  @Option(name = "--code-coverage-format", usage = "Format to be used for coverage")
  private CoverageReportFormat coverageReportFormat = CoverageReportFormat.HTML;

  @Option(name = "--code-coverage-title", usage = "Title used for coverage")
  private String coverageReportTitle = "Code-Coverage Analysis";

  @Option(name = "--debug",
          usage = "Whether the test will start suspended with a JDWP debug port of 5005")
  private boolean isDebugEnabled = false;

  @Option(name = "--xml", usage = "Where to write test output as XML.")
  @Nullable
  private String pathToXmlTestOutput = null;

  @Option(name = "--run-with-java-agent",
      usage = "Whether the test will start a java profiling agent")
  @Nullable
  private String pathToJavaAgent = null;

  @Option(name = "--no-results-cache", usage = "Whether to use cached test results.")
  @Nullable
  private Boolean isResultsCacheDisabled = null;

  @Option(name = "--build-filtered", usage = "Whether to build filtered out tests.")
  @Nullable
  private Boolean isBuildFiltered = null;

  // TODO(#9061229): See if we can remove this option entirely. For now, the
  // underlying code has been removed, and this option is ignored.
  @Option(
      name = "--ignore-when-dependencies-fail",
      aliases = {"-i"},
      usage =
          "Deprecated option (ignored).",
      hidden = true)
  @SuppressWarnings("PMD.UnusedPrivateField")
  private boolean isIgnoreFailingDependencies;

  @Option(
      name = "--dry-run",
      usage = "Print tests that match the given command line options, but don't run them.")
  private boolean isDryRun;

  @Option(
      name = "--one-time-output",
      usage =
          "Put test-results in a unique, one-time output directory.  " +
          "This allows multiple tests to be run in parallel without interfering with each other, " +
          "but at the expense of being unable to use cached results.  " +
          "WARNING: this is experimental, and only works for Java tests!")
  private boolean isUsingOneTimeOutput;

  @Option(
      name = "--shuffle",
      usage =
          "Randomize the order in which test classes are executed." +
          "WARNING: only works for Java tests!")
  private boolean isShufflingTests;

  @Option(
      name = "--exclude-transitive-tests",
      usage =
          "Only run the tests targets that were specified on the command line (without adding " +
          "more tests by following dependencies).")
  private boolean shouldExcludeTransitiveTests;

  @AdditionalOptions
  @SuppressFieldNotInitialized
  private AdbCommandLineOptions adbOptions;

  @AdditionalOptions
  @SuppressFieldNotInitialized
  private TargetDeviceCommandLineOptions targetDeviceOptions;

  @AdditionalOptions
  @SuppressFieldNotInitialized
  private TestSelectorOptions testSelectorOptions;

  @AdditionalOptions
  @SuppressFieldNotInitialized
  private TestLabelOptions testLabelOptions;

  @Option(name = "--", handler = ConsumeAllOptionsHandler.class)
  private List<String> withDashArguments = Lists.newArrayList();

  public boolean isRunAllTests() {
    return all || getArguments().isEmpty();
  }

  @Override
  public boolean isCodeCoverageEnabled() {
    return isCodeCoverageEnabled;
  }

  public boolean isResultsCacheEnabled(BuckConfig buckConfig) {
    // The option is negative (--no-X) but we prefer to reason about positives, in the code.
    if (isResultsCacheDisabled == null) {
      boolean isUseResultsCache = buckConfig.getBooleanValue("test", USE_RESULTS_CACHE, true);
      isResultsCacheDisabled = !isUseResultsCache;
    }
    return !isResultsCacheDisabled;
  }

  @Override
  public boolean isDebugEnabled() {
    return isDebugEnabled;
  }

  public Optional<TargetDevice> getTargetDeviceOptional() {
    return targetDeviceOptions.getTargetDeviceOptional();
  }

  public AdbOptions getAdbOptions(BuckConfig buckConfig) {
    return adbOptions.getAdbOptions(buckConfig);
  }

  public TargetDeviceOptions getTargetDeviceOptions() {
    return targetDeviceOptions.getTargetDeviceOptions();
  }

  public boolean isDryRun() {
    return isDryRun;
  }

  public boolean isMatchedByLabelOptions(BuckConfig buckConfig, Set<Label> labels) {
    return testLabelOptions.isMatchedByLabelOptions(buckConfig, labels);
  }

  public boolean shouldExcludeTransitiveTests() {
    return shouldExcludeTransitiveTests;
  }

  public boolean shouldExcludeWin() {
    return testLabelOptions.shouldExcludeWin();
  }

  public boolean isBuildFiltered(BuckConfig buckConfig) {
    return isBuildFiltered != null ?
        isBuildFiltered :
        buckConfig.getBooleanValue("test", "build_filtered_tests", false);
  }

  public int getNumTestThreads(BuckConfig buckConfig) {
    if (isDebugEnabled()) {
      return 1;
    }
    return buckConfig.getNumThreads();
  }

  private TestRunningOptions getTestRunningOptions(CommandRunnerParams params) {
    return TestRunningOptions.builder()
        .setUsingOneTimeOutputDirectories(isUsingOneTimeOutput)
        .setCodeCoverageEnabled(isCodeCoverageEnabled)
        .setRunAllTests(isRunAllTests())
        .setTestSelectorList(testSelectorOptions.getTestSelectorList())
        .setShouldExplainTestSelectorList(testSelectorOptions.shouldExplain())
        .setResultsCacheEnabled(isResultsCacheEnabled(params.getBuckConfig()))
        .setDryRun(isDryRun)
        .setShufflingTests(isShufflingTests)
        .setPathToXmlTestOutput(Optional.fromNullable(pathToXmlTestOutput))
        .setPathToJavaAgent(Optional.fromNullable(pathToJavaAgent))
        .setCoverageReportFormat(coverageReportFormat)
        .setCoverageReportTitle(coverageReportTitle)
        .build();
  }

  private int runTestsInternal(
      CommandRunnerParams params,
      BuildEngine buildEngine,
      Build build,
      Iterable<TestRule> testRules)
      throws InterruptedException, IOException {

    if (!withDashArguments.isEmpty()) {
      params.getBuckEventBus().post(ConsoleEvent.severe(
          "Unexpected arguments after \"--\" when using internal runner"));
      return 1;
    }

    ConcurrencyLimit concurrencyLimit = new ConcurrencyLimit(
        getNumTestThreads(params.getBuckConfig()),
        params.getBuckConfig().getLoadLimit());
    try (
        CommandThreadManager testPool = new CommandThreadManager(
            "Test-Run",
            params.getBuckConfig().getWorkQueueExecutionOrder(),
            concurrencyLimit)) {
      return TestRunning.runTests(
          params,
          testRules,
          Preconditions.checkNotNull(build.getBuildContext()),
          build.getExecutionContext(),
          getTestRunningOptions(params),
          testPool.getExecutor(),
          buildEngine,
          new DefaultStepRunner(build.getExecutionContext()));
    } catch (ExecutionException e) {
      params.getBuckEventBus().post(ConsoleEvent.severe(
          MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return 1;
    }
  }

  private int runTestsExternal(
      final CommandRunnerParams params,
      Build build,
      Iterable<String> command,
      Iterable<TestRule> testRules)
      throws InterruptedException, IOException {
    TestRunningOptions options = getTestRunningOptions(params);

    // Walk the test rules, collecting all the specs.
    List<ExternalTestRunnerTestSpec> specs = Lists.newArrayList();
    for (TestRule testRule : testRules) {
      if (!(testRule instanceof ExternalTestRunnerRule)) {
        params.getBuckEventBus().post(ConsoleEvent.severe(String.format(
            "Test %s does not support external test running",
            testRule.getBuildTarget())));
        return 1;
      }
      ExternalTestRunnerRule rule = (ExternalTestRunnerRule) testRule;
      specs.add(rule.getExternalTestRunnerSpec(build.getExecutionContext(), options));
    }

    // Serialize the specs to a file to pass into the test runner.
    Path infoFile =
        params.getCell().getFilesystem()
            .resolve(BuckConstant.getScratchPath().resolve("external_runner_specs.json"));
    Files.createDirectories(infoFile.getParent());
    Files.deleteIfExists(infoFile);
    params.getObjectMapper().writerWithDefaultPrettyPrinter().writeValue(infoFile.toFile(), specs);

    // Launch and run the external test runner, forwarding it's stdout/stderr to the console.
    // We wait for it to complete then returns its error code.
    ListeningProcessExecutor processExecutor = new ListeningProcessExecutor();
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .addAllCommand(command)
            .addAllCommand(withDashArguments)
            .setEnvironment(params.getEnvironment())
            .addCommand("--buck-test-info", infoFile.toString())
            .setDirectory(params.getCell().getFilesystem().getRootPath().toFile())
            .build();
    ForwardingProcessListener processListener =
        new ForwardingProcessListener(
            Channels.newChannel(params.getConsole().getStdOut()),
            Channels.newChannel(params.getConsole().getStdErr()));
    ListeningProcessExecutor.LaunchedProcess process =
        processExecutor.launchProcess(processExecutorParams, processListener);
    try {
      return processExecutor.waitForProcess(process, Long.MAX_VALUE, TimeUnit.DAYS);
    } finally {
      processExecutor.destroyProcess(process, /* force */ false);
      processExecutor.waitForProcess(process, Long.MAX_VALUE, TimeUnit.DAYS);
    }
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    LOG.debug("Running with arguments %s", getArguments());

    try (CommandThreadManager pool = new CommandThreadManager(
        "Test",
        params.getBuckConfig().getWorkQueueExecutionOrder(),
        getConcurrencyLimit(params.getBuckConfig()))) {
      // Post the build started event, setting it to the Parser recorded start time if appropriate.
      BuildEvent.Started started = BuildEvent.started(getArguments());
      if (params.getParser().getParseStartTime().isPresent()) {
        params.getBuckEventBus().post(
            started,
            params.getParser().getParseStartTime().get());
      } else {
        params.getBuckEventBus().post(started);
      }

      // The first step is to parse all of the build files. This will populate the parser and find
      // all of the test rules.
      TargetGraph targetGraph;
      ImmutableSet<BuildTarget> explicitBuildTargets;

      try {

        // If the user asked to run all of the tests, parse all of the build files looking for any
        // test rules.
        boolean ignoreBuckAutodepsFiles = false;
        if (isRunAllTests()) {
          targetGraph = params.getParser().buildTargetGraphForTargetNodeSpecs(
              params.getBuckEventBus(),
              params.getCell(),
              getEnableParserProfiling(),
              pool.getExecutor(),
              ImmutableList.of(
                  TargetNodePredicateSpec.of(
                      new Predicate<TargetNode<?>>() {
                        @Override
                        public boolean apply(TargetNode<?> input) {
                          return input.getType().isTestRule();
                        }
                      },
                      BuildFileSpec.fromRecursivePath(Paths.get("")))),
              ignoreBuckAutodepsFiles,
              Parser.ApplyDefaultFlavorsMode.ENABLED).getTargetGraph();
          explicitBuildTargets = ImmutableSet.of();

          // Otherwise, the user specified specific test targets to build and run, so build a graph
          // around these.
        } else {
          LOG.debug("Parsing graph for arguments %s", getArguments());
          TargetGraphAndBuildTargets result = params.getParser()
              .buildTargetGraphForTargetNodeSpecs(
                  params.getBuckEventBus(),
                  params.getCell(),
                  getEnableParserProfiling(),
                  pool.getExecutor(),
                  parseArgumentsAsTargetNodeSpecs(
                      params.getBuckConfig(),
                      getArguments()),
                  ignoreBuckAutodepsFiles,
                  Parser.ApplyDefaultFlavorsMode.ENABLED);
          targetGraph = result.getTargetGraph();
          explicitBuildTargets = result.getBuildTargets();

          LOG.debug("Got explicit build targets %s", explicitBuildTargets);
          ImmutableSet.Builder<BuildTarget> testTargetsBuilder = ImmutableSet.builder();
          for (TargetNode<?> node : targetGraph.getAll(explicitBuildTargets)) {
            ImmutableSortedSet<BuildTarget> nodeTests = TargetNodes.getTestTargetsForNode(node);
            if (!nodeTests.isEmpty()) {
              LOG.debug("Got tests for target %s: %s", node.getBuildTarget(), nodeTests);
              testTargetsBuilder.addAll(nodeTests);
            }
          }
          ImmutableSet<BuildTarget> testTargets = testTargetsBuilder.build();
          if (!testTargets.isEmpty()) {
            LOG.debug("Got related test targets %s, building new target graph...", testTargets);
            targetGraph = params.getParser().buildTargetGraph(
                params.getBuckEventBus(),
                params.getCell(),
                getEnableParserProfiling(),
                pool.getExecutor(),
                Iterables.concat(
                    explicitBuildTargets,
                    testTargets));
            LOG.debug("Finished building new target graph with tests.");
          }
        }

      } catch (BuildTargetException | BuildFileParseException e) {
        params.getBuckEventBus().post(ConsoleEvent.severe(
            MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
        return 1;
      }

      ActionGraphAndResolver actionGraphAndResolver = Preconditions.checkNotNull(
          params.getActionGraphCache().getActionGraph(
              params.getBuckEventBus(),
              BuildIdSampler.apply(
                  params.getBuckConfig().getActionGraphCacheCheckSampleRate(),
                  params.getBuckEventBus().getBuildId()),
              targetGraph));
      // Look up all of the test rules in the action graph.
      Iterable<TestRule> testRules = Iterables.filter(
          actionGraphAndResolver.getActionGraph().getNodes(),
          TestRule.class);

      // Unless the user requests that we build filtered tests, filter them out here, before
      // the build.
      if (!isBuildFiltered(params.getBuckConfig())) {
        testRules = filterTestRules(params.getBuckConfig(), explicitBuildTargets, testRules);
      }

      if (isDryRun()) {
        printMatchingTestRules(params.getConsole(), testRules);
      }

      CachingBuildEngine cachingBuildEngine =
          new CachingBuildEngine(
              pool.getExecutor(),
              params.getFileHashCache(),
              getBuildEngineMode().or(params.getBuckConfig().getBuildEngineMode()),
              params.getBuckConfig().getDependencySchedulingOrder(),
              params.getBuckConfig().getBuildDepFiles(),
              params.getBuckConfig().getBuildMaxDepFileCacheEntries(),
              params.getBuckConfig().getBuildArtifactCacheSizeLimit(),
              params.getObjectMapper(),
              actionGraphAndResolver.getResolver());
      try (Build build = createBuild(
          params.getBuckConfig(),
          actionGraphAndResolver.getActionGraph(),
          actionGraphAndResolver.getResolver(),
          params.getAndroidPlatformTargetSupplier(),
          cachingBuildEngine,
          params.getArtifactCache(),
          params.getConsole(),
          params.getBuckEventBus(),
          getTargetDeviceOptional(),
          params.getPlatform(),
          params.getEnvironment(),
          params.getObjectMapper(),
          params.getClock(),
          Optional.of(getAdbOptions(params.getBuckConfig())),
          Optional.of(getTargetDeviceOptions()),
          params.getExecutors())) {

        // Build all of the test rules.
        int exitCode = build.executeAndPrintFailuresToEventBus(
            testRules,
            isKeepGoing(),
            params.getBuckEventBus(),
            params.getConsole(),
            getPathToBuildReport(params.getBuckConfig()));
        params.getBuckEventBus().post(BuildEvent.finished(started, exitCode));
        if (exitCode != 0) {
          return exitCode;
        }

        // If the user requests that we build tests that we filter out, then we perform
        // the filtering here, after we've done the build but before we run the tests.
        if (isBuildFiltered(params.getBuckConfig())) {
          testRules =
              filterTestRules(params.getBuckConfig(), explicitBuildTargets, testRules);
        }

        // Once all of the rules are built, then run the tests.
        Optional<ImmutableList<String>> externalTestRunner =
            params.getBuckConfig().getExternalTestRunner();
        if (externalTestRunner.isPresent()) {
          return runTestsExternal(
              params,
              build,
              externalTestRunner.get(),
              testRules);
        }
        return runTestsInternal(params, cachingBuildEngine, build, testRules);
      }
    }
  }

  @Override
  public boolean isReadOnly() {
    return false;
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
  Iterable<TestRule> filterTestRules(
      BuckConfig buckConfig,
      ImmutableSet<BuildTarget> explicitBuildTargets,
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

    for (TestRule rule : testRules) {
      boolean explicitArgument = explicitBuildTargets.contains(rule.getBuildTarget());
      boolean matchesLabel = isMatchedByLabelOptions(buckConfig, rule.getLabels());

      // We always want to run the rules that are given on the command line. Always. Unless we don't
      // want to.
      if (shouldExcludeWin() && !matchesLabel) {
        continue;
      }

      // The testRules Iterable contains transitive deps of the arguments given on the command line,
      // filter those out if such is the user's will.
      if (shouldExcludeTransitiveTests() && !explicitArgument) {
        continue;
      }

      // Normal behavior is to include all rules that match the given label as well as any that
      // were explicitly specified by the user.
      if (explicitArgument || matchesLabel) {
        builder.add(rule);
      }
    }

    return builder.build();
  }

  @Override
  public String getShortDescription() {
    return "builds and runs the tests for the specified target";
  }

}
