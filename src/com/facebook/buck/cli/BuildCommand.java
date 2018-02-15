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

import com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig;
import com.facebook.buck.cli.output.Mode;
import com.facebook.buck.command.Build;
import com.facebook.buck.command.LocalBuildExecutor;
import com.facebook.buck.command.LocalBuildExecutorInvoker;
import com.facebook.buck.config.BuckConfig;
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
import com.facebook.buck.distributed.RuleKeyNameAndType;
import com.facebook.buck.distributed.build_client.DistBuildControllerArgs;
import com.facebook.buck.distributed.build_client.DistBuildControllerInvocationArgs;
import com.facebook.buck.distributed.build_client.LogStateTracker;
import com.facebook.buck.distributed.build_client.StampedeBuildClient;
import com.facebook.buck.distributed.thrift.BuckVersion;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.distributed.thrift.RuleKeyLogEntry;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.listener.DistBuildClientEventListener;
import com.facebook.buck.io.file.MoreFiles;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.CommandThreadFactory;
import com.facebook.buck.log.Logger;
import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.parser.DefaultParserTargetNodeFactory;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.ParserTargetNodeFactory;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.rules.ActionAndTargetGraphs;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.LocalCachingBuildEngineDelegate;
import com.facebook.buck.rules.NoOpRemoteBuildRuleCompletionWaiter;
import com.facebook.buck.rules.ParallelRuleKeyCalculator;
import com.facebook.buck.rules.RemoteBuildRuleCompletionWaiter;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodeFactory;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.PathTypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.rules.keys.RuleKeyCacheScope;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.util.CloseableMemoizedSupplier;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.versions.VersionException;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
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
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
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
    hidden = true
  )
  private String justBuildTarget = null;

  @Option(
    name = DEEP_LONG_ARG,
    usage =
        "Perform a \"deep\" build, which makes the output of all transitive dependencies"
            + " available.",
    forbids = SHALLOW_LONG_ARG
  )
  private boolean deepBuild = false;

  @Option(
    name = POPULATE_CACHE_LONG_ARG,
    usage =
        "Performs a cache population, which makes the output of all unchanged "
            + "transitive dependencies available (if these outputs are available "
            + "in the remote cache). Does not build changed or unavailable dependencies locally.",
    forbids = {SHALLOW_LONG_ARG, DEEP_LONG_ARG}
  )
  private boolean populateCacheOnly = false;

  @Option(
    name = SHALLOW_LONG_ARG,
    usage =
        "Perform a \"shallow\" build, which only makes the output of all explicitly listed"
            + " targets available.",
    forbids = DEEP_LONG_ARG
  )
  private boolean shallowBuild = false;

  @Option(
    name = REPORT_ABSOLUTE_PATHS,
    usage = "Reports errors using absolute paths to the source files instead of relative paths."
  )
  private boolean shouldReportAbsolutePaths = false;

  @Option(
    name = SHOW_OUTPUT_LONG_ARG,
    usage = "Print the path to the output for each of the built rules relative to the cell."
  )
  private boolean showOutput;

  @Option(name = OUT_LONG_ARG, usage = "Copies the output of the lone build target to this path.")
  @Nullable
  private Path outputPathForSingleBuildTarget;

  @Option(
    name = SHOW_FULL_OUTPUT_LONG_ARG,
    usage = "Print the absolute path to the output for each of the built rules."
  )
  private boolean showFullOutput;

  @Option(name = SHOW_JSON_OUTPUT_LONG_ARG, usage = "Show output in JSON format.")
  private boolean showJsonOutput;

  @Option(name = SHOW_FULL_JSON_OUTPUT_LONG_ARG, usage = "Show full output in JSON format.")
  private boolean showFullJsonOutput;

  @Option(name = SHOW_RULEKEY_LONG_ARG, usage = "Print the rulekey for each of the built rules.")
  private boolean showRuleKey;

  @Option(
    name = DISTRIBUTED_LONG_ARG,
    usage = "Whether to run in distributed build mode. (experimental)",
    hidden = true
  )
  private boolean useDistributedBuild = false;

  @Nullable
  @Option(
    name = DistBuildRunCommand.BUILD_STATE_FILE_ARG_NAME,
    usage = DistBuildRunCommand.BUILD_STATE_FILE_ARG_USAGE,
    hidden = true
  )
  private String distributedBuildStateFile = null;

  @Nullable
  @Option(
    name = BUCK_BINARY_STRING_ARG,
    usage = "Buck binary to use on a distributed build instead of the current git version.",
    hidden = true
  )
  private String buckBinary = null;

  @Nullable
  @Option(
    name = RULEKEY_LOG_PATH_LONG_ARG,
    usage = "If set, log a binary representation of rulekeys to this file."
  )
  private String ruleKeyLogPath = null;

  @Argument private List<String> arguments = new ArrayList<>();

  private boolean buildTargetsHaveBeenCalculated;

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

  protected Mode getOutputMode() {
    if (this.showFullOutput) {
      return Mode.FULL;
    } else if (this.showOutput) {
      return Mode.SIMPLE;
    } else {
      return Mode.NONE;
    }
  }

  public BuildCommand() {
    this(ImmutableList.of());
  }

  public BuildCommand(List<String> arguments) {
    this.arguments.addAll(arguments);
  }

  public Optional<CachingBuildEngine.BuildMode> getBuildEngineMode() {
    Optional<CachingBuildEngine.BuildMode> mode = Optional.empty();
    if (deepBuild) {
      mode = Optional.of(CachingBuildEngine.BuildMode.DEEP);
    }
    if (populateCacheOnly) {
      mode = Optional.of(CachingBuildEngine.BuildMode.POPULATE_FROM_REMOTE_CACHE);
    }
    if (shallowBuild) {
      mode = Optional.of(CachingBuildEngine.BuildMode.SHALLOW);
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

  public boolean isUseDistributedBuild() {
    return useDistributedBuild;
  }

  public void setUseDistributedBuild(boolean useDistributedBuild) {
    this.useDistributedBuild = useDistributedBuild;
  }

  /** @return an absolute path or {@link Optional#empty()}. */
  public Optional<Path> getPathToBuildReport(BuckConfig buckConfig) {
    return Optional.ofNullable(
        buckConfig.resolvePathThatMayBeOutsideTheProjectFilesystem(buildReport));
  }

  private final AtomicReference<Build> lastBuild = new AtomicReference<>(null);
  private final SettableFuture<ParallelRuleKeyCalculator<RuleKey>> localRuleKeyCalculator =
      SettableFuture.create();

  private ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of();

  /**
   * Create the serializable {@link BuildJobState} for distributed builds.
   *
   * @param buildTargets - Top level targets.
   * @param params - Client side parameters.
   * @param executor - Executor for async ops.
   * @param poolSupplier - the supplier of an executor for parallel action graph construction
   * @return - New instance of serializable {@link BuildJobState}.
   * @throws InterruptedException
   * @throws IOException
   */
  public static ListenableFuture<BuildJobState> getAsyncDistBuildState(
      List<String> buildTargets,
      CommandRunnerParams params,
      WeightedListeningExecutorService executor,
      CloseableMemoizedSupplier<ForkJoinPool, RuntimeException> poolSupplier)
      throws InterruptedException, IOException {
    BuildCommand buildCommand = new BuildCommand(buildTargets);
    buildCommand.assertArguments(params);

    ActionAndTargetGraphs graphs = null;
    try {
      graphs = buildCommand.createGraphs(params, executor, Optional.empty(), poolSupplier);
    } catch (ActionGraphCreationException e) {
      throw BuildFileParseException.createForUnknownParseError(e.getMessage());
    }

    return buildCommand.computeDistBuildState(params, graphs, executor, Optional.empty())
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
                params.getEnvironment()); ) {
      prehook.startPrehookScript();
      return run(params, pool, ImmutableSet.of());
    }
  }

  /** @throw CommandLineException if arguments provided are incorrect */
  protected void assertArguments(CommandRunnerParams params) {
    if (!getArguments().isEmpty()) {
      return;
    }
    String message = "Must specify at least one build target.";
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

  protected ExitCode run(
      CommandRunnerParams params,
      CommandThreadManager commandThreadManager,
      ImmutableSet<String> additionalTargets)
      throws IOException, InterruptedException {
    if (!additionalTargets.isEmpty()) {
      this.arguments.addAll(additionalTargets);
    }
    BuildEvent.Started started = postBuildStartedEvent(params);
    ExitCode exitCode = ExitCode.SUCCESS;
    try (CloseableMemoizedSupplier<ForkJoinPool, RuntimeException> poolSupplier =
        getForkJoinPoolSupplier(params.getBuckConfig())) {
      exitCode = executeBuildAndProcessResult(params, commandThreadManager, poolSupplier);
    } catch (ActionGraphCreationException e) {
      params.getConsole().printBuildFailure(e.getMessage());
      exitCode = ExitCode.PARSE_ERROR;
    } finally {
      params.getBuckEventBus().post(BuildEvent.finished(started, exitCode));
    }

    return exitCode;
  }

  private BuildEvent.Started postBuildStartedEvent(CommandRunnerParams params) {
    // Post the build started event, setting it to the Parser recorded start time if appropriate.
    BuildEvent.Started started = BuildEvent.started(getArguments());
    if (params.getParser().getParseStartTime().isPresent()) {
      params.getBuckEventBus().post(started, params.getParser().getParseStartTime().get());
    } else {
      params.getBuckEventBus().post(started);
    }
    return started;
  }

  private ActionAndTargetGraphs createGraphs(
      CommandRunnerParams params,
      ListeningExecutorService executorService,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger,
      CloseableMemoizedSupplier<ForkJoinPool, RuntimeException> poolSupplier)
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
    ActionGraphAndResolver actionGraph =
        createActionGraphAndResolver(params, targetGraphForLocalBuild, ruleKeyLogger, poolSupplier);
    return ActionAndTargetGraphs.builder()
        .setUnversionedTargetGraph(unversionedTargetGraph)
        .setVersionedTargetGraph(versionedTargetGraph)
        .setActionGraphAndResolver(actionGraph)
        .build();
  }

  private void checkSingleBuildTargetSpecifiedForOutBuildMode(
      TargetGraphAndBuildTargets targetGraphAndBuildTargets) throws ActionGraphCreationException {
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

  private ExitCode executeBuildAndProcessResult(
      CommandRunnerParams params,
      CommandThreadManager commandThreadManager,
      CloseableMemoizedSupplier<ForkJoinPool, RuntimeException> poolSupplier)
      throws IOException, InterruptedException, ActionGraphCreationException {
    ExitCode exitCode = ExitCode.SUCCESS;
    final ActionAndTargetGraphs graphs;
    if (useDistributedBuild) {
      DistBuildConfig distBuildConfig = new DistBuildConfig(params.getBuckConfig());
      ClientStatsTracker distBuildClientStatsTracker =
          new ClientStatsTracker(distBuildConfig.getBuildLabel());

      distBuildClientStatsTracker.startTimer(LOCAL_PREPARATION);
      distBuildClientStatsTracker.startTimer(LOCAL_GRAPH_CONSTRUCTION);
      graphs =
          createGraphs(
              params,
              commandThreadManager.getListeningExecutorService(),
              Optional.empty(),
              poolSupplier);
      distBuildClientStatsTracker.stopTimer(LOCAL_GRAPH_CONSTRUCTION);

      try (RuleKeyCacheScope<RuleKey> ruleKeyCacheScope =
          getDefaultRuleKeyCacheScope(params, graphs.getActionGraphAndResolver())) {
        try {
          exitCode =
              executeDistBuild(
                  params,
                  distBuildConfig,
                  graphs,
                  commandThreadManager.getWeightedListeningExecutorService(),
                  params.getCell().getFilesystem(),
                  params.getFileHashCache(),
                  distBuildClientStatsTracker,
                  ruleKeyCacheScope);
        } catch (Throwable ex) {
          String stackTrace = Throwables.getStackTraceAsString(ex);
          distBuildClientStatsTracker.setBuckClientErrorMessage(ex.toString() + "\n" + stackTrace);
          distBuildClientStatsTracker.setBuckClientError(true);

          throw ex;
        } finally {
          if (distBuildClientStatsTracker.hasStampedeId()) {
            params
                .getBuckEventBus()
                .post(new DistBuildClientStatsEvent(distBuildClientStatsTracker.generateStats()));
          } else {
            LOG.error(
                "Failed to published DistBuildClientStatsEvent as no Stampede ID was received");
          }
        }
        if (exitCode == ExitCode.SUCCESS) {
          exitCode = processSuccessfulBuild(params, graphs, ruleKeyCacheScope);
        }
      }
    } else {
      try (ThriftRuleKeyLogger ruleKeyLogger = createRuleKeyLogger().orElse(null)) {
        Optional<ThriftRuleKeyLogger> optionalRuleKeyLogger = Optional.ofNullable(ruleKeyLogger);
        graphs =
            createGraphs(
                params,
                commandThreadManager.getListeningExecutorService(),
                optionalRuleKeyLogger,
                poolSupplier);
        try (RuleKeyCacheScope<RuleKey> ruleKeyCacheScope =
            getDefaultRuleKeyCacheScope(params, graphs.getActionGraphAndResolver())) {
          exitCode =
              executeLocalBuild(
                  params,
                  graphs.getActionGraphAndResolver(),
                  commandThreadManager.getWeightedListeningExecutorService(),
                  optionalRuleKeyLogger,
                  new NoOpRemoteBuildRuleCompletionWaiter(),
                  false,
                  Optional.empty(),
                  ruleKeyCacheScope,
                  lastBuild);
          if (exitCode == ExitCode.SUCCESS) {
            exitCode = processSuccessfulBuild(params, graphs, ruleKeyCacheScope);
          }
        }
      }
    }

    return exitCode;
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
      ActionAndTargetGraphs graphs,
      RuleKeyCacheScope<RuleKey> ruleKeyCacheScope)
      throws IOException {
    if (params.getBuckConfig().createBuildOutputSymLinksEnabled()) {
      symLinkBuildResults(params, graphs.getActionGraphAndResolver());
    }
    if (showOutput || showFullOutput || showJsonOutput || showFullJsonOutput || showRuleKey) {
      showOutputs(params, graphs.getActionGraphAndResolver(), ruleKeyCacheScope);
    }
    if (outputPathForSingleBuildTarget != null) {
      BuildTarget loneTarget =
          Iterables.getOnlyElement(graphs.getTargetGraphForLocalBuild().getBuildTargets());
      BuildRule rule = graphs.getActionGraphAndResolver().getResolver().getRule(loneTarget);
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

        ProjectFilesystem projectFilesystem = rule.getProjectFilesystem();
        SourcePathResolver pathResolver =
            DefaultSourcePathResolver.from(
                new SourcePathRuleFinder(graphs.getActionGraphAndResolver().getResolver()));
        projectFilesystem.copyFile(
            pathResolver.getAbsolutePath(output), outputPathForSingleBuildTarget);
      }
    }
    return ExitCode.SUCCESS;
  }

  private void symLinkBuildResults(
      CommandRunnerParams params, ActionGraphAndResolver actionGraphAndResolver)
      throws IOException {
    // Clean up last buck-out/last.
    Path lastOutputDirPath =
        params.getCell().getFilesystem().getBuckPaths().getLastOutputDir().toAbsolutePath();
    MoreFiles.deleteRecursivelyIfExists(lastOutputDirPath);
    Files.createDirectories(lastOutputDirPath);

    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(actionGraphAndResolver.getResolver());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    for (BuildTarget buildTarget : buildTargets) {
      BuildRule rule = actionGraphAndResolver.getResolver().requireRule(buildTarget);
      Optional<Path> outputPath =
          TargetsCommand.getUserFacingOutputPath(
              pathResolver, rule, params.getBuckConfig().getBuckOutCompatLink());
      if (outputPath.isPresent()) {
        Path absolutePath = outputPath.get();
        Path destPath = lastOutputDirPath.relativize(absolutePath);
        Path linkPath = lastOutputDirPath.resolve(absolutePath.getFileName());
        // Don't overwrite existing symlink in case there are duplicate names.
        if (!Files.exists(linkPath)) {
          Files.createSymbolicLink(linkPath, destPath);
        }
      }
    }
  }

  private AsyncJobStateAndCells computeDistBuildState(
      final CommandRunnerParams params,
      ActionAndTargetGraphs graphs,
      final ListeningExecutorService executorService,
      Optional<ClientStatsTracker> clientStatsTracker)
      throws IOException, InterruptedException {
    DistBuildCellIndexer cellIndexer = new DistBuildCellIndexer(params.getCell());

    // Compute the file hashes.
    ActionGraphAndResolver actionGraphAndResolver = graphs.getActionGraphAndResolver();
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(actionGraphAndResolver.getResolver());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    clientStatsTracker.ifPresent(tracker -> tracker.startTimer(LOCAL_FILE_HASH_COMPUTATION));
    DistBuildFileHashes distributedBuildFileHashes =
        new DistBuildFileHashes(
            actionGraphAndResolver.getActionGraph(),
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
        graphs.getTargetGraphForDistributedBuild();

    TypeCoercerFactory typeCoercerFactory =
        new DefaultTypeCoercerFactory(PathTypeCoercer.PathExistenceVerificationMode.DO_NOT_VERIFY);
    ParserTargetNodeFactory<TargetNode<?, ?>> parserTargetNodeFactory =
        DefaultParserTargetNodeFactory.createForDistributedBuild(
            new ConstructorArgMarshaller(typeCoercerFactory),
            new TargetNodeFactory(typeCoercerFactory),
            params.getRuleKeyConfiguration());
    DistBuildTargetGraphCodec targetGraphCodec =
        new DistBuildTargetGraphCodec(
            parserTargetNodeFactory,
            input -> {
              return params
                  .getParser()
                  .getRawTargetNode(
                      params.getBuckEventBus(),
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

    return new AsyncJobStateAndCells(
        distributedBuildFileHashes,
        executorService.submit(
            () -> {
              try {
                BuildJobState state =
                    DistBuildState.dump(
                        cellIndexer,
                        distributedBuildFileHashes,
                        targetGraphCodec,
                        targetGraphAndBuildTargets.getTargetGraph(),
                        buildTargets,
                        clientStatsTracker);
                LOG.info("Finished computing serializable distributed build state.");
                return state;
              } catch (InterruptedException ex) {
                LOG.warn(
                    ex,
                    "Failed computing serializable distributed build state as interrupted. Local build probably finished first.");
                Thread.currentThread().interrupt();
                throw ex;
              }
            }),
        cellIndexer);
  }

  private ListeningExecutorService createStampedeControllerExecutorService(int maxThreads) {
    CommandThreadFactory stampedeCommandThreadFactory =
        new CommandThreadFactory("StampedeController");
    return MoreExecutors.listeningDecorator(
        newMultiThreadExecutor(stampedeCommandThreadFactory, maxThreads));
  }

  private ListeningExecutorService createStampedeLocalBuildExecutorService() {
    CommandThreadFactory stampedeCommandThreadFactory =
        new CommandThreadFactory("StampedeLocalBuild");
    return MoreExecutors.listeningDecorator(
        MostExecutors.newSingleThreadExecutor(stampedeCommandThreadFactory));
  }

  private ExitCode executeDistBuild(
      CommandRunnerParams params,
      DistBuildConfig distBuildConfig,
      ActionAndTargetGraphs graphs,
      WeightedListeningExecutorService executorService,
      ProjectFilesystem filesystem,
      FileHashCache fileHashCache,
      ClientStatsTracker distBuildClientStats,
      RuleKeyCacheScope<RuleKey> ruleKeyCacheScope)
      throws IOException, InterruptedException {
    Preconditions.checkNotNull(distBuildClientEventListener);

    Preconditions.checkArgument(
        (distBuildConfig.getPerformRuleKeyConsistencyCheck()
                && distBuildConfig.getLogMaterializationEnabled())
            || !distBuildConfig.getPerformRuleKeyConsistencyCheck(),
        "Log materialization must be enabled to perform rule key consistency check.");

    if (distributedBuildStateFile == null
        && distBuildConfig.getBuildMode().equals(BuildMode.DISTRIBUTED_BUILD_WITH_LOCAL_COORDINATOR)
        && !distBuildConfig.getMinionQueue().isPresent()) {
      throw new HumanReadableException(
          "Stampede Minion Queue name must be specified to use Local Coordinator Mode.");
    }

    BuildEvent.DistBuildStarted started = BuildEvent.distBuildStarted();
    params.getBuckEventBus().post(started);

    LOG.info("Starting async file hash computation and job state serialization.");
    AsyncJobStateAndCells stateAndCells =
        computeDistBuildState(params, graphs, executorService, Optional.of(distBuildClientStats));
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

    try (DistBuildService distBuildService = DistBuildFactory.newDistBuildService(params)) {
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
              .setTopLevelTargets(buildTargets)
              .setBuildGraphs(graphs)
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
            public int executeLocalBuild(
                boolean isDownloadHeavyBuild,
                RemoteBuildRuleCompletionWaiter remoteBuildRuleCompletionWaiter,
                CountDownLatch initializeBuildLatch,
                AtomicReference<Build> buildReference)
                throws IOException, InterruptedException {
              return BuildCommand.this
                  .executeLocalBuild(
                      params,
                      graphs.getActionGraphAndResolver(),
                      executorService,
                      Optional.empty(),
                      remoteBuildRuleCompletionWaiter,
                      isDownloadHeavyBuild,
                      Optional.of(initializeBuildLatch),
                      ruleKeyCacheScope,
                      buildReference)
                  .getCode();
            }
          };

      DistBuildControllerInvocationArgs distBuildControllerInvocationArgs =
          DistBuildControllerInvocationArgs.builder()
              .setExecutorService(stampedeControllerExecutor)
              .setProjectFilesystem(filesystem)
              .setFileHashCache(fileHashCache)
              .setInvocationInfo(params.getInvocationInfo().get())
              .setBuildMode(distBuildConfig.getBuildMode())
              .setNumberOfMinions(distBuildConfig.getNumberOfMinions())
              .setRepository(distBuildConfig.getRepository())
              .setTenantId(distBuildConfig.getTenantId())
              .setRuleKeyCalculatorFuture(localRuleKeyCalculator)
              .build();

      // TODO(alisdair): ensure minion build status recorded even if local build finishes first.
      boolean waitForDistBuildThreadToFinishGracefully =
          distBuildConfig.getLogMaterializationEnabled();

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
              waitForDistBuildThreadToFinishGracefully);

      distBuildClientStats.startTimer(PERFORM_LOCAL_BUILD);

      // Perform either a single phase build that waits for all remote artifacts before proceeding,
      // or a two stage build where local build first races against remote, and depending on
      // progress either completes first or falls back to build that waits for remote artifacts.
      boolean skipRacingBuild =
          distBuildConfig.shouldAlwaysWaitForRemoteBuildBeforeProceedingLocally();
      int localExitCode =
          stampedeBuildClient.build(
              skipRacingBuild, distBuildConfig.isSlowLocalBuildFallbackModeEnabled());

      // All local/distributed build steps are now finished.
      StampedeId stampedeId = stampedeBuildClient.getStampedeId();
      int distributedBuildExitCode = stampedeBuildClient.getDistBuildExitCode();
      distBuildClientStats.setStampedeId(stampedeId.getId());
      distBuildClientStats.setDistributedBuildExitCode(distributedBuildExitCode);

      // Set local build stats
      distBuildClientStats.setPerformedLocalBuild(true);
      distBuildClientStats.stopTimer(PERFORM_LOCAL_BUILD);
      distBuildClientStats.setLocalBuildExitCode(localExitCode);

      // If local build finished before hashing was complete, it's important to cancel
      // related Futures to avoid this operation blocking forever.
      stateAndCells.cancel();

      // stampedeControllerExecutor is now redundant. Kill it as soon as possible.
      killExecutor(
          stampedeControllerExecutor,
          ("Stampede controller executor service still running after build finished"
              + " and timeout elapsed. Terminating.."));

      killExecutor(
          stampedeLocalBuildExecutor,
          ("Stampede local build executor service still running after build finished"
              + " and timeout elapsed. Terminating.."));

      // Publish details about all default rule keys that were cache misses.
      // A non-zero value suggests a problem that needs investigating.
      try {
        Set<String> cacheMissRequestKeys =
            distBuildClientEventListener.getDefaultCacheMissRequestKeys();
        ArtifactCacheBuckConfig artifactCacheBuckConfig =
            ArtifactCacheBuckConfig.of(distBuildConfig.getBuckConfig());
        List<RuleKeyLogEntry> ruleKeyLogs =
            distBuildService.fetchRuleKeyLogs(
                cacheMissRequestKeys,
                artifactCacheBuckConfig.getRepository(),
                artifactCacheBuckConfig.getScheduleType(),
                true /* distributedBuildModeEnabled */);
        params
            .getBuckEventBus()
            .post(distBuildClientEventListener.createDistBuildClientCacheResultsEvent(ruleKeyLogs));
      } catch (Exception ex) {
        LOG.error("Failed to publish distributed build client cache request event", ex);
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

      int finalExitCode = localExitCode;
      if (!ruleKeyConsistencyChecksPassedOrSkipped) {
        finalExitCode =
            com.facebook.buck.distributed.ExitCode.RULE_KEY_CONSISTENCY_CHECK_FAILED.getCode();
      }

      // Post distributed build phase starts POST_DISTRIBUTED_BUILD_LOCAL_STEPS counter internally.
      if (distributedBuildExitCode == 0) {
        distBuildClientStats.stopTimer(POST_DISTRIBUTED_BUILD_LOCAL_STEPS);
      }

      if (distBuildClientStats.hasStampedeId()) {
        params
            .getBuckEventBus()
            .post(new DistBuildClientStatsEvent(distBuildClientStats.generateStats()));
      }

      return ExitCode.map(finalExitCode);
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
      int distributedBuildExitCode,
      int localBuildExitCode,
      LogStateTracker distBuildLogStateTracker)
      throws IOException {
    // If we are pulling down remote logs, and the distributed build finished successfully,
    // then perform analysis
    if (distBuildConfig.getLogMaterializationEnabled()
        && distributedBuildExitCode == 0
        && localBuildExitCode == 0) {
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
      ActionGraphAndResolver actionGraphAndResolver,
      RuleKeyCacheScope<RuleKey> ruleKeyCacheScope)
      throws IOException {
    TreeMap<String, String> sortedJsonOutputs = new TreeMap<String, String>();
    Optional<DefaultRuleKeyFactory> ruleKeyFactory = Optional.empty();
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(actionGraphAndResolver.getResolver());
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
    for (BuildTarget buildTarget : buildTargets) {
      BuildRule rule = actionGraphAndResolver.getResolver().requireRule(buildTarget);
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
                showRuleKey ? " " + ruleKeyFactory.get().build(rule).toString() : "",
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
              params.getBuckEventBus(),
              params.getCell(),
              getEnableParserProfiling(),
              executor,
              parseArgumentsAsTargetNodeSpecs(params.getBuckConfig(), getArguments()),
              parserConfig.getDefaultFlavorsMode());
    } catch (BuildTargetException e) {
      throw new ActionGraphCreationException(MoreExceptions.getHumanReadableOrLocalizedMessage(e));
    }
  }

  private ActionGraphAndResolver createActionGraphAndResolver(
      CommandRunnerParams params,
      TargetGraphAndBuildTargets targetGraphAndBuildTargets,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger,
      CloseableMemoizedSupplier<ForkJoinPool, RuntimeException> poolSupplier)
      throws ActionGraphCreationException {
    buildTargets = targetGraphAndBuildTargets.getBuildTargets();
    buildTargetsHaveBeenCalculated = true;
    ActionGraphAndResolver actionGraphAndResolver =
        params
            .getActionGraphCache()
            .getActionGraph(
                params.getBuckEventBus(),
                targetGraphAndBuildTargets.getTargetGraph(),
                params.getBuckConfig(),
                params.getRuleKeyConfiguration(),
                ruleKeyLogger,
                poolSupplier);

    // If the user specified an explicit build target, use that.
    if (justBuildTarget != null) {
      BuildTarget explicitTarget =
          BuildTargetParser.INSTANCE.parse(
              justBuildTarget,
              BuildTargetPatternParser.fullyQualified(),
              params.getCell().getCellPathResolver());
      Iterable<BuildRule> actionGraphRules =
          Preconditions.checkNotNull(actionGraphAndResolver.getActionGraph().getNodes());
      ImmutableSet<BuildTarget> actionGraphTargets =
          ImmutableSet.copyOf(Iterables.transform(actionGraphRules, BuildRule::getBuildTarget));
      if (!actionGraphTargets.contains(explicitTarget)) {
        throw new ActionGraphCreationException(
            "Targets specified via `--just-build` must be a subset of action graph.");
      }
      buildTargets = ImmutableSet.of(explicitTarget);
    }

    return actionGraphAndResolver;
  }

  protected ExitCode executeLocalBuild(
      CommandRunnerParams params,
      ActionGraphAndResolver actionGraphAndResolver,
      WeightedListeningExecutorService executor,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger,
      RemoteBuildRuleCompletionWaiter remoteBuildRuleCompletionWaiter,
      boolean isDownloadHeavyBuild,
      Optional<CountDownLatch> initializeBuildLatch,
      RuleKeyCacheScope<RuleKey> ruleKeyCacheScope,
      AtomicReference<Build> buildReference)
      throws IOException, InterruptedException {

    LocalBuildExecutor builder =
        new LocalBuildExecutor(
            params.createBuilderArgs(),
            getExecutionContext(),
            actionGraphAndResolver,
            new LocalCachingBuildEngineDelegate(params.getFileHashCache()),
            executor,
            isKeepGoing(),
            useDistributedBuild,
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
        FluentIterable.from(buildTargets)
            .append(getAdditionalTargetsToBuild(actionGraphAndResolver.getResolver()))
            .transform(target -> target.getFullyQualifiedName())
            .toList();
    int code =
        builder.buildLocallyAndReturnExitCode(
            targetStrings, getPathToBuildReport(params.getBuckConfig()));
    builder.shutdown();
    return ExitCode.map(code);
  }

  RuleKeyCacheScope<RuleKey> getDefaultRuleKeyCacheScope(
      CommandRunnerParams params, ActionGraphAndResolver actionGraphAndResolver) {
    return getDefaultRuleKeyCacheScope(
        params,
        new RuleKeyCacheRecycler.SettingsAffectingCache(
            params.getBuckConfig().getKeySeed(), actionGraphAndResolver.getActionGraph()));
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
  protected Iterable<BuildTarget> getAdditionalTargetsToBuild(BuildRuleResolver resolver) {
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

  public ImmutableList<BuildTarget> getBuildTargets() {
    Preconditions.checkState(buildTargetsHaveBeenCalculated);
    return ImmutableList.copyOf(buildTargets);
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
    if (useDistributedBuild) {
      distBuildClientEventListener = new DistBuildClientEventListener();
      listeners.add(distBuildClientEventListener);
    }
    return listeners.build();
  }

  private static class AsyncJobStateAndCells {
    final DistBuildFileHashes distributedBuildFileHashes;
    final ListenableFuture<BuildJobState> asyncJobState;
    final DistBuildCellIndexer distBuildCellIndexer;

    AsyncJobStateAndCells(
        DistBuildFileHashes distributedBuildFileHashes,
        ListenableFuture<BuildJobState> asyncJobState,
        DistBuildCellIndexer cellIndexer) {
      this.distributedBuildFileHashes = distributedBuildFileHashes;
      this.asyncJobState = asyncJobState;
      this.distBuildCellIndexer = cellIndexer;
    }

    // Cancels any ongoing Future operations
    protected void cancel() {
      distributedBuildFileHashes.cancel();
      asyncJobState.cancel(true);
    }
  }

  public static class ActionGraphCreationException extends Exception {
    public ActionGraphCreationException(String message) {
      super(message);
    }
  }
}
