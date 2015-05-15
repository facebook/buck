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
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.BuildFileSpec;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.TargetNodePredicateSpec;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.TargetDevice;
import com.facebook.buck.test.CoverageReportFormat;
import com.facebook.buck.util.Console;
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

import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

public class TestCommand extends BuildCommand {

  public static final String USE_RESULTS_CACHE = "use_results_cache";

  @Option(name = "--all",
          usage =
              "Whether all of the tests should be run. " +
              "If no targets are given, --all is implied")
  private boolean all = false;

  @Option(name = "--code-coverage", usage = "Whether code coverage information will be generated.")
  private boolean isCodeCoverageEnabled = false;

  @Option(name = "--code-coverage-format", usage = "Format to be used for coverage")
  private CoverageReportFormat coverageReportFormat = CoverageReportFormat.HTML;

  @Option(name = "--debug",
          usage = "Whether the test will start suspended with a JDWP debug port of 5005")
  private boolean isDebugEnabled = false;

  @Option(name = "--xml", usage = "Where to write test output as XML.")
  @Nullable
  private String pathToXmlTestOutput = null;

  @Option(name = "--no-results-cache", usage = "Whether to use cached test results.")
  @Nullable
  private Boolean isResultsCacheDisabled = null;

  @Option(name = "--build-filtered", usage = "Whether to build filtered out tests.")
  @Nullable
  private Boolean isBuildFiltered = null;

  @Option(
      name = "--ignore-when-dependencies-fail",
      aliases = {"-i"},
      usage =
          "Ignore test failures for libraries if they depend on other libraries " +
          "that aren't passing their tests.  " +
          "For example, if java_library A depends on B, " +
          "and they are tested respectively by T1 and T2 and both of those tests fail, " +
          "only print the error for T2.")
  private boolean isIgnoreFailingDependencies = false;

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
  private TargetDeviceOptions targetDeviceOptions;

  @AdditionalOptions
  @SuppressFieldNotInitialized
  private TestSelectorOptions testSelectorOptions;

