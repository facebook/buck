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

import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.LOCAL_FILE_HASH_COMPUTATION;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.LOCAL_GRAPH_CONSTRUCTION;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.LOCAL_PREPARATION;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.PERFORM_LOCAL_BUILD;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.POST_BUILD_ANALYSIS;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.POST_DISTRIBUTED_BUILD_LOCAL_STEPS;
import static com.facebook.buck.util.concurrent.MostExecutors.newMultiThreadExecutor;

import com.facebook.buck.apple.AppleBundle;
import com.facebook.buck.apple.AppleDsym;
import com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig;
import com.facebook.buck.command.Build;
import com.facebook.buck.command.LocalBuildExecutor;
import com.facebook.buck.command.LocalBuildExecutorInvoker;
import com.facebook.buck.core.build.distributed.synchronization.RemoteBuildRuleCompletionWaiter;
import com.facebook.buck.core.build.distributed.synchronization.impl.NoOpRemoteBuildRuleCompletionWaiter;
import com.facebook.buck.core.build.distributed.synchronization.impl.RemoteBuildRuleSynchronizer;
import com.facebook.buck.core.build.engine.delegate.LocalCachingBuildEngineDelegate;
import com.facebook.buck.core.build.engine.type.BuildType;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.graph.ActionAndTargetGraphs;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rulekey.calculator.ParallelRuleKeyCalculator;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.transformer.impl.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.distributed.AnalysisResults;
import com.facebook.buck.distributed.BuckVersionUtil;
import com.facebook.buck.distributed.BuildJobStateSerializer;
import com.facebook.buck.distributed.ClientStatsTracker;
import com.facebook.buck.distributed.DistBuildCellIndexer;
import com.facebook.buck.distributed.DistBuildClientStatsEvent;
import com.facebook.buck.distributed.DistBuildConfig;
import com.facebook.buck.distributed.DistBuildFileHashes;
import com.facebook.buck.distributed.DistBuildPostBuildAnalysis;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildState;
import com.facebook.buck.distributed.DistBuildTargetGraphCodec;
import com.facebook.buck.distributed.DistLocalBuildMode;
import com.facebook.buck.distributed.DistributedExitCode;
import com.facebook.buck.distributed.RuleKeyNameAndType;
import com.facebook.buck.distributed.build_client.DistBuildControllerArgs;
import com.facebook.buck.distributed.build_client.DistBuildControllerInvocationArgs;
import com.facebook.buck.distributed.build_client.DistBuildSuperConsoleEvent;
import com.facebook.buck.distributed.build_client.LogStateTracker;
import com.facebook.buck.distributed.build_client.StampedeBuildClient;
import com.facebook.buck.distributed.thrift.BuckVersion;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.distributed.thrift.RemoteCommand;
import com.facebook.buck.distributed.thrift.RuleKeyLogEntry;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.listener.DistBuildClientEventListener;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.GlobalStateManager;
import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.parser.DefaultParserTargetNodeFactory;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.ParserTargetNodeFactory;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.PathTypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.rules.keys.RuleKeyCacheScope;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.rules.visibility.VisibilityPatternFactory;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.concurrent.CommandThreadFactory;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.versions.VersionException;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.immutables.value.Value;
import org.immutables.value.Value.Immutable;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

public class BuildCommand extends AbstractCommand {

  private static final Logger LOG = Logger.get(BuildCommand.class);

  private static final String KEEP_GOING_LONG_ARG = "--keep-going";
  private static final String BUILD_REPORT_LONG_ARG = "--build-report";
  private static final String JUST_BUILD_LONG_ARG = "--just-build";
  private static final String DEEP_LONG_ARG = "--deep";
  private static final String OUT_LONG_ARG = "--out";
  private static final String POPULATE_CACHE_LONG_ARG = "--populate-cache";
  private static final String SHALLOW_LONG_ARG = "--shallow";
  private static final String REPORT_ABSOLUTE_PATHS = "--report-absolute-paths";
  private static final String SHOW_OUTPUT_LONG_ARG = "--show-output";
  private static final String SHOW_FULL_OUTPUT_LONG_ARG = "--show-full-output";
  private static final String SHOW_JSON_OUTPUT_LONG_ARG = "--show-json-output";
  private static final String SHOW_FULL_JSON_OUTPUT_LONG_ARG = "--show-full-json-output";
  private static final String SHOW_RULEKEY_LONG_ARG = "--show-rulekey";
  private static final String LOCAL_BUILD_LONG_ARG = "--local";
  private static final String DISTRIBUTED_LONG_ARG = "--distributed";
  private static final String BUCK_BINARY_STRING_ARG = "--buck-binary";
  private static final String RULEKEY_LOG_PATH_LONG_ARG = "--rulekeys-log-path";

  private static final String BUCK_GIT_COMMIT_KEY = "buck.git_commit";

  private static final int STAMPEDE_EXECUTOR_SHUTDOWN_TIMEOUT_MILLIS = 100;

  @Option(name = KEEP_GOING_LONG_ARG, usage = "Keep going when some targets can't be made.")
  private boolean keepGoing = false;

  @Option(name = BUILD_REPORT_LONG_ARG, usage = "File where build report will be written.")
  @Nullable
  private Path buildReport = null;

  @Nullable
  @Option(
      name = JUST_BUILD_LONG_ARG,
      usage = "For debugging, limits the build to a specific target in the action graph.",
      hidden = true)
  private String justBuildTarget = null;

  @Option(
      name = DEEP_LONG_ARG,
      usage =
          "Perform a \"deep\" build, which makes the output of all transitive dependencies"
              + " available.",
      forbids = SHALLOW_LONG_ARG)
  private boolean deepBuild = false;

  @Option(
      name = POPULATE_CACHE_LONG_ARG,
      usage =
          "Performs a cache population, which makes the output of all unchanged "
              + "transitive dependencies available (if these outputs are available "
              + "in the remote cache). Does not build changed or unavailable dependencies locally.",
      forbids = {SHALLOW_LONG_ARG, DEEP_LONG_ARG})
  private boolean populateCacheOnly = false;

  @Option(
      name = SHALLOW_LONG_ARG,
      usage =
          "Perform a \"shallow\" build, which only makes the output of all explicitly listed"
              + " targets available.",
      forbids = DEEP_LONG_ARG)
  private boolean shallowBuild = false;

  @Option(
      name = REPORT_ABSOLUTE_PATHS,
      usage = "Reports errors using absolute paths to the source files instead of relative paths.")
  private boolean shouldReportAbsolutePaths = false;

  @Option(
      name = SHOW_OUTPUT_LONG_ARG,
      usage = "Print the path to the output for each of the built rules relative to the cell.")
  private boolean showOutput;

  @Option(name = OUT_LONG_ARG, usage = "Copies the output of the lone build target to this path.")
  @Nullable
  private Path outputPathForSingleBuildTarget;

  @Option(
      name = SHOW_FULL_OUTPUT_LONG_ARG,
      usage = "Print the absolute path to the output for each of the built rules.")
  private boolean showFullOutput;

  @Option(name = SHOW_JSON_OUTPUT_LONG_ARG, usage = "Show output in JSON format.")
  private boolean showJsonOutput;

  @Option(name = SHOW_FULL_JSON_OUTPUT_LONG_ARG, usage = "Show full output in JSON format.")
  private boolean showFullJsonOutput;

  @Option(name = SHOW_RULEKEY_LONG_ARG, usage = "Print the rulekey for each of the built rules.")
  private boolean showRuleKey;

  @Option(
      name = LOCAL_BUILD_LONG_ARG,
      usage = "Disable distributed build (overrides --distributed).")
  private boolean forceDisableDistributedBuild = false;

