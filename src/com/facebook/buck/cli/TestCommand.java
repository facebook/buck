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

import com.facebook.buck.android.exopackage.AndroidDevicesHelperFactory;
import com.facebook.buck.command.Build;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.resources.ResourcesConfig;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildFileSpec;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.TargetNodePredicateSpec;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.CachingBuildEngineBuckConfig;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.ExternalTestRunnerRule;
import com.facebook.buck.rules.ExternalTestRunnerTestSpec;
import com.facebook.buck.rules.LocalCachingBuildEngineDelegate;
import com.facebook.buck.rules.MetadataChecker;
import com.facebook.buck.rules.NoOpRemoteBuildRuleCompletionWaiter;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodes;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.rules.keys.RuleKeyCacheScope;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.step.AdbOptions;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TargetDevice;
import com.facebook.buck.step.TargetDeviceOptions;
import com.facebook.buck.test.CoverageReportFormat;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.external.ExternalTestRunEvent;
import com.facebook.buck.test.external.ExternalTestSpecCalculationEvent;
import com.facebook.buck.util.CloseableMemoizedSupplier;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.ForwardingProcessListener;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.versions.VersionException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.Messages;
import org.kohsuke.args4j.spi.OptionHandler;
import org.kohsuke.args4j.spi.Parameters;
import org.kohsuke.args4j.spi.Setter;

public class TestCommand extends BuildCommand {

  private static final Logger LOG = Logger.get(TestCommand.class);

  @Option(
    name = "--all",
    usage = "Whether all of the tests should be run. " + "If no targets are given, --all is implied"
  )
  private boolean all = false;

  @Option(name = "--code-coverage", usage = "Whether code coverage information will be generated.")
  private boolean isCodeCoverageEnabled = false;

  @Option(
    name = "--code-coverage-format",
    usage = "Comma separated Formats to be used for coverage",
    handler = CoverageReportFormatsHandler.class
  )
  private CoverageReportFormat[] coverageReportFormats =
      new CoverageReportFormat[] {CoverageReportFormat.HTML};

  @Option(name = "--code-coverage-title", usage = "Title used for coverage")
  private String coverageReportTitle = "Code-Coverage Analysis";

  @Option(
    name = "--debug",
    usage = "Whether the test will start suspended with a JDWP debug port of 5005"
  )
  private boolean isDebugEnabled = false;

  @Option(name = "--xml", usage = "Where to write test output as XML.")
  @Nullable
  private String pathToXmlTestOutput = null;

  @Option(
    name = "--run-with-java-agent",
    usage = "Whether the test will start a java profiling agent"
  )
  @Nullable
  private String pathToJavaAgent = null;

  @Option(name = "--build-filtered", usage = "Whether to build filtered out tests.")
  @Nullable
  private Boolean isBuildFiltered = null;

  // TODO(#9061229): See if we can remove this option entirely. For now, the
  // underlying code has been removed, and this option is ignored.
  @Option(
    name = "--ignore-when-dependencies-fail",
    aliases = {"-i"},
    usage = "Deprecated option (ignored).",
    hidden = true
  )
  @SuppressWarnings("PMD.UnusedPrivateField")
  private boolean isIgnoreFailingDependencies;

  @Option(
    name = "--shuffle",
    usage =
        "Randomize the order in which test classes are executed."
            + "WARNING: only works for Java tests!"
  )
  private boolean isShufflingTests;

  @Option(
    name = "--exclude-transitive-tests",
    usage =
        "Only run the tests targets that were specified on the command line (without adding "
            + "more tests by following dependencies)."
  )
  private boolean shouldExcludeTransitiveTests;

  @Option(
    name = "--test-runner-env",
    usage =
        "Add or override an environment variable passed to the test runner. Later occurrences "
            + "override earlier occurrences. Currently this only support Apple(ios/osx) tests.",
    handler = EnvironmentOverrideOptionHandler.class
  )
  private Map<String, String> environmentOverrides = new HashMap<>();

  @AdditionalOptions @SuppressFieldNotInitialized private AdbCommandLineOptions adbOptions;

  @AdditionalOptions @SuppressFieldNotInitialized
  private TargetDeviceCommandLineOptions targetDeviceOptions;

  @AdditionalOptions @SuppressFieldNotInitialized private TestSelectorOptions testSelectorOptions;

  @AdditionalOptions @SuppressFieldNotInitialized private TestLabelOptions testLabelOptions;