  @AdditionalOptions
  @SuppressFieldNotInitialized
  private TestLabelOptions testLabelOptions;

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
    return getNumThreads(buckConfig);
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {

    // Post the build started event, setting it to the Parser recorded start time if appropriate.
    if (params.getParser().getParseStartTime().isPresent()) {
      params.getBuckEventBus().post(
          BuildEvent.started(getArguments()),
          params.getParser().getParseStartTime().get());
    } else {
      params.getBuckEventBus().post(BuildEvent.started(getArguments()));
    }

    // The first step is to parse all of the build files. This will populate the parser and find all
    // of the test rules.
    ParserConfig parserConfig = new ParserConfig(params.getBuckConfig());
    TargetGraph targetGraph;
    ImmutableSet<BuildTarget> explicitBuildTargets;

    try {

      // If the user asked to run all of the tests, parse all of the build files looking for any
      // test rules.
      if (isRunAllTests()) {
        targetGraph = params.getParser().buildTargetGraphForTargetNodeSpecs(
            ImmutableList.of(
                TargetNodePredicateSpec.of(
                    new Predicate<TargetNode<?>>() {
                      @Override
                      public boolean apply(TargetNode<?> input) {
                        return input.getType().isTestRule();
                      }
                    },
                    BuildFileSpec.fromRecursivePath(
                        Paths.get(""),
                        params.getRepository().getFilesystem().getIgnorePaths()))),
            parserConfig,
            params.getBuckEventBus(),
            params.getConsole(),
            params.getEnvironment(),
            getEnableProfiling()).getSecond();
        explicitBuildTargets = ImmutableSet.of();

        // Otherwise, the user specified specific test targets to build and run, so build a graph
        // around these.
      } else {
        Pair<ImmutableSet<BuildTarget>, TargetGraph> result = params.getParser()
            .buildTargetGraphForTargetNodeSpecs(
                parseArgumentsAsTargetNodeSpecs(
                    params.getBuckConfig(),
                    params.getRepository().getFilesystem().getIgnorePaths(),
                    getArguments()),
                parserConfig,
                params.getBuckEventBus(),
                params.getConsole(),
                params.getEnvironment(),
                getEnableProfiling());
        targetGraph = result.getSecond();
        explicitBuildTargets = result.getFirst();
      }

    } catch (BuildTargetException | BuildFileParseException e) {
      params.getConsole().printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    ActionGraph graph = new TargetGraphToActionGraph(
        params.getBuckEventBus(),
        new BuildTargetNodeToBuildRuleTransformer()).apply(targetGraph);

    // Look up all of the test rules in the action graph.
    Iterable<TestRule> testRules = Iterables.filter(graph.getNodes(), TestRule.class);

    // Unless the user requests that we build filtered tests, filter them out here, before
    // the build.
    if (!isBuildFiltered(params.getBuckConfig())) {
      testRules = filterTestRules(params.getBuckConfig(), explicitBuildTargets, testRules);
    }

    if (isDryRun()) {
      printMatchingTestRules(params.getConsole(), testRules);
    }

    // Create artifact cache to initialize Cassandra connection, if appropriate.
    ArtifactCache artifactCache = getArtifactCache(params);

    try (CommandThreadManager pool =
             new CommandThreadManager("Test", getConcurrencyLimit(params.getBuckConfig()))) {
      CachingBuildEngine cachingBuildEngine =
          new CachingBuildEngine(
              pool.getExecutor(),
              params.getBuckConfig().getSkipLocalBuildChainDepth().or(1L));
      try (Build build = createBuild(
          params.getBuckConfig(),
          graph,
          params.getRepository().getFilesystem(),
          params.getAndroidPlatformTargetSupplier(),
          cachingBuildEngine,
          artifactCache,
          params.getConsole(),
          params.getBuckEventBus(),
          getTargetDeviceOptional(),
          params.getPlatform(),
          params.getEnvironment(),
          params.getObjectMapper(),
          params.getClock())) {

        // Build all of the test rules.
        int exitCode = build.executeAndPrintFailuresToConsole(
            testRules,
            isKeepGoing(),
            params.getConsole(),
            getPathToBuildReport(params.getBuckConfig()));
        params.getBuckEventBus().post(BuildEvent.finished(getArguments(), exitCode));
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
        ConcurrencyLimit concurrencyLimit = new ConcurrencyLimit(
            getNumTestThreads(params.getBuckConfig()),
            getLoadLimit(params.getBuckConfig()));
        try (CommandThreadManager testPool =
                 new CommandThreadManager("Test-Run", concurrencyLimit)) {
          TestRunningOptions options = TestRunningOptions.builder()
              .setUsingOneTimeOutputDirectories(isUsingOneTimeOutput)
              .setCodeCoverageEnabled(isCodeCoverageEnabled)
              .setRunAllTests(isRunAllTests())
              .setTestSelectorList(testSelectorOptions.getTestSelectorList())
              .setShouldExplainTestSelectorList(testSelectorOptions.shouldExplain())
              .setIgnoreFailingDependencies(isIgnoreFailingDependencies)
              .setResultsCacheEnabled(isResultsCacheEnabled(params.getBuckConfig()))
              .setDryRun(isDryRun)
              .setShufflingTests(isShufflingTests)
              .setPathToXmlTestOutput(Optional.fromNullable(pathToXmlTestOutput))
              .setCoverageReportFormat(coverageReportFormat)
              .build();
          return TestRunning.runTests(
              params,
              testRules,
              Preconditions.checkNotNull(build.getBuildContext()),
              build.getExecutionContext(),
              options,
              testPool.getExecutor(),
              cachingBuildEngine,
              new DefaultStepRunner(build.getExecutionContext()));
        } catch (ExecutionException e) {
          params.getConsole().printBuildFailureWithoutStacktrace(e);
          return 1;
        }
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