  @Option(
      name = DISTRIBUTED_LONG_ARG,
      usage = "Whether to run in distributed build mode. (experimental)",
      hidden = true)
  private boolean useDistributedBuild = false; // Must be accessed via the getter method.

  private boolean autoDistBuild = false;
  private Optional<String> autoDistBuildMessage = Optional.empty();

  @Nullable
  @Option(
      name = DistBuildRunCommand.BUILD_STATE_FILE_ARG_NAME,
      usage = DistBuildRunCommand.BUILD_STATE_FILE_ARG_USAGE,
      hidden = true)
  private String distributedBuildStateFile = null;

  @Nullable
  @Option(
      name = BUCK_BINARY_STRING_ARG,
      usage = "Buck binary to use on a distributed build instead of the current git version.",
      hidden = true)
  private String buckBinary = null;

  @Nullable
  @Option(
      name = RULEKEY_LOG_PATH_LONG_ARG,
      usage = "If set, log a binary representation of rulekeys to this file.")
  private String ruleKeyLogPath = null;

  @Argument private List<String> arguments = new ArrayList<>();

  @Nullable private DistBuildClientEventListener distBuildClientEventListener;

  public List<String> getArguments() {
    return arguments;
  }

  public boolean isCodeCoverageEnabled() {
    return false;
  }

  public boolean isDebugEnabled() {
    return false;
  }

  public BuildCommand() {
    this(ImmutableList.of());
  }

  public BuildCommand(List<String> arguments) {
    this.arguments.addAll(arguments);
  }

  public Optional<BuildType> getBuildEngineMode() {
    Optional<BuildType> mode = Optional.empty();
    if (deepBuild) {
      mode = Optional.of(BuildType.DEEP);
    }
    if (populateCacheOnly) {
      mode = Optional.of(BuildType.POPULATE_FROM_REMOTE_CACHE);
    }
    if (shallowBuild) {
      mode = Optional.of(BuildType.SHALLOW);
    }
    return mode;
  }

  public boolean isKeepGoing() {
    return keepGoing;
  }

  protected boolean shouldReportAbsolutePaths() {
    return shouldReportAbsolutePaths;
  }

  public void setKeepGoing(boolean keepGoing) {
    this.keepGoing = keepGoing;
  }

  /** Whether this build is using stampede or not. */
  public boolean isUsingDistributedBuild() {
    if (forceDisableDistributedBuild) {
      useDistributedBuild = false;
    }

    return useDistributedBuild;
  }

  /**
   * Mark this build as being automatically converted to stampede.
   *
   * @param config to retrieve the message (if any) to be shown to the user.
   * @return true if the build was converted to stampede.
   */
  public boolean tryConvertingToStampede(DistBuildConfig config) {
    if (forceDisableDistributedBuild) {
      LOG.warn(
          String.format(
              "%s has been specified. Will not auto-convert build to stampede.",
              LOCAL_BUILD_LONG_ARG));

      useDistributedBuild = false; // Make sure
      return false;
    }

    autoDistBuild = true;
    useDistributedBuild = true;
    autoDistBuildMessage = config.getAutoDistributedBuildMessage();
    return true;
  }

  /** @return an absolute path or {@link Optional#empty()}. */
  public Optional<Path> getPathToBuildReport(BuckConfig buckConfig) {
    return Optional.ofNullable(
        buckConfig.resolvePathThatMayBeOutsideTheProjectFilesystem(buildReport));
  }

  private final AtomicReference<Build> lastBuild = new AtomicReference<>(null);
  private final SettableFuture<ParallelRuleKeyCalculator<RuleKey>> localRuleKeyCalculator =
      SettableFuture.create();

  /**
   * Create the serializable {@link BuildJobState} for distributed builds.
   *
   * @param buildTargets - Top level targets.
   * @param params - Client side parameters.
   * @param executor - Executor for async ops.
   * @return - New instance of serializable {@link BuildJobState}.
   * @throws InterruptedException
   * @throws IOException
   */
  public static ListenableFuture<BuildJobState> getAsyncDistBuildState(
      List<String> buildTargets,
      CommandRunnerParams params,
      WeightedListeningExecutorService executor)
      throws InterruptedException, IOException {
    BuildCommand buildCommand = new BuildCommand(buildTargets);
    buildCommand.assertArguments(params);

    GraphsAndBuildTargets graphsAndBuildTargets = null;
    try {
      graphsAndBuildTargets =
          buildCommand.createGraphsAndTargets(params, executor, Optional.empty());
    } catch (ActionGraphCreationException e) {
      throw BuildFileParseException.createForUnknownParseError(e.getMessage());
    }

    return buildCommand.computeDistBuildState(
            params, graphsAndBuildTargets, executor, Optional.empty(), RemoteCommand.BUILD)
        .asyncJobState;
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    assertArguments(params);

    ListeningProcessExecutor processExecutor = new ListeningProcessExecutor();
    try (CommandThreadManager pool =
            new CommandThreadManager("Build", getConcurrencyLimit(params.getBuckConfig()));
        BuildPrehook prehook =
            new BuildPrehook(
                processExecutor,
                params.getCell(),
                params.getBuckEventBus(),
                params.getBuckConfig(),
                params.getEnvironment(),
                getArguments())) {
      prehook.startPrehookScript();
      return run(params, pool, ImmutableSet.of()).getExitCode();
    }
  }

  /** @throw CommandLineException if arguments provided are incorrect */
  protected void assertArguments(CommandRunnerParams params) {
    if (!getArguments().isEmpty()) {
      return;
    }
    String message =
        "Must specify at least one build target. See https://buckbuild.com/concept/build_target_pattern.html";
    ImmutableSet<String> aliases = params.getBuckConfig().getAliases().keySet();
    if (!aliases.isEmpty()) {
      // If there are aliases defined in .buckconfig, suggest that the user
      // build one of them. We show the user only the first 10 aliases.
      message +=
          String.format(
              "%nTry building one of the following targets:%n%s",
              Joiner.on(' ').join(Iterators.limit(aliases.iterator(), 10)));
    }
    throw new CommandLineException(message);
  }

  protected BuildRunResult run(
      CommandRunnerParams params,
      CommandThreadManager commandThreadManager,
      ImmutableSet<String> additionalTargets)
      throws IOException, InterruptedException {
    if (!additionalTargets.isEmpty()) {
      this.arguments.addAll(additionalTargets);
    }
    BuildEvent.Started started = postBuildStartedEvent(params);
    BuildRunResult result = ImmutableBuildRunResult.of(ExitCode.BUILD_ERROR, ImmutableList.of());
    try {
      result = executeBuildAndProcessResult(params, commandThreadManager);
    } catch (ActionGraphCreationException e) {
      params.getConsole().printBuildFailure(e.getMessage());
      result = ImmutableBuildRunResult.of(ExitCode.PARSE_ERROR, ImmutableList.of());
    } finally {
      params.getBuckEventBus().post(BuildEvent.finished(started, result.getExitCode()));
    }

    return result;
  }

  private BuildEvent.Started postBuildStartedEvent(CommandRunnerParams params) {
    BuildEvent.Started started = BuildEvent.started(getArguments());
    params.getBuckEventBus().post(started);
    return started;
  }