  @Option(
    name = "--",
    usage =
        "When an external test runner is specified to be used (in the .buckconfig file), "
            + "all options specified after -- get forwarded directly to the external test runner. "
            + "Available options after -- are specific to that particular test runner and you may "
            + "also want to consult its help pages.",
    handler = ConsumeAllOptionsHandler.class
  )
  private List<String> withDashArguments = new ArrayList<>();

  public boolean isRunAllTests() {
    return all || getArguments().isEmpty();
  }

  @Override
  public boolean isCodeCoverageEnabled() {
    return isCodeCoverageEnabled;
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

  public boolean isMatchedByLabelOptions(BuckConfig buckConfig, Set<String> labels) {
    return testLabelOptions.isMatchedByLabelOptions(buckConfig, labels);
  }

  public boolean shouldExcludeTransitiveTests() {
    return shouldExcludeTransitiveTests;
  }

  public boolean shouldExcludeWin() {
    return testLabelOptions.shouldExcludeWin();
  }

  public boolean isBuildFiltered(BuckConfig buckConfig) {
    return isBuildFiltered != null
        ? isBuildFiltered
        : buckConfig.getBooleanValue("test", "build_filtered_tests", false);
  }

  public int getNumTestThreads(BuckConfig buckConfig) {
    if (isDebugEnabled()) {
      return 1;
    }
    return buckConfig.getNumTestThreads();
  }

  public int getNumTestManagedThreads(ResourcesConfig resourcesConfig) {
    if (isDebugEnabled()) {
      return 1;
    }
    return resourcesConfig.getManagedThreadCount();
  }

  private TestRunningOptions getTestRunningOptions(CommandRunnerParams params) {
    // this.coverageReportFormats should never be empty, but doing this to avoid problems with
    // EnumSet.copyOf throwing Exception on empty parameter.
    EnumSet<CoverageReportFormat> coverageFormats = EnumSet.noneOf(CoverageReportFormat.class);
    coverageFormats.addAll(Arrays.asList(this.coverageReportFormats));

    TestRunningOptions.Builder builder =
        TestRunningOptions.builder()
            .setCodeCoverageEnabled(isCodeCoverageEnabled)
            .setRunAllTests(isRunAllTests())
            .setTestSelectorList(testSelectorOptions.getTestSelectorList())
            .setShouldExplainTestSelectorList(testSelectorOptions.shouldExplain())
            .setShufflingTests(isShufflingTests)
            .setPathToXmlTestOutput(Optional.ofNullable(pathToXmlTestOutput))
            .setPathToJavaAgent(Optional.ofNullable(pathToJavaAgent))
            .setCoverageReportFormats(coverageFormats)
            .setCoverageReportTitle(coverageReportTitle)
            .setEnvironmentOverrides(environmentOverrides);

    Optional<ImmutableList<String>> coverageIncludes =
        params.getBuckConfig().getOptionalListWithoutComments("test", "coverageIncludes", ',');
    Optional<ImmutableList<String>> coverageExcludes =
        params.getBuckConfig().getOptionalListWithoutComments("test", "coverageExcludes", ',');

    coverageIncludes.ifPresent(
        strings -> builder.setCoverageIncludes(strings.stream().collect(Collectors.joining(","))));
    coverageExcludes.ifPresent(
        strings -> builder.setCoverageExcludes(strings.stream().collect(Collectors.joining(","))));

    return builder.build();
  }

  private ConcurrencyLimit getTestConcurrencyLimit(CommandRunnerParams params) {
    ResourcesConfig resourcesConfig = params.getBuckConfig().getView(ResourcesConfig.class);
    return new ConcurrencyLimit(
        getNumTestThreads(params.getBuckConfig()),
        resourcesConfig.getResourceAllocationFairness(),
        getNumTestManagedThreads(resourcesConfig),
        resourcesConfig.getDefaultResourceAmounts(),
        resourcesConfig.getMaximumResourceAmounts());
  }

  private ExitCode runTestsInternal(
      CommandRunnerParams params,
      BuildEngine buildEngine,
      Build build,
      BuildContext buildContext,
      Iterable<TestRule> testRules)
      throws InterruptedException, IOException {

    if (!withDashArguments.isEmpty()) {
      throw new CommandLineException(
          "unexpected arguments after \"--\" when using internal runner");
    }

    try (CommandThreadManager testPool =
        new CommandThreadManager("Test-Run", getTestConcurrencyLimit(params))) {
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(build.getRuleResolver());
      int exitCodeInt =
          TestRunning.runTests(
              params,
              testRules,
              build.getExecutionContext(),
              getTestRunningOptions(params),
              testPool.getWeightedListeningExecutorService(),
              buildEngine,
              new DefaultStepRunner(),
              buildContext,
              ruleFinder);
      return ExitCode.map(exitCodeInt);
    }
  }

  private ExitCode runTestsExternal(
      final CommandRunnerParams params,
      Build build,
      Iterable<String> command,
      Iterable<TestRule> testRules,
      BuildContext buildContext)
      throws InterruptedException, IOException {
    Optional<TestRule> nonExternalTestRunnerRule =
        StreamSupport.stream(testRules.spliterator(), /* parallel */ true)
            .filter(rule -> !(rule instanceof ExternalTestRunnerRule))
            .findAny();
    if (nonExternalTestRunnerRule.isPresent()) {
      params
          .getBuckEventBus()
          .post(
              ConsoleEvent.severe(
                  String.format(
                      "Test %s does not support external test running",
                      nonExternalTestRunnerRule.get().getBuildTarget())));
      return ExitCode.BUILD_ERROR;
    }
    TestRunningOptions options = getTestRunningOptions(params);
    // Walk the test rules, collecting all the specs.
    ImmutableList<ExternalTestRunnerTestSpec> specs =
        StreamSupport.stream(
                testRules.spliterator(),
                params.getBuckConfig().isParallelExternalTestSpecComputationEnabled())
            .map(ExternalTestRunnerRule.class::cast)
            .map(
                rule -> {
                  try {
                    params
                        .getBuckEventBus()
                        .post(ExternalTestSpecCalculationEvent.started(rule.getBuildTarget()));
                    return rule.getExternalTestRunnerSpec(
                        build.getExecutionContext(), options, buildContext);
                  } finally {
                    params
                        .getBuckEventBus()
                        .post(ExternalTestSpecCalculationEvent.finished(rule.getBuildTarget()));
                  }
                })
            .collect(ImmutableList.toImmutableList());

    StreamSupport.stream(
            testRules.spliterator(),
            params.getBuckConfig().isParallelExternalTestSpecComputationEnabled())
        .map(ExternalTestRunnerRule.class::cast)
        .forEach(
            rule -> {
              try {
                rule.onPreTest(buildContext);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });

    // Serialize the specs to a file to pass into the test runner.
    Path infoFile =
        params
            .getCell()
            .getFilesystem()
            .resolve(params.getCell().getFilesystem().getBuckPaths().getScratchDir())
            .resolve("external_runner_specs.json");
    Files.createDirectories(infoFile.getParent());
    Files.deleteIfExists(infoFile);
    ObjectMappers.WRITER.withDefaultPrettyPrinter().writeValue(infoFile.toFile(), specs);

    // Launch and run the external test runner, forwarding it's stdout/stderr to the console.
    // We wait for it to complete then returns its error code.
    ListeningProcessExecutor processExecutor = new ListeningProcessExecutor();
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .addAllCommand(command)
            .addAllCommand(withDashArguments)
            .setEnvironment(params.getEnvironment())
            .addCommand("--buck-test-info", infoFile.toString())
            .addCommand("--jobs", String.valueOf(getTestConcurrencyLimit(params).threadLimit))
            .setDirectory(params.getCell().getFilesystem().getRootPath())
            .build();
    ForwardingProcessListener processListener =
        new ForwardingProcessListener(
            params.getConsole().getStdOut(), params.getConsole().getStdErr());
    final ImmutableSet<String> testTargets =
        StreamSupport.stream(testRules.spliterator(), /* parallel */ false)
            .map(BuildRule::getBuildTarget)
            .map(Object::toString)
            .collect(ImmutableSet.toImmutableSet());
    ListeningProcessExecutor.LaunchedProcess process =
        processExecutor.launchProcess(processExecutorParams, processListener);
    ExitCode exitCode = ExitCode.FATAL_GENERIC;
    try {
      params
          .getBuckEventBus()
          .post(
              ExternalTestRunEvent.started(
                  options.isRunAllTests(),
                  options.getTestSelectorList(),
                  options.shouldExplainTestSelectorList(),
                  testTargets));
      exitCode = ExitCode.map(processExecutor.waitForProcess(process));
      return exitCode;
    } finally {
      params.getBuckEventBus().post(ExternalTestRunEvent.finished(testTargets, exitCode));
      processExecutor.destroyProcess(process, /* force */ false);
      processExecutor.waitForProcess(process);
    }
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    LOG.debug("Running with arguments %s", getArguments());

    try (CommandThreadManager pool =
            new CommandThreadManager("Test", getConcurrencyLimit(params.getBuckConfig()));
        CloseableMemoizedSupplier<ForkJoinPool, RuntimeException> poolSupplier =
            getForkJoinPoolSupplier(params.getBuckConfig())) {
      // Post the build started event, setting it to the Parser recorded start time if appropriate.
      BuildEvent.Started started = BuildEvent.started(getArguments());
      if (params.getParser().getParseStartTime().isPresent()) {
        params.getBuckEventBus().post(started, params.getParser().getParseStartTime().get());
      } else {
        params.getBuckEventBus().post(started);
      }

      // The first step is to parse all of the build files. This will populate the parser and find
      // all of the test rules.
      TargetGraphAndBuildTargets targetGraphAndBuildTargets;
      ParserConfig parserConfig = params.getBuckConfig().getView(ParserConfig.class);

      try {

        // If the user asked to run all of the tests, parse all of the build files looking for any
        // test rules.
        if (isRunAllTests()) {
          targetGraphAndBuildTargets =
              params
                  .getParser()
                  .buildTargetGraphForTargetNodeSpecs(
                      params.getBuckEventBus(),
                      params.getCell(),
                      getEnableParserProfiling(),
                      pool.getListeningExecutorService(),
                      ImmutableList.of(
                          TargetNodePredicateSpec.of(
                                  BuildFileSpec.fromRecursivePath(
                                      Paths.get(""), params.getCell().getRoot()))
                              .withOnlyTests(true)),
                      parserConfig.getDefaultFlavorsMode());
          targetGraphAndBuildTargets =
              targetGraphAndBuildTargets.withBuildTargets(ImmutableSet.of());

          // Otherwise, the user specified specific test targets to build and run, so build a graph
          // around these.
        } else {
          LOG.debug("Parsing graph for arguments %s", getArguments());
          targetGraphAndBuildTargets =
              params
                  .getParser()
                  .buildTargetGraphForTargetNodeSpecs(
                      params.getBuckEventBus(),
                      params.getCell(),
                      getEnableParserProfiling(),
                      pool.getListeningExecutorService(),
                      parseArgumentsAsTargetNodeSpecs(params.getBuckConfig(), getArguments()),
                      parserConfig.getDefaultFlavorsMode());

          LOG.debug("Got explicit build targets %s", targetGraphAndBuildTargets.getBuildTargets());
          ImmutableSet.Builder<BuildTarget> testTargetsBuilder = ImmutableSet.builder();
          for (TargetNode<?, ?> node :
              targetGraphAndBuildTargets
                  .getTargetGraph()
                  .getAll(targetGraphAndBuildTargets.getBuildTargets())) {
            ImmutableSortedSet<BuildTarget> nodeTests = TargetNodes.getTestTargetsForNode(node);
            if (!nodeTests.isEmpty()) {
              LOG.debug("Got tests for target %s: %s", node.getBuildTarget(), nodeTests);
              testTargetsBuilder.addAll(nodeTests);
            }
          }
          ImmutableSet<BuildTarget> testTargets = testTargetsBuilder.build();
          if (!testTargets.isEmpty()) {
            LOG.debug("Got related test targets %s, building new target graph...", testTargets);
            Iterable<BuildTarget> allTargets =
                Iterables.concat(targetGraphAndBuildTargets.getBuildTargets(), testTargets);
            TargetGraph targetGraph =
                params
                    .getParser()
                    .buildTargetGraph(
                        params.getBuckEventBus(),
                        params.getCell(),
                        getEnableParserProfiling(),
                        pool.getListeningExecutorService(),
                        allTargets);
            LOG.debug("Finished building new target graph with tests.");
            targetGraphAndBuildTargets = TargetGraphAndBuildTargets.of(targetGraph, allTargets);
          }
        }

        if (params.getBuckConfig().getBuildVersions()) {
          targetGraphAndBuildTargets = toVersionedTargetGraph(params, targetGraphAndBuildTargets);
        }

      } catch (BuildFileParseException | VersionException e) {
        params
            .getBuckEventBus()
            .post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
        return ExitCode.PARSE_ERROR;
      }

      ActionGraphAndResolver actionGraphAndResolver =
          params
              .getActionGraphCache()
              .getActionGraph(
                  params.getBuckEventBus(),
                  targetGraphAndBuildTargets.getTargetGraph(),
                  params.getBuckConfig(),
                  params.getRuleKeyConfiguration(),
                  poolSupplier);
      // Look up all of the test rules in the action graph.
      Iterable<TestRule> testRules =
          Iterables.filter(actionGraphAndResolver.getActionGraph().getNodes(), TestRule.class);

      // Unless the user requests that we build filtered tests, filter them out here, before
      // the build.
      if (!isBuildFiltered(params.getBuckConfig())) {
        testRules =
            filterTestRules(
                params.getBuckConfig(), targetGraphAndBuildTargets.getBuildTargets(), testRules);
      }

      MetadataChecker.checkAndCleanIfNeeded(params.getCell());
      CachingBuildEngineBuckConfig cachingBuildEngineBuckConfig =
          params.getBuckConfig().getView(CachingBuildEngineBuckConfig.class);
      try (RuleKeyCacheScope<RuleKey> ruleKeyCacheScope =
          getDefaultRuleKeyCacheScope(
              params,
              new RuleKeyCacheRecycler.SettingsAffectingCache(
                  params.getBuckConfig().getKeySeed(), actionGraphAndResolver.getActionGraph()))) {
        LocalCachingBuildEngineDelegate localCachingBuildEngineDelegate =
            new LocalCachingBuildEngineDelegate(params.getFileHashCache());
        SourcePathRuleFinder sourcePathRuleFinder =
            new SourcePathRuleFinder(actionGraphAndResolver.getResolver());
        try (CachingBuildEngine cachingBuildEngine =
                new CachingBuildEngine(
                    localCachingBuildEngineDelegate,
                    pool.getWeightedListeningExecutorService(),
                    new DefaultStepRunner(),
                    getBuildEngineMode().orElse(cachingBuildEngineBuckConfig.getBuildEngineMode()),
                    cachingBuildEngineBuckConfig.getBuildMetadataStorage(),
                    cachingBuildEngineBuckConfig.getBuildDepFiles(),
                    cachingBuildEngineBuckConfig.getBuildMaxDepFileCacheEntries(),
                    cachingBuildEngineBuckConfig.getBuildArtifactCacheSizeLimit(),
                    actionGraphAndResolver.getResolver(),
                    sourcePathRuleFinder,
                    DefaultSourcePathResolver.from(sourcePathRuleFinder),
                    params.getBuildInfoStoreManager(),
                    cachingBuildEngineBuckConfig.getResourceAwareSchedulingInfo(),
                    cachingBuildEngineBuckConfig.getConsoleLogBuildRuleFailuresInline(),
                    RuleKeyFactories.of(
                        params.getRuleKeyConfiguration(),
                        localCachingBuildEngineDelegate.getFileHashCache(),
                        actionGraphAndResolver.getResolver(),
                        params.getBuckConfig().getBuildInputRuleKeyFileSizeLimit(),
                        ruleKeyCacheScope.getCache()),
                    new NoOpRemoteBuildRuleCompletionWaiter());
            Build build =
                new Build(
                    actionGraphAndResolver.getResolver(),
                    params.getCell(),
                    cachingBuildEngine,
                    params.getArtifactCacheFactory().newInstance(),
                    params
                        .getBuckConfig()
                        .getView(JavaBuckConfig.class)
                        .createDefaultJavaPackageFinder(),
                    params.getClock(),
                    getExecutionContext(),
                    isKeepGoing())) {

          // Build all of the test rules.
          int exitCodeInt =
              build.executeAndPrintFailuresToEventBus(
                  RichStream.from(testRules)
                      .map(TestRule::getBuildTarget)
                      .collect(ImmutableList.toImmutableList()),
                  params.getBuckEventBus(),
                  params.getConsole(),
                  getPathToBuildReport(params.getBuckConfig()));
          ExitCode exitCode = ExitCode.map(exitCodeInt);
          params.getBuckEventBus().post(BuildEvent.finished(started, exitCode));
          if (exitCode != ExitCode.SUCCESS) {
            return exitCode;
          }

          // If the user requests that we build tests that we filter out, then we perform
          // the filtering here, after we've done the build but before we run the tests.
          if (isBuildFiltered(params.getBuckConfig())) {
            testRules =
                filterTestRules(
                    params.getBuckConfig(),
                    targetGraphAndBuildTargets.getBuildTargets(),
                    testRules);
          }

          BuildContext buildContext =
              BuildContext.builder()
                  .setSourcePathResolver(
                      DefaultSourcePathResolver.from(
                          new SourcePathRuleFinder(actionGraphAndResolver.getResolver())))
                  .setBuildCellRootPath(params.getCell().getRoot())
                  .setJavaPackageFinder(params.getJavaPackageFinder())
                  .setEventBus(params.getBuckEventBus())
                  .build();

          // Once all of the rules are built, then run the tests.
          Optional<ImmutableList<String>> externalTestRunner =
              params.getBuckConfig().getExternalTestRunner();
          if (externalTestRunner.isPresent()) {
            return runTestsExternal(
                params, build, externalTestRunner.get(), testRules, buildContext);
          }
          return runTestsInternal(params, cachingBuildEngine, build, buildContext, testRules);
        }
      }
    }
  }

  @Override
  protected ExecutionContext.Builder getExecutionContextBuilder(CommandRunnerParams params) {
    return super.getExecutionContextBuilder(params)
        .setTargetDevice(getTargetDeviceOptional())
        .setAndroidDevicesHelper(
            AndroidDevicesHelperFactory.get(
                params.getCell().getToolchainProvider(),
                this::getExecutionContext,
                params.getBuckConfig(),
                getAdbOptions(params.getBuckConfig()),
                getTargetDeviceOptions()));
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @VisibleForTesting
  Iterable<TestRule> filterTestRules(
      BuckConfig buckConfig,
      ImmutableSet<BuildTarget> explicitBuildTargets,
      Iterable<TestRule> testRules) {

    ImmutableSortedSet.Builder<TestRule> builder =
        ImmutableSortedSet.orderedBy(Comparator.comparing(TestRule::getFullyQualifiedName));

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
  public void printUsage(PrintStream stream) {
    stream.println("Usage:");
    stream.println("  " + "buck test [<targets>] [<options>]");
    stream.println();

    stream.println("Description:");
    stream.println("  Builds and runs the tests for one or more specified targets.");
    stream.println("  You can either directly specify test targets, or any other target which");
    stream.println("  contains a `tests = ['...']` field to specify its tests. Alternatively,");
    stream.println("  by specifying no targets all of the tests will be run.");
    stream.println("  Tests get run by the internal test runner unless an external test runner");
    stream.println("  is specified in the .buckconfig file. Note that not all of the options");
    stream.println("  are applicable to all build rule types. Likewise, when an external test");
    stream.println("  runner is being used, some of the options listed here may not apply, and");
    stream.println("  you may need to use options specific to that test runner. See -- option.");
    stream.println();

    stream.println("Options:");
    new AdditionalOptionsCmdLineParser(this).printUsage(stream);
    stream.println();
  }

  @Override
  public String getShortDescription() {
    return "builds and runs the tests for the specified target";
  }

  /**
   * args4j does not support parsing repeated (or delimiter separated) Enums by default. {@link
   * CoverageReportFormatsHandler} implements args4j behavior for CoverageReportFormat.
   */
  public static class CoverageReportFormatsHandler extends OptionHandler<CoverageReportFormat> {

    public CoverageReportFormatsHandler(
        CmdLineParser parser, OptionDef option, Setter<CoverageReportFormat> setter) {
      super(parser, option, setter);
    }

    @Override
    public int parseArguments(Parameters params) throws CmdLineException {
      Set<String> parsed =
          Splitter.on(",")
              .splitToList(params.getParameter(0))
              .stream()
              .map(s -> s.replaceAll("-", "_").toLowerCase())
              .collect(Collectors.toSet());
      List<CoverageReportFormat> formats = new ArrayList<>();
      for (CoverageReportFormat format : CoverageReportFormat.values()) {
        if (parsed.remove(format.name().toLowerCase())) {
          formats.add(format);
        }
      }

      if (parsed.size() != 0) {
        String invalidFormats = parsed.stream().collect(Collectors.joining(","));
        if (option.isArgument()) {
          throw new CmdLineException(
              owner, Messages.ILLEGAL_OPERAND, option.toString(), invalidFormats);
        } else {
          throw new CmdLineException(
              owner, Messages.ILLEGAL_OPERAND, params.getParameter(-1), invalidFormats);
        }
      }

      for (CoverageReportFormat format : formats) {
        setter.addValue(format);
      }
      return 1;
    }

    @Override
    public String getDefaultMetaVariable() {
      return Arrays.stream(CoverageReportFormat.values())
          .map(Enum::name)
          .collect(Collectors.joining(" | ", "[", "]"));
    }

    @Override
    public String getMetaVariable(ResourceBundle rb) {
      return getDefaultMetaVariable();
    }
  }
}