  private GraphsAndBuildTargets createGraphsAndTargets(
      CommandRunnerParams params,
      ListeningExecutorService executorService,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger)
      throws ActionGraphCreationException, IOException, InterruptedException {
    TargetGraphAndBuildTargets unversionedTargetGraph =
        createUnversionedTargetGraph(params, executorService);

    Optional<TargetGraphAndBuildTargets> versionedTargetGraph = Optional.empty();
    try {
      if (params.getBuckConfig().getBuildVersions()) {
        versionedTargetGraph = Optional.of(toVersionedTargetGraph(params, unversionedTargetGraph));
      }
    } catch (VersionException e) {
      throw new ActionGraphCreationException(MoreExceptions.getHumanReadableOrLocalizedMessage(e));
    }

    TargetGraphAndBuildTargets targetGraphForLocalBuild =
        ActionAndTargetGraphs.getTargetGraphForLocalBuild(
            unversionedTargetGraph, versionedTargetGraph);
    checkSingleBuildTargetSpecifiedForOutBuildMode(targetGraphForLocalBuild);
    ActionGraphAndBuilder actionGraph =
        createActionGraphAndResolver(params, targetGraphForLocalBuild, ruleKeyLogger);

    ImmutableSet<BuildTarget> buildTargets =
        getBuildTargets(params, actionGraph, targetGraphForLocalBuild, justBuildTarget);

    ActionAndTargetGraphs actionAndTargetGraphs =
        ActionAndTargetGraphs.builder()
            .setUnversionedTargetGraph(unversionedTargetGraph)
            .setVersionedTargetGraph(versionedTargetGraph)
            .setActionGraphAndBuilder(actionGraph)
            .build();

    return ImmutableGraphsAndBuildTargets.of(actionAndTargetGraphs, buildTargets);
  }

  private void checkSingleBuildTargetSpecifiedForOutBuildMode(
      TargetGraphAndBuildTargets targetGraphAndBuildTargets) {
    // Ideally, we would error out of this before we build the entire graph, but it is possible
    // that `getArguments().size()` is 1 but `targetGraphAndBuildTargets.getBuildTargets().size()`
    // is greater than 1 if the lone argument is a wildcard build target that ends in "...".
    // As such, we have to get the result of createTargetGraph() before we can do this check.
    if (outputPathForSingleBuildTarget != null
        && targetGraphAndBuildTargets.getBuildTargets().size() != 1) {
      throw new CommandLineException(
          String.format(
              "When using %s you must specify exactly one build target, but you specified %s",
              OUT_LONG_ARG, targetGraphAndBuildTargets.getBuildTargets()));
    }
  }

  private BuildRunResult executeBuildAndProcessResult(
      CommandRunnerParams params, CommandThreadManager commandThreadManager)
      throws IOException, InterruptedException, ActionGraphCreationException {
    ExitCode exitCode = ExitCode.SUCCESS;
    GraphsAndBuildTargets graphsAndBuildTargets;
    if (isUsingDistributedBuild()) {
      DistBuildConfig distBuildConfig = new DistBuildConfig(params.getBuckConfig());
      ClientStatsTracker distBuildClientStatsTracker =
          new ClientStatsTracker(
              distBuildConfig.getBuildLabel(), distBuildConfig.getMinionType().toString());

      distBuildClientStatsTracker.startTimer(LOCAL_PREPARATION);
      distBuildClientStatsTracker.startTimer(LOCAL_GRAPH_CONSTRUCTION);
      graphsAndBuildTargets =
          createGraphsAndTargets(
              params, commandThreadManager.getListeningExecutorService(), Optional.empty());
      distBuildClientStatsTracker.stopTimer(LOCAL_GRAPH_CONSTRUCTION);

      try (RuleKeyCacheScope<RuleKey> ruleKeyCacheScope =
          getDefaultRuleKeyCacheScope(
              params, graphsAndBuildTargets.getGraphs().getActionGraphAndBuilder())) {
        try {
          exitCode =
              executeDistBuild(
                  params,
                  distBuildConfig,
                  graphsAndBuildTargets,
                  commandThreadManager.getWeightedListeningExecutorService(),
                  params.getCell().getFilesystem(),
                  params.getFileHashCache(),
                  distBuildClientStatsTracker,
                  ruleKeyCacheScope);
        } catch (Throwable ex) {
          String stackTrace = Throwables.getStackTraceAsString(ex);
          distBuildClientStatsTracker.setBuckClientErrorMessage(ex + "\n" + stackTrace);
          distBuildClientStatsTracker.setBuckClientError(true);

          throw ex;
        } finally {
          if (distributedBuildStateFile == null) {
            params
                .getBuckEventBus()
                .post(new DistBuildClientStatsEvent(distBuildClientStatsTracker.generateStats()));
          }
        }
        if (exitCode == ExitCode.SUCCESS) {
          exitCode = processSuccessfulBuild(params, graphsAndBuildTargets, ruleKeyCacheScope);
        }
      }
    } else {
      try (ThriftRuleKeyLogger ruleKeyLogger = createRuleKeyLogger().orElse(null)) {
        Optional<ThriftRuleKeyLogger> optionalRuleKeyLogger = Optional.ofNullable(ruleKeyLogger);
        graphsAndBuildTargets =
            createGraphsAndTargets(
                params, commandThreadManager.getListeningExecutorService(), optionalRuleKeyLogger);
        try (RuleKeyCacheScope<RuleKey> ruleKeyCacheScope =
            getDefaultRuleKeyCacheScope(
                params, graphsAndBuildTargets.getGraphs().getActionGraphAndBuilder())) {
          exitCode =
              executeLocalBuild(
                  params,
                  graphsAndBuildTargets,
                  commandThreadManager.getWeightedListeningExecutorService(),
                  optionalRuleKeyLogger,
                  new NoOpRemoteBuildRuleCompletionWaiter(),
                  false,
                  Optional.empty(),
                  ruleKeyCacheScope,
                  lastBuild);
          if (exitCode == ExitCode.SUCCESS) {
            exitCode = processSuccessfulBuild(params, graphsAndBuildTargets, ruleKeyCacheScope);
          }
        }
      }
    }

    return ImmutableBuildRunResult.of(exitCode, graphsAndBuildTargets.getBuildTargets());
  }

  /**
   * Create a {@link ThriftRuleKeyLogger} depending on whether {@link BuildCommand#ruleKeyLogPath}
   * is set or not
   */
  private Optional<ThriftRuleKeyLogger> createRuleKeyLogger() throws IOException {
    if (ruleKeyLogPath == null) {
      return Optional.empty();
    } else {
      return Optional.of(ThriftRuleKeyLogger.create(Paths.get(ruleKeyLogPath)));
    }
  }

  private ExitCode processSuccessfulBuild(
      CommandRunnerParams params,
      GraphsAndBuildTargets graphsAndBuildTargets,
      RuleKeyCacheScope<RuleKey> ruleKeyCacheScope)
      throws IOException {
    if (params.getBuckConfig().createBuildOutputSymLinksEnabled()) {
      symLinkBuildResults(params, graphsAndBuildTargets);
    }
    ActionAndTargetGraphs graphs = graphsAndBuildTargets.getGraphs();
    if (showOutput || showFullOutput || showJsonOutput || showFullJsonOutput || showRuleKey) {
      showOutputs(params, graphsAndBuildTargets, ruleKeyCacheScope);
    }
    if (outputPathForSingleBuildTarget != null) {
      BuildTarget loneTarget =
          Iterables.getOnlyElement(graphs.getTargetGraphForLocalBuild().getBuildTargets());
      BuildRule rule =
          graphs.getActionGraphAndBuilder().getActionGraphBuilder().getRule(loneTarget);
      if (!rule.outputFileCanBeCopied()) {
        params
            .getConsole()
            .printErrorText(
                String.format(
                    "%s does not have an output that is compatible with `buck build --out`",
                    loneTarget));
        return ExitCode.BUILD_ERROR;
      } else {
        SourcePath output =
            Preconditions.checkNotNull(
                rule.getSourcePathToOutput(),
                "%s specified a build target that does not have an output file: %s",
                OUT_LONG_ARG,
                loneTarget);

        ProjectFilesystem projectFilesystem = params.getCell().getFilesystem();
        SourcePathResolver pathResolver =
            DefaultSourcePathResolver.from(
                new SourcePathRuleFinder(
                    graphs.getActionGraphAndBuilder().getActionGraphBuilder()));

        Path outputPath;
        if (Files.isDirectory(outputPathForSingleBuildTarget)) {
          Path outputDir = outputPathForSingleBuildTarget.normalize();
          Path outputFilename = pathResolver.getAbsolutePath(output).getFileName();
          outputPath = outputDir.resolve(outputFilename);
        } else {
          outputPath = outputPathForSingleBuildTarget;
        }

        projectFilesystem.copyFile(pathResolver.getAbsolutePath(output), outputPath);
      }
    }
    return ExitCode.SUCCESS;
  }

  private void symLinkBuildRuleResult(
      SourcePathResolver pathResolver,
      BuckConfig buckConfig,
      Path lastOutputDirPath,
      BuildRule rule)
      throws IOException {
    Optional<Path> outputPath =
        TargetsCommand.getUserFacingOutputPath(
            pathResolver, rule, buckConfig.getBuckOutCompatLink());
    if (outputPath.isPresent()) {
      Path absolutePath = outputPath.get();
      Path destPath = lastOutputDirPath.relativize(absolutePath);
      Path linkPath = lastOutputDirPath.resolve(absolutePath.getFileName());
      // Don't overwrite existing symlink in case there are duplicate names.
      if (!Files.exists(linkPath, LinkOption.NOFOLLOW_LINKS)) {
        ProjectFilesystem projectFilesystem = rule.getProjectFilesystem();
        projectFilesystem.createSymLink(linkPath, destPath, false);
      }
    }
  }

  private void symLinkBuildResults(
      CommandRunnerParams params, GraphsAndBuildTargets graphsAndBuildTargets) throws IOException {
    // Clean up last buck-out/last.
    Path lastOutputDirPath =
        params.getCell().getFilesystem().getBuckPaths().getLastOutputDir().toAbsolutePath();
    MostFiles.deleteRecursivelyIfExists(lastOutputDirPath);
    Files.createDirectories(lastOutputDirPath);

    ActionGraphBuilder graphBuilder =
        graphsAndBuildTargets.getGraphs().getActionGraphAndBuilder().getActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    for (BuildTarget buildTarget : graphsAndBuildTargets.getBuildTargets()) {
      BuildRule rule = graphBuilder.requireRule(buildTarget);
      // If it's an apple bundle, we'd like to also link the dSYM file over here.
      if (rule instanceof AppleBundle) {
        AppleBundle bundle = (AppleBundle) rule;
        Optional<AppleDsym> dsym = bundle.getAppleDsym();
        if (dsym.isPresent()) {
          symLinkBuildRuleResult(
              pathResolver, params.getBuckConfig(), lastOutputDirPath, dsym.get());
        }
      }
      symLinkBuildRuleResult(pathResolver, params.getBuckConfig(), lastOutputDirPath, rule);
    }
  }

  private AsyncJobStateAndCells computeDistBuildState(
      CommandRunnerParams params,
      GraphsAndBuildTargets graphsAndBuildTargets,
      WeightedListeningExecutorService executorService,
      Optional<ClientStatsTracker> clientStatsTracker,
      RemoteCommand remoteCommand) {
    DistBuildCellIndexer cellIndexer = new DistBuildCellIndexer(params.getCell());

    // Compute the file hashes.
    ActionGraphAndBuilder actionGraphAndBuilder =
        graphsAndBuildTargets.getGraphs().getActionGraphAndBuilder();
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(actionGraphAndBuilder.getActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    clientStatsTracker.ifPresent(tracker -> tracker.startTimer(LOCAL_FILE_HASH_COMPUTATION));
    DistBuildFileHashes distributedBuildFileHashes =
        new DistBuildFileHashes(
            actionGraphAndBuilder.getActionGraph(),
            pathResolver,
            ruleFinder,
            params.getFileHashCache(),
            cellIndexer,
            executorService,
            params.getRuleKeyConfiguration(),
            params.getCell());
    distributedBuildFileHashes
        .getFileHashesComputationFuture()
        .addListener(
            () ->
                clientStatsTracker.ifPresent(
                    tracker -> tracker.stopTimer(LOCAL_FILE_HASH_COMPUTATION)),
            executorService);

    // Distributed builds serialize and send the unversioned target graph,
    // and then deserialize and version remotely.
    TargetGraphAndBuildTargets targetGraphAndBuildTargets =
        graphsAndBuildTargets.getGraphs().getTargetGraphForDistributedBuild();

    TypeCoercerFactory typeCoercerFactory =
        new DefaultTypeCoercerFactory(PathTypeCoercer.PathExistenceVerificationMode.DO_NOT_VERIFY);
    ParserTargetNodeFactory<Map<String, Object>> parserTargetNodeFactory =
        DefaultParserTargetNodeFactory.createForDistributedBuild(
            params.getKnownRuleTypesProvider(),
            new ConstructorArgMarshaller(typeCoercerFactory),
            new TargetNodeFactory(typeCoercerFactory),
            new VisibilityPatternFactory(),
            params.getRuleKeyConfiguration());
    DistBuildTargetGraphCodec targetGraphCodec =
        new DistBuildTargetGraphCodec(
            executorService,
            parserTargetNodeFactory,
            input -> {
              return params
                  .getParser()
                  .getTargetNodeRawAttributes(
                      params.getCell().getCell(input.getBuildTarget()),
                      false /* enableProfiling */,
                      executorService,
                      input);
            },
            targetGraphAndBuildTargets
                .getBuildTargets()
                .stream()
                .map(t -> t.getFullyQualifiedName())
                .collect(Collectors.toSet()));

    ListenableFuture<BuildJobState> asyncJobState =
        executorService.submit(
            () -> {
              try {
                BuildJobState state =
                    DistBuildState.dump(
                        cellIndexer,
                        distributedBuildFileHashes,
                        targetGraphCodec,
                        targetGraphAndBuildTargets.getTargetGraph(),
                        graphsAndBuildTargets.getBuildTargets(),
                        remoteCommand,
                        clientStatsTracker);
                LOG.info("Finished computing serializable distributed build state.");
                return state;
              } catch (InterruptedException ex) {
                distributedBuildFileHashes.cancel();
                LOG.warn(
                    ex,
                    "Failed computing serializable distributed build state as interrupted. Local build probably finished first.");
                Thread.currentThread().interrupt();
                throw ex;
              }
            });

    Futures.addCallback(
        asyncJobState,
        new FutureCallback<BuildJobState>() {
          @Override
          public void onSuccess(@Nullable BuildJobState result) {
            LOG.info("Finished creating stampede BuildJobState.");
          }

          @Override
          public void onFailure(Throwable t) {
            // We need to cancel file hash computation here as well, in case the asyncJobState
            // future didn't start at all, and hence wasn't able to cancel file hash computation
            // itself.
            LOG.warn("Failed to create stampede BuildJobState. Cancelling file hash computation.");
            distributedBuildFileHashes.cancel();
          }
        },
        MoreExecutors.directExecutor());

    return new AsyncJobStateAndCells(asyncJobState, cellIndexer);
  }

  private ListeningExecutorService createStampedeControllerExecutorService(int maxThreads) {
    CommandThreadFactory stampedeCommandThreadFactory =
        new CommandThreadFactory(
            "StampedeController", GlobalStateManager.singleton().getThreadToCommandRegister());
    return MoreExecutors.listeningDecorator(
        newMultiThreadExecutor(stampedeCommandThreadFactory, maxThreads));
  }

  private ListeningExecutorService createStampedeLocalBuildExecutorService() {
    CommandThreadFactory stampedeCommandThreadFactory =
        new CommandThreadFactory(
            "StampedeLocalBuild", GlobalStateManager.singleton().getThreadToCommandRegister());
    return MoreExecutors.listeningDecorator(
        MostExecutors.newSingleThreadExecutor(stampedeCommandThreadFactory));
  }

  private ExitCode executeDistBuild(
      CommandRunnerParams params,
      DistBuildConfig distBuildConfig,
      GraphsAndBuildTargets graphsAndBuildTargets,
      WeightedListeningExecutorService executorService,
      ProjectFilesystem filesystem,
      FileHashCache fileHashCache,
      ClientStatsTracker distBuildClientStats,
      RuleKeyCacheScope<RuleKey> ruleKeyCacheScope)
      throws IOException, InterruptedException {
    Preconditions.checkNotNull(distBuildClientEventListener);

    Preconditions.checkArgument(
        !distBuildConfig.getPerformRuleKeyConsistencyCheck()
            || distBuildConfig.getLogMaterializationEnabled(),
        "Log materialization must be enabled to perform rule key consistency check.");

    if (distributedBuildStateFile == null
        && distBuildConfig.getBuildMode().equals(BuildMode.DISTRIBUTED_BUILD_WITH_LOCAL_COORDINATOR)
        && !distBuildConfig.getMinionQueue().isPresent()) {
      throw new HumanReadableException(
          "Stampede Minion Queue name must be specified to use Local Coordinator Mode.");
    }

    BuildEvent.DistBuildStarted started = BuildEvent.distBuildStarted();
    params.getBuckEventBus().post(started);
    if (!autoDistBuild) {
      // Enable Stampede console now, but only if it's an explicit stampede build.
      params.getBuckEventBus().post(new DistBuildSuperConsoleEvent());
    }

    LOG.info("Starting async file hash computation and job state serialization.");
    RemoteCommand remoteCommand =
        distBuildConfig.getLocalBuildMode() == DistLocalBuildMode.RULE_KEY_DIVERGENCE_CHECK
            ? RemoteCommand.RULE_KEY_DIVERGENCE_CHECK
            : RemoteCommand.BUILD;
    AsyncJobStateAndCells stateAndCells =
        computeDistBuildState(
            params,
            graphsAndBuildTargets,
            executorService,
            Optional.of(distBuildClientStats),
            remoteCommand);
    ListenableFuture<BuildJobState> asyncJobState = stateAndCells.asyncJobState;
    DistBuildCellIndexer distBuildCellIndexer = stateAndCells.distBuildCellIndexer;

    if (distributedBuildStateFile != null) {
      BuildJobState jobState;
      try {
        jobState = asyncJobState.get();
      } catch (ExecutionException e) {
        throw new RuntimeException("Failed to compute DistBuildState.", e);
      }

      // Read all files inline if we're dumping state to a file.
      for (BuildJobStateFileHashes cell : jobState.getFileHashes()) {
        ProjectFilesystem cellFilesystem =
            Preconditions.checkNotNull(
                distBuildCellIndexer.getLocalFilesystemsByCellIndex().get(cell.getCellIndex()));
        for (BuildJobStateFileHashEntry entry : cell.getEntries()) {
          cellFilesystem
              .readFileIfItExists(cellFilesystem.resolve(entry.getPath().getPath()))
              .ifPresent(contents -> entry.setContents(contents.getBytes()));
        }
      }

      Path stateDumpPath = Paths.get(distributedBuildStateFile);
      BuildJobStateSerializer.serialize(jobState, filesystem.newFileOutputStream(stateDumpPath));
      return ExitCode.SUCCESS;
    }

    BuckVersion buckVersion = getBuckVersion();
    Preconditions.checkArgument(params.getInvocationInfo().isPresent());

    distBuildClientStats.setIsLocalFallbackBuildEnabled(
        distBuildConfig.isSlowLocalBuildFallbackModeEnabled());

    try (DistBuildService distBuildService = DistBuildFactory.newDistBuildService(params);
        RemoteBuildRuleSynchronizer remoteBuildRuleSynchronizer =
            new RemoteBuildRuleSynchronizer(
                params.getClock(),
                params.getScheduledExecutor(),
                distBuildConfig.getCacheSynchronizationFirstBackoffMillis(),
                distBuildConfig.getCacheSynchronizationMaxTotalBackoffMillis())) {
      ListeningExecutorService stampedeControllerExecutor =
          createStampedeControllerExecutorService(distBuildConfig.getControllerMaxThreadCount());

      ListeningExecutorService stampedeLocalBuildExecutor =
          createStampedeLocalBuildExecutorService();

      LogStateTracker distBuildLogStateTracker =
          DistBuildFactory.newDistBuildLogStateTracker(
              params.getInvocationInfo().get().getLogDirectoryPath(), filesystem, distBuildService);

      DistBuildControllerArgs.Builder distBuildControllerArgsBuilder =
          DistBuildControllerArgs.builder()
              .setBuilderExecutorArgs(params.createBuilderArgs())
              .setBuckEventBus(params.getBuckEventBus())
              .setDistBuildStartedEvent(started)
              .setTopLevelTargets(graphsAndBuildTargets.getBuildTargets())
              .setBuildGraphs(graphsAndBuildTargets.getGraphs())
              .setCachingBuildEngineDelegate(
                  Optional.of(new LocalCachingBuildEngineDelegate(params.getFileHashCache())))
              .setAsyncJobState(asyncJobState)
              .setDistBuildCellIndexer(distBuildCellIndexer)
              .setDistBuildService(distBuildService)
              .setDistBuildLogStateTracker(distBuildLogStateTracker)
              .setBuckVersion(buckVersion)
              .setDistBuildClientStats(distBuildClientStats)
              .setScheduler(params.getScheduledExecutor())
              .setMaxTimeoutWaitingForLogsMillis(
                  distBuildConfig.getMaxWaitForRemoteLogsToBeAvailableMillis())
              .setLogMaterializationEnabled(distBuildConfig.getLogMaterializationEnabled())
              .setBuildLabel(distBuildConfig.getBuildLabel());

      LocalBuildExecutorInvoker localBuildExecutorInvoker =
          new LocalBuildExecutorInvoker() {
            @Override
            public void initLocalBuild(
                boolean isDownloadHeavyBuild,
                RemoteBuildRuleCompletionWaiter remoteBuildRuleCompletionWaiter) {
              BuildCommand.this.initLocalBuild(
                  params,
                  graphsAndBuildTargets,
                  executorService,
                  Optional.empty(),
                  remoteBuildRuleCompletionWaiter,
                  isDownloadHeavyBuild,
                  ruleKeyCacheScope);
            }

            @Override
            public ExitCode executeLocalBuild(
                boolean isDownloadHeavyBuild,
                RemoteBuildRuleCompletionWaiter remoteBuildRuleCompletionWaiter,
                CountDownLatch initializeBuildLatch,
                AtomicReference<Build> buildReference)
                throws IOException, InterruptedException {
              return BuildCommand.this.executeLocalBuild(
                  params,
                  graphsAndBuildTargets,
                  executorService,
                  Optional.empty(),
                  remoteBuildRuleCompletionWaiter,
                  isDownloadHeavyBuild,
                  Optional.of(initializeBuildLatch),
                  ruleKeyCacheScope,
                  buildReference);
            }
          };

      DistBuildControllerInvocationArgs distBuildControllerInvocationArgs =
          DistBuildControllerInvocationArgs.builder()
              .setExecutorService(stampedeControllerExecutor)
              .setProjectFilesystem(filesystem)
              .setFileHashCache(fileHashCache)
              .setInvocationInfo(params.getInvocationInfo().get())
              .setBuildMode(distBuildConfig.getBuildMode())
              .setDistLocalBuildMode(distBuildConfig.getLocalBuildMode())
              .setMinionRequirements(distBuildConfig.getMinionRequirements())
              .setRepository(distBuildConfig.getRepository())
              .setTenantId(distBuildConfig.getTenantId())
              .setRuleKeyCalculatorFuture(localRuleKeyCalculator)
              .build();

      // TODO(alisdair): ensure minion build status recorded even if local build finishes first.
      boolean waitForDistBuildThreadToFinishGracefully =
          distBuildConfig.getLogMaterializationEnabled();
      long distributedBuildThreadKillTimeoutSeconds =
          distBuildConfig.getDistributedBuildThreadKillTimeoutSeconds();

      StampedeBuildClient stampedeBuildClient =
          new StampedeBuildClient(
              params.getBuckEventBus(),
              stampedeLocalBuildExecutor,
              stampedeControllerExecutor,
              distBuildService,
              started,
              localBuildExecutorInvoker,
              distBuildControllerArgsBuilder,
              distBuildControllerInvocationArgs,
              distBuildClientStats,
              waitForDistBuildThreadToFinishGracefully,
              distributedBuildThreadKillTimeoutSeconds,
              autoDistBuildMessage,
              remoteBuildRuleSynchronizer);

      distBuildClientStats.startTimer(PERFORM_LOCAL_BUILD);

      // Perform either a single phase build that waits for all remote artifacts before proceeding,
      // or a two stage build where local build first races against remote, and depending on
      // progress either completes first or falls back to build that waits for remote artifacts.
      Optional<ExitCode> localExitCodeOption =
          stampedeBuildClient.build(
              distBuildConfig.getLocalBuildMode(),
              distBuildConfig.isSlowLocalBuildFallbackModeEnabled());

      ExitCode localExitCode = localExitCodeOption.orElse(ExitCode.FATAL_GENERIC);

      // All local/distributed build steps are now finished.
      StampedeId stampedeId = stampedeBuildClient.getStampedeId();
      DistributedExitCode distributedBuildExitCode = stampedeBuildClient.getDistBuildExitCode();
      distBuildClientStats.setStampedeId(stampedeId.getId());
      distBuildClientStats.setDistributedBuildExitCode(distributedBuildExitCode.getCode());

      // Set local build stats
      distBuildClientStats.setPerformedLocalBuild(true);
      distBuildClientStats.stopTimer(PERFORM_LOCAL_BUILD);

      distBuildClientStats.setLocalBuildExitCode(localExitCode.getCode());
      // If local build finished before hashing was complete, it's important to cancel
      // related Futures to avoid this operation blocking forever.
      asyncJobState.cancel(true);

      // stampedeControllerExecutor is now redundant. Kill it as soon as possible.
      killExecutor(
          stampedeControllerExecutor,
          ("Stampede controller executor service still running after build finished"
              + " and timeout elapsed. Terminating.."));

      killExecutor(
          stampedeLocalBuildExecutor,
          ("Stampede local build executor service still running after build finished"
              + " and timeout elapsed. Terminating.."));

      ExitCode finalExitCode;
      DistLocalBuildMode distLocalBuildMode =
          distBuildControllerInvocationArgs.getDistLocalBuildMode();
      if (distLocalBuildMode.equals(DistLocalBuildMode.FIRE_AND_FORGET)
          || distLocalBuildMode.equals(DistLocalBuildMode.RULE_KEY_DIVERGENCE_CHECK)) {
        finalExitCode = localExitCode;
      } else {
        finalExitCode =
            performPostBuild(
                params,
                distBuildConfig,
                filesystem,
                distBuildClientStats,
                stampedeId,
                distributedBuildExitCode,
                localExitCode,
                distBuildService,
                distBuildLogStateTracker);
      }

      // If no local fallback, and there was a stampede infrastructure failure,
      // then return corresponding exit code
      if (finalExitCode != ExitCode.SUCCESS
          && !distBuildConfig.isSlowLocalBuildFallbackModeEnabled()
          && DistributedExitCode.wasStampedeInfraFailure(distributedBuildExitCode)) {
        finalExitCode = ExitCode.STAMPEDE_INFRA_ERROR;
      }

      return finalExitCode;
    }
  }

  private ExitCode performPostBuild(
      CommandRunnerParams params,
      DistBuildConfig distBuildConfig,
      ProjectFilesystem filesystem,
      ClientStatsTracker distBuildClientStats,
      StampedeId stampedeId,
      DistributedExitCode distributedBuildExitCode,
      ExitCode localExitCode,
      DistBuildService distBuildService,
      LogStateTracker distBuildLogStateTracker)
      throws IOException {
    // Publish details about all default rule keys that were cache misses.
    // A non-zero value suggests a problem that needs investigating.
    if (distBuildConfig.isCacheMissAnalysisEnabled()) {
      performCacheMissAnalysis(params, distBuildConfig, distBuildService);
    }

    boolean ruleKeyConsistencyChecksPassedOrSkipped =
        performStampedePostBuildAnalysisAndRuleKeyConsistencyChecks(
            params,
            distBuildConfig,
            filesystem,
            distBuildClientStats,
            stampedeId,
            distributedBuildExitCode,
            localExitCode,
            distBuildLogStateTracker);

    ExitCode finalExitCode = localExitCode;
    if (!ruleKeyConsistencyChecksPassedOrSkipped) {
      finalExitCode = ExitCode.BUILD_ERROR;
    }

    // Post distributed build phase starts POST_DISTRIBUTED_BUILD_LOCAL_STEPS counter internally.
    if (distributedBuildExitCode == DistributedExitCode.SUCCESSFUL) {
      distBuildClientStats.stopTimer(POST_DISTRIBUTED_BUILD_LOCAL_STEPS);
    }

    return finalExitCode;
  }

  private void performCacheMissAnalysis(
      CommandRunnerParams params,
      DistBuildConfig distBuildConfig,
      DistBuildService distBuildService) {
    try {
      Set<String> cacheMissRequestKeys =
          distBuildClientEventListener.getDefaultCacheMissRequestKeys();
      ArtifactCacheBuckConfig artifactCacheBuckConfig =
          ArtifactCacheBuckConfig.of(distBuildConfig.getBuckConfig());

      LOG.info(
          String.format(
              "Fetching rule key logs for [%d] cache misses", cacheMissRequestKeys.size()));
      if (cacheMissRequestKeys.size() > 0) {
        // TODO(alisdair): requests should be batched for high key counts.
        List<RuleKeyLogEntry> ruleKeyLogs =
            distBuildService.fetchRuleKeyLogs(
                cacheMissRequestKeys,
                artifactCacheBuckConfig.getRepository(),
                artifactCacheBuckConfig.getScheduleType(),
                true /* distributedBuildModeEnabled */);
        params
            .getBuckEventBus()
            .post(distBuildClientEventListener.createDistBuildClientCacheResultsEvent(ruleKeyLogs));
      }
      LOG.info(
          String.format(
              "Fetched rule key logs for [%d] cache misses", cacheMissRequestKeys.size()));

    } catch (Exception ex) {
      LOG.error("Failed to publish distributed build client cache request event", ex);
    }
  }

  private void killExecutor(
      ListeningExecutorService stampedeControllerExecutor, String failureWarning)
      throws InterruptedException {
    stampedeControllerExecutor.shutdown();
    if (!stampedeControllerExecutor.awaitTermination(
        STAMPEDE_EXECUTOR_SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
      LOG.warn(failureWarning);
      stampedeControllerExecutor.shutdownNow();
    }
  }

  private boolean performStampedePostBuildAnalysisAndRuleKeyConsistencyChecks(
      CommandRunnerParams params,
      DistBuildConfig distBuildConfig,
      ProjectFilesystem filesystem,
      ClientStatsTracker distBuildClientStats,
      StampedeId stampedeId,
      DistributedExitCode distributedBuildExitCode,
      ExitCode localBuildExitCode,
      LogStateTracker distBuildLogStateTracker)
      throws IOException {
    // If we are pulling down remote logs, and the distributed build finished successfully,
    // then perform analysis
    if (distBuildConfig.getLogMaterializationEnabled()
        && distributedBuildExitCode == DistributedExitCode.SUCCESSFUL
        && localBuildExitCode == ExitCode.SUCCESS) {
      distBuildClientStats.startTimer(POST_BUILD_ANALYSIS);
      DistBuildPostBuildAnalysis postBuildAnalysis =
          new DistBuildPostBuildAnalysis(
              params.getInvocationInfo().get().getBuildId(),
              stampedeId,
              filesystem.resolve(params.getInvocationInfo().get().getLogDirectoryPath()),
              distBuildLogStateTracker.getBuildSlaveLogsMaterializer().getMaterializedRunIds(),
              DistBuildCommand.class.getSimpleName().toLowerCase());

      LOG.info("Created DistBuildPostBuildAnalysis");
      AnalysisResults results = postBuildAnalysis.runAnalysis();
      Path analysisSummaryFile = postBuildAnalysis.dumpResultsToLogFile(results);

      distBuildClientStats.stopTimer(POST_BUILD_ANALYSIS);

      LOG.info(String.format("Dumped DistBuildPostBuildAnalysis to [%s]", analysisSummaryFile));
      Path relativePathToSummaryFile = filesystem.getRootPath().relativize(analysisSummaryFile);
      params
          .getBuckEventBus()
          .post(
              ConsoleEvent.warning(
                  "Details of distributed build analysis: %s",
                  relativePathToSummaryFile.toString()));

      LOG.info(
          "Number of mismatching default rule keys: " + results.numMismatchingDefaultRuleKeys());
      if (distBuildConfig.getPerformRuleKeyConsistencyCheck()
          && results.numMismatchingDefaultRuleKeys() > 0) {
        params
            .getBuckEventBus()
            .post(
                ConsoleEvent.severe(
                    "*** [%d] default rule keys mismatched between client and server. *** \nMismatching rule keys:",
                    results.numMismatchingDefaultRuleKeys()));

        for (RuleKeyNameAndType ruleKeyNameAndType :
            postBuildAnalysis.getMismatchingDefaultRuleKeys(results)) {
          params
              .getBuckEventBus()
              .post(
                  ConsoleEvent.severe(
                      "MISMATCHING RULE: %s [%s]",
                      ruleKeyNameAndType.getRuleName(), ruleKeyNameAndType.getRuleType()));
        }

        return false; // Rule keys were not consistent
      }
    }

    return true; // Rule keys were consistent, or test was skipped.
  }

  private BuckVersion getBuckVersion() throws IOException {
    if (buckBinary == null) {
      String gitHash = System.getProperty(BUCK_GIT_COMMIT_KEY, null);
      if (gitHash == null) {
        throw new CommandLineException(
            String.format(
                "Property [%s] is not set and the command line flag [%s] was not passed.",
                BUCK_GIT_COMMIT_KEY, BUCK_BINARY_STRING_ARG));
      }

      return BuckVersionUtil.createFromGitHash(gitHash);
    }

    Path binaryPath = Paths.get(buckBinary);
    if (!Files.isRegularFile(binaryPath)) {
      throw new CommandLineException(
          String.format(
              "Buck binary [%s] passed under flag [%s] does not exist.",
              binaryPath, BUCK_BINARY_STRING_ARG));
    }

    return BuckVersionUtil.createFromLocalBinary(binaryPath);
  }

  private void showOutputs(
      CommandRunnerParams params,
      GraphsAndBuildTargets graphsAndBuildTargets,
      RuleKeyCacheScope<RuleKey> ruleKeyCacheScope)
      throws IOException {
    TreeMap<String, String> sortedJsonOutputs = new TreeMap<String, String>();
    Optional<DefaultRuleKeyFactory> ruleKeyFactory = Optional.empty();
    ActionGraphBuilder graphBuilder =
        graphsAndBuildTargets.getGraphs().getActionGraphAndBuilder().getActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    if (showRuleKey) {
      RuleKeyFieldLoader fieldLoader = new RuleKeyFieldLoader(params.getRuleKeyConfiguration());
      ruleKeyFactory =
          Optional.of(
              new DefaultRuleKeyFactory(
                  fieldLoader,
                  params.getFileHashCache(),
                  pathResolver,
                  ruleFinder,
                  ruleKeyCacheScope.getCache(),
                  Optional.empty()));
    }
    for (BuildTarget buildTarget : graphsAndBuildTargets.getBuildTargets()) {
      BuildRule rule = graphBuilder.requireRule(buildTarget);
      Optional<Path> outputPath =
          TargetsCommand.getUserFacingOutputPath(
                  pathResolver, rule, params.getBuckConfig().getBuckOutCompatLink())
              .map(
                  path ->
                      showFullOutput || showFullJsonOutput
                          ? path
                          : params.getCell().getFilesystem().relativize(path));

      params.getConsole().getStdOut().flush();
      if (showJsonOutput || showFullJsonOutput) {
        sortedJsonOutputs.put(
            rule.getFullyQualifiedName(), outputPath.map(Object::toString).orElse(""));
      } else {
        params
            .getConsole()
            .getStdOut()
            .printf(
                "%s%s%s\n",
                rule.getFullyQualifiedName(),
                showRuleKey ? " " + ruleKeyFactory.get().build(rule) : "",
                showOutput || showFullOutput
                    ? " " + outputPath.map(Object::toString).orElse("")
                    : "");
      }
    }

    if (showJsonOutput || showFullJsonOutput) {
      // Print the build rule information as JSON.
      StringWriter stringWriter = new StringWriter();
      ObjectMappers.WRITER.withDefaultPrettyPrinter().writeValue(stringWriter, sortedJsonOutputs);
      String output = stringWriter.getBuffer().toString();
      params.getConsole().getStdOut().println(output);
    }
  }

  private TargetGraphAndBuildTargets createUnversionedTargetGraph(
      CommandRunnerParams params, ListeningExecutorService executor)
      throws IOException, InterruptedException, ActionGraphCreationException {
    // Parse the build files to create a ActionGraph.
    ParserConfig parserConfig = params.getBuckConfig().getView(ParserConfig.class);
    try {
      return params
          .getParser()
          .buildTargetGraphForTargetNodeSpecs(
              params.getCell(),
              getEnableParserProfiling(),
              executor,
              parseArgumentsAsTargetNodeSpecs(
                  params.getCell().getCellPathResolver(), params.getBuckConfig(), getArguments()),
              parserConfig.getDefaultFlavorsMode());
    } catch (BuildTargetException e) {
      throw new ActionGraphCreationException(MoreExceptions.getHumanReadableOrLocalizedMessage(e));
    }
  }

  private static ActionGraphAndBuilder createActionGraphAndResolver(
      CommandRunnerParams params,
      TargetGraphAndBuildTargets targetGraphAndBuildTargets,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger) {
    ActionGraphAndBuilder actionGraphAndBuilder =
        params
            .getActionGraphProvider()
            .getActionGraph(
                new DefaultTargetNodeToBuildRuleTransformer(),
                targetGraphAndBuildTargets.getTargetGraph(),
                ruleKeyLogger);
    return actionGraphAndBuilder;
  }

  private static ImmutableSet<BuildTarget> getBuildTargets(
      CommandRunnerParams params,
      ActionGraphAndBuilder actionGraphAndBuilder,
      TargetGraphAndBuildTargets targetGraph,
      @Nullable String justBuildTarget)
      throws ActionGraphCreationException {
    ImmutableSet<BuildTarget> buildTargets = targetGraph.getBuildTargets();
    if (justBuildTarget == null) {
      return buildTargets;
    }

    // If the user specified an explicit build target, use that.
    BuildTarget explicitTarget =
        BuildTargetParser.INSTANCE.parse(
            justBuildTarget,
            BuildTargetPatternParser.fullyQualified(),
            params.getCell().getCellPathResolver());
    Iterable<BuildRule> actionGraphRules =
        Preconditions.checkNotNull(actionGraphAndBuilder.getActionGraph().getNodes());
    ImmutableSet<BuildTarget> actionGraphTargets =
        ImmutableSet.copyOf(Iterables.transform(actionGraphRules, BuildRule::getBuildTarget));
    if (!actionGraphTargets.contains(explicitTarget)) {
      throw new ActionGraphCreationException(
          "Targets specified via `--just-build` must be a subset of action graph.");
    }
    return ImmutableSet.of(explicitTarget);
  }

  /** Initializes localRuleKeyCalculator (for use in rule key divergence checker) */
  protected void initLocalBuild(
      CommandRunnerParams params,
      GraphsAndBuildTargets graphsAndBuildTargets,
      WeightedListeningExecutorService executor,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger,
      RemoteBuildRuleCompletionWaiter remoteBuildRuleCompletionWaiter,
      boolean isDownloadHeavyBuild,
      RuleKeyCacheScope<RuleKey> ruleKeyCacheScope) {
    ActionGraphAndBuilder actionGraphAndBuilder =
        graphsAndBuildTargets.getGraphs().getActionGraphAndBuilder();
    LocalBuildExecutor builder =
        new LocalBuildExecutor(
            params.createBuilderArgs(),
            getExecutionContext(),
            actionGraphAndBuilder,
            new LocalCachingBuildEngineDelegate(params.getFileHashCache()),
            executor,
            isKeepGoing(),
            isUsingDistributedBuild(),
            isDownloadHeavyBuild,
            ruleKeyCacheScope,
            getBuildEngineMode(),
            ruleKeyLogger,
            remoteBuildRuleCompletionWaiter);
    localRuleKeyCalculator.set(builder.getCachingBuildEngine().getRuleKeyCalculator());
    builder.shutdown();
  }

  protected ExitCode executeLocalBuild(
      CommandRunnerParams params,
      GraphsAndBuildTargets graphsAndBuildTargets,
      WeightedListeningExecutorService executor,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger,
      RemoteBuildRuleCompletionWaiter remoteBuildRuleCompletionWaiter,
      boolean isDownloadHeavyBuild,
      Optional<CountDownLatch> initializeBuildLatch,
      RuleKeyCacheScope<RuleKey> ruleKeyCacheScope,
      AtomicReference<Build> buildReference)
      throws IOException, InterruptedException {

    ActionGraphAndBuilder actionGraphAndBuilder =
        graphsAndBuildTargets.getGraphs().getActionGraphAndBuilder();
    LocalBuildExecutor builder =
        new LocalBuildExecutor(
            params.createBuilderArgs(),
            getExecutionContext(),
            actionGraphAndBuilder,
            new LocalCachingBuildEngineDelegate(params.getFileHashCache()),
            executor,
            isKeepGoing(),
            isUsingDistributedBuild(),
            isDownloadHeavyBuild,
            ruleKeyCacheScope,
            getBuildEngineMode(),
            ruleKeyLogger,
            remoteBuildRuleCompletionWaiter);
    buildReference.set(builder.getBuild());
    // TODO(alisdair): ensure that all Stampede local builds re-use same calculator
    localRuleKeyCalculator.set(builder.getCachingBuildEngine().getRuleKeyCalculator());

    if (initializeBuildLatch.isPresent()) {
      // Signal to other threads that lastBuild has now been set.
      initializeBuildLatch.get().countDown();
    }

    List<String> targetStrings =
        FluentIterable.from(graphsAndBuildTargets.getBuildTargets())
            .append(getAdditionalTargetsToBuild(graphsAndBuildTargets))
            .transform(target -> target.getFullyQualifiedName())
            .toList();
    ExitCode code =
        builder.buildLocallyAndReturnExitCode(
            targetStrings, getPathToBuildReport(params.getBuckConfig()));
    builder.shutdown();
    return code;
  }

  RuleKeyCacheScope<RuleKey> getDefaultRuleKeyCacheScope(
      CommandRunnerParams params, ActionGraphAndBuilder actionGraphAndBuilder) {
    return getDefaultRuleKeyCacheScope(
        params,
        new RuleKeyCacheRecycler.SettingsAffectingCache(
            params.getBuckConfig().getKeySeed(), actionGraphAndBuilder.getActionGraph()));
  }

  @Override
  protected ExecutionContext.Builder getExecutionContextBuilder(CommandRunnerParams params) {
    return super.getExecutionContextBuilder(params)
        .setTargetDevice(Optional.empty())
        .setCodeCoverageEnabled(isCodeCoverageEnabled())
        .setDebugEnabled(isDebugEnabled())
        .setShouldReportAbsolutePaths(shouldReportAbsolutePaths());
  }

  @SuppressWarnings("unused")
  protected Iterable<BuildTarget> getAdditionalTargetsToBuild(
      GraphsAndBuildTargets graphsAndBuildTargets) {
    return ImmutableList.of();
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public boolean isSourceControlStatsGatheringEnabled() {
    return true;
  }

  Build getBuild() {
    return Preconditions.checkNotNull(lastBuild.get());
  }

  @Override
  public String getShortDescription() {
    return "builds the specified target";
  }

  @Override
  public Iterable<BuckEventListener> getEventListeners(
      Map<ExecutorPool, ListeningExecutorService> executorPool,
      ScheduledExecutorService scheduledExecutorService) {
    ImmutableList.Builder<BuckEventListener> listeners = ImmutableList.builder();
    if (isUsingDistributedBuild()) {
      distBuildClientEventListener = new DistBuildClientEventListener();
      listeners.add(distBuildClientEventListener);
    }
    return listeners.build();
  }

  private static class AsyncJobStateAndCells {
    final ListenableFuture<BuildJobState> asyncJobState;
    final DistBuildCellIndexer distBuildCellIndexer;

    AsyncJobStateAndCells(
        ListenableFuture<BuildJobState> asyncJobState, DistBuildCellIndexer cellIndexer) {
      this.asyncJobState = asyncJobState;
      this.distBuildCellIndexer = cellIndexer;
    }
  }

  public static class ActionGraphCreationException extends Exception {
    public ActionGraphCreationException(String message) {
      super(message);
    }
  }

  @Override
  public boolean performsBuild() {
    return true;
  }

  @Immutable(builder = false, copy = false)
  interface GraphsAndBuildTargets {
    @Value.Parameter
    ActionAndTargetGraphs getGraphs();

    @Value.Parameter
    ImmutableSet<BuildTarget> getBuildTargets();
  }

  @Immutable(builder = false, copy = false)
  interface BuildRunResult {
    @Value.Parameter
    ExitCode getExitCode();

    @Value.Parameter
    ImmutableSet<BuildTarget> getBuildTargets();
  }
}
