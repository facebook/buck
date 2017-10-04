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

import static com.facebook.buck.distributed.DistBuildClientStatsTracker.DistBuildClientStat.*;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.cli.output.Mode;
import com.facebook.buck.command.Build;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.distributed.BuckVersionUtil;
import com.facebook.buck.distributed.BuildJobStateSerializer;
import com.facebook.buck.distributed.DistBuildCellIndexer;
import com.facebook.buck.distributed.DistBuildClientExecutor;
import com.facebook.buck.distributed.DistBuildClientStatsEvent;
import com.facebook.buck.distributed.DistBuildClientStatsTracker;
import com.facebook.buck.distributed.DistBuildConfig;
import com.facebook.buck.distributed.DistBuildFileHashes;
import com.facebook.buck.distributed.DistBuildLogStateTracker;
import com.facebook.buck.distributed.DistBuildPostBuildAnalysis;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildState;
import com.facebook.buck.distributed.DistBuildTargetGraphCodec;
import com.facebook.buck.distributed.thrift.BuckVersion;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.RuleKeyLogEntry;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.listener.DistBuildClientEventListener;
import com.facebook.buck.io.file.MoreFiles;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.parser.DefaultParserTargetNodeFactory;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.ParserTargetNodeFactory;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.CachingBuildEngineBuckConfig;
import com.facebook.buck.rules.CachingBuildEngineDelegate;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.LocalCachingBuildEngineDelegate;
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
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.versions.VersionException;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.util.concurrent.ListeningExecutorService;
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
import java.util.concurrent.ScheduledExecutorService;
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

  private static final String BUCK_GIT_COMMIT_KEY = "buck.git_commit";

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

  @Argument private List<String> arguments = new ArrayList<>();

  private boolean buildTargetsHaveBeenCalculated;

  @Nullable private DistBuildClientEventListener distBuildClientEventListener;

  public List<String> getArguments() {
    return arguments;
  }

  private boolean isArtifactCacheDisabled = false;

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

  public void setArtifactCacheDisabled(boolean value) {
    isArtifactCacheDisabled = value;
  }

  public boolean isArtifactCacheDisabled() {
    return isArtifactCacheDisabled;
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

  /** @return an absolute path or {@link Optional#empty()}. */
  public Optional<Path> getPathToBuildReport(BuckConfig buckConfig) {
    return Optional.ofNullable(
        buckConfig.resolvePathThatMayBeOutsideTheProjectFilesystem(buildReport));
  }

  Build createBuild(
      BuckConfig buckConfig,
      BuildRuleResolver ruleResolver,
      Cell rootCell,
      BuildEngine buildEngine,
      ArtifactCache artifactCache,
      Console console,
      Clock clock,
      ExecutionContext executionContext) {
    if (console.getVerbosity() == Verbosity.ALL) {
      console.getStdErr().printf("Creating a build with %d threads.\n", buckConfig.getNumThreads());
    }
    return new Build(
        ruleResolver,
        rootCell,
        buildEngine,
        artifactCache,
        buckConfig.getView(JavaBuckConfig.class).createDefaultJavaPackageFinder(),
        clock,
        executionContext);
  }

  @Nullable private Build lastBuild;

  private ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of();

  public static BuildJobState getDistBuildState(
      List<String> buildTargets,
      CommandRunnerParams params,
      WeightedListeningExecutorService executor)
      throws InterruptedException, IOException {
    BuildCommand buildCommand = new BuildCommand(buildTargets);
    int exitCode = buildCommand.checkArguments(params);
    if (exitCode != 0) {
      throw new HumanReadableException("The build targets are invalid.");
    }

    ActionAndTargetGraphs graphs = null;
    try {
      graphs = buildCommand.createGraphs(params, executor);
    } catch (ActionGraphCreationException e) {
      params.getConsole().printBuildFailure(e.getMessage());
      throw new RuntimeException(e);
    }

    return buildCommand.computeDistBuildState(params, graphs, executor).getFirst();
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    int exitCode = checkArguments(params);
    if (exitCode != 0) {
      return exitCode;
    }

    try (CommandThreadManager pool =
        new CommandThreadManager("Build", getConcurrencyLimit(params.getBuckConfig()))) {
      return run(params, pool, ImmutableSet.of());
    }
  }

  protected int checkArguments(CommandRunnerParams params) {
    if (!getArguments().isEmpty()) {
      return 0;
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
    params.getConsole().printBuildFailure(message);
    return 1;
  }

  protected int run(
      CommandRunnerParams params,
      CommandThreadManager commandThreadManager,
      ImmutableSet<String> additionalTargets)
      throws IOException, InterruptedException {
    if (!additionalTargets.isEmpty()) {
      this.arguments.addAll(additionalTargets);
    }
    BuildEvent.Started started = postBuildStartedEvent(params);
    int exitCode = 0;
    try {
      exitCode = executeBuildAndProcessResult(params, commandThreadManager);
    } catch (ActionGraphCreationException e) {
      params.getConsole().printBuildFailure(e.getMessage());
      exitCode = 1;
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
      CommandRunnerParams params, ListeningExecutorService executorService)
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
        getTargetGraphForLocalBuild(unversionedTargetGraph, versionedTargetGraph);
    checkSingleBuildTargetSpecifiedForOutBuildMode(targetGraphForLocalBuild);
    ActionGraphAndResolver actionGraph =
        createActionGraphAndResolver(params, targetGraphForLocalBuild);
    return new ActionAndTargetGraphs(unversionedTargetGraph, versionedTargetGraph, actionGraph);
  }

  private void checkSingleBuildTargetSpecifiedForOutBuildMode(
      TargetGraphAndBuildTargets targetGraphAndBuildTargets) throws ActionGraphCreationException {
    // Ideally, we would error out of this before we build the entire graph, but it is possible
    // that `getArguments().size()` is 1 but `targetGraphAndBuildTargets.getBuildTargets().size()`
    // is greater than 1 if the lone argument is a wildcard build target that ends in "...".
    // As such, we have to get the result of createTargetGraph() before we can do this check.
    if (outputPathForSingleBuildTarget != null
        && targetGraphAndBuildTargets.getBuildTargets().size() != 1) {
      throw new ActionGraphCreationException(
          String.format(
              "When using %s you must specify exactly one build target, but you specified %s",
              OUT_LONG_ARG, targetGraphAndBuildTargets.getBuildTargets()));
    }
  }

  private int executeBuildAndProcessResult(
      CommandRunnerParams params, CommandThreadManager commandThreadManager)
      throws IOException, InterruptedException, ActionGraphCreationException {
    int exitCode;
    ActionAndTargetGraphs graphs = null;
    if (useDistributedBuild) {
      DistBuildConfig distBuildConfig = new DistBuildConfig(params.getBuckConfig());
      DistBuildClientStatsTracker distBuildClientStatsTracker =
          new DistBuildClientStatsTracker(distBuildConfig.getBuildLabel());

      distBuildClientStatsTracker.startTimer(LOCAL_PREPARATION);
      distBuildClientStatsTracker.startTimer(LOCAL_GRAPH_CONSTRUCTION);

      graphs = createGraphs(params, commandThreadManager.getListeningExecutorService());

      distBuildClientStatsTracker.stopTimer(LOCAL_GRAPH_CONSTRUCTION);

      Pair<BuildJobState, DistBuildCellIndexer> stateAndCells =
          computeDistBuildState(params, graphs, commandThreadManager.getListeningExecutorService());
      BuildJobState jobState = stateAndCells.getFirst();
      DistBuildCellIndexer distBuildCellIndexer = stateAndCells.getSecond();

      try {
        exitCode =
            executeDistBuild(
                params,
                distBuildConfig,
                graphs,
                commandThreadManager.getWeightedListeningExecutorService(),
                params.getCell().getFilesystem(),
                params.getFileHashCache(),
                jobState,
                distBuildCellIndexer,
                distBuildClientStatsTracker);
      } catch (Throwable ex) {
        distBuildClientStatsTracker.setBuckClientError(true);
        String stackTrace = Throwables.getStackTraceAsString(ex);
        distBuildClientStatsTracker.setBuckClientErrorMessage(ex.toString() + "\n" + stackTrace);
        throw ex;
      } finally {
        if (distBuildClientStatsTracker.hasStampedeId()) {
          params
              .getBuckEventBus()
              .post(new DistBuildClientStatsEvent(distBuildClientStatsTracker.generateStats()));
        } else {
          LOG.error("Failed to published DistBuildClientStatsEvent as no Stampede ID was received");
        }
      }
    } else {
      graphs = createGraphs(params, commandThreadManager.getListeningExecutorService());
      exitCode =
          executeLocalBuild(
              params,
              graphs.actionGraph,
              commandThreadManager.getWeightedListeningExecutorService());
    }
    if (exitCode == 0) {
      exitCode = processSuccessfulBuild(params, graphs);
    }
    return exitCode;
  }

  private int processSuccessfulBuild(CommandRunnerParams params, ActionAndTargetGraphs graphs)
      throws IOException {
    if (params.getBuckConfig().createBuildOutputSymLinksEnabled()) {
      symLinkBuildResults(params, graphs.actionGraph);
    }
    if (showOutput || showFullOutput || showJsonOutput || showFullJsonOutput || showRuleKey) {
      showOutputs(params, graphs.actionGraph);
    }
    if (outputPathForSingleBuildTarget != null) {
      BuildTarget loneTarget =
          Iterables.getOnlyElement(graphs.getTargetGraphForLocalBuild().getBuildTargets());
      BuildRule rule = graphs.actionGraph.getResolver().getRule(loneTarget);
      if (!rule.outputFileCanBeCopied()) {
        params
            .getConsole()
            .printErrorText(
                String.format(
                    "%s does not have an output that is compatible with `buck build --out`",
                    loneTarget));
        return 1;
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
                new SourcePathRuleFinder(graphs.actionGraph.getResolver()));
        projectFilesystem.copyFile(
            pathResolver.getAbsolutePath(output), outputPathForSingleBuildTarget);
      }
    }
    return 0;
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

  private Pair<BuildJobState, DistBuildCellIndexer> computeDistBuildState(
      final CommandRunnerParams params,
      ActionAndTargetGraphs graphs,
      final ListeningExecutorService executorService)
      throws IOException, InterruptedException {
    // Distributed builds serialize and send the unversioned target graph,
    // and then deserialize and version remotely.
    TargetGraphAndBuildTargets targetGraphAndBuildTargets = graphs.unversionedTargetGraph;

    TypeCoercerFactory typeCoercerFactory =
        new DefaultTypeCoercerFactory(PathTypeCoercer.PathExistenceVerificationMode.DO_NOT_VERIFY);
    ParserTargetNodeFactory<TargetNode<?, ?>> parserTargetNodeFactory =
        DefaultParserTargetNodeFactory.createForDistributedBuild(
            new ConstructorArgMarshaller(typeCoercerFactory),
            new TargetNodeFactory(typeCoercerFactory));
    DistBuildTargetGraphCodec targetGraphCodec =
        new DistBuildTargetGraphCodec(
            parserTargetNodeFactory,
            new Function<TargetNode<?, ?>, Map<String, Object>>() {
              @Nullable
              @Override
              public Map<String, Object> apply(TargetNode<?, ?> input) {
                try {
                  return params
                      .getParser()
                      .getRawTargetNode(
                          params.getBuckEventBus(),
                          params.getCell().getCell(input.getBuildTarget()),
                          false /* enableProfiling */,
                          executorService,
                          input);
                } catch (BuildFileParseException e) {
                  throw new RuntimeException(e);
                }
              }
            },
            targetGraphAndBuildTargets
                .getBuildTargets()
                .stream()
                .map(t -> t.getFullyQualifiedName())
                .collect(Collectors.toSet()));

    ActionGraphAndResolver actionGraphAndResolver = graphs.actionGraph;
    DistBuildCellIndexer cellIndexer = new DistBuildCellIndexer(params.getCell());
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(actionGraphAndResolver.getResolver());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    DistBuildFileHashes distributedBuildFileHashes =
        new DistBuildFileHashes(
            actionGraphAndResolver.getActionGraph(),
            pathResolver,
            ruleFinder,
            params.getFileHashCache(),
            cellIndexer,
            executorService,
            params.getBuckConfig().getKeySeed(),
            params.getCell());

    return new Pair<>(
        DistBuildState.dump(
            cellIndexer,
            distributedBuildFileHashes,
            targetGraphCodec,
            targetGraphAndBuildTargets.getTargetGraph(),
            buildTargets),
        cellIndexer);
  }

  private int executeDistBuild(
      CommandRunnerParams params,
      DistBuildConfig distBuildConfig,
      ActionAndTargetGraphs graphs,
      WeightedListeningExecutorService executorService,
      ProjectFilesystem filesystem,
      FileHashCache fileHashCache,
      BuildJobState jobState,
      DistBuildCellIndexer distBuildCellIndexer,
      DistBuildClientStatsTracker distBuildClientStats)
      throws IOException, InterruptedException {
    Preconditions.checkNotNull(distBuildClientEventListener);

    if (distributedBuildStateFile != null) {
      Path stateDumpPath = Paths.get(distributedBuildStateFile);

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

      BuildJobStateSerializer.serialize(jobState, filesystem.newFileOutputStream(stateDumpPath));
      return 0;
    }

    BuildEvent.DistBuildStarted started = BuildEvent.distBuildStarted();
    params.getBuckEventBus().post(started);

    int distBuildExitCode = 1;
    DistBuildClientExecutor.ExecutionResult distBuildResult;
    BuckVersion buckVersion = getBuckVersion();
    Preconditions.checkArgument(params.getInvocationInfo().isPresent());

    DistBuildLogStateTracker distBuildLogStateTracker =
        DistBuildFactory.newDistBuildLogStateTracker(
            params.getInvocationInfo().get().getLogDirectoryPath(), filesystem);
    try (DistBuildService service = DistBuildFactory.newDistBuildService(params)) {
      try {
        DistBuildClientExecutor build =
            new DistBuildClientExecutor(
                jobState,
                distBuildCellIndexer,
                service,
                distBuildLogStateTracker,
                buckVersion,
                distBuildClientStats,
                params.getScheduledExecutor());
        distBuildResult =
            build.executeAndPrintFailuresToEventBus(
                executorService,
                filesystem,
                fileHashCache,
                params.getBuckEventBus(),
                distBuildConfig.getBuildMode(),
                distBuildConfig.getNumberOfMinions(),
                distBuildConfig.getRepository(),
                distBuildConfig.getTenantId());
        distBuildExitCode = distBuildResult.exitCode;
      } finally {
        BuildEvent.DistBuildFinished finished =
            BuildEvent.distBuildFinished(started, distBuildExitCode);
        params.getBuckEventBus().post(finished);
      }

      distBuildClientStats.setIsLocalFallbackBuildEnabled(
          distBuildConfig.isSlowLocalBuildFallbackModeEnabled());
      distBuildClientStats.setDistributedBuildExitCode(distBuildExitCode);
      // After dist-build is complete, start build locally and we'll find everything in the cache.
      int exitCode = distBuildExitCode;
      if (distBuildConfig.isSlowLocalBuildFallbackModeEnabled() || distBuildExitCode == 0) {
        if (distBuildExitCode != 0) {
          String errorMessage =
              String.format(
                  "The remote/distributed build with Stampede ID [%s] "
                      + "failed with exit code [%d] trying to build "
                      + "targets [%s]. This program will continue now by falling back to a "
                      + "local build because config "
                      + "[stampede.enable_slow_local_build_fallback=true]. ",
                  distBuildResult.stampedeId, distBuildExitCode, Joiner.on(" ").join(arguments));
          params.getConsole().printErrorText(errorMessage);
          LOG.error(errorMessage);
        }

        distBuildClientStats.startTimer(PERFORM_LOCAL_BUILD);
        int localBuildExitCode = executeLocalBuild(params, graphs.actionGraph, executorService);
        distBuildClientStats.stopTimer(PERFORM_LOCAL_BUILD);
        distBuildClientStats.setLocalBuildExitCode(localBuildExitCode);
        distBuildClientStats.setPerformedLocalBuild(true);

        // Publish details about all default rule keys that were cache misses.
        // A non-zero value suggests a problem that needs investigating.
        try {
          Set<String> cacheMissRequestKeys =
              distBuildClientEventListener.getDefaultCacheMissRequestKeys();
          List<RuleKeyLogEntry> ruleKeyLogs = service.fetchRuleKeyLogs(cacheMissRequestKeys);
          params
              .getBuckEventBus()
              .post(
                  distBuildClientEventListener.createDistBuildClientCacheResultsEvent(ruleKeyLogs));
        } catch (Exception ex) {
          LOG.error("Failed to publish distributed build client cache request event", ex);
        }

        distBuildClientStats.startTimer(POST_BUILD_ANALYSIS);
        DistBuildPostBuildAnalysis postBuildAnalysis =
            new DistBuildPostBuildAnalysis(
                params.getInvocationInfo().get().getBuildId(),
                distBuildResult.stampedeId,
                filesystem.resolve(params.getInvocationInfo().get().getLogDirectoryPath()),
                distBuildLogStateTracker.getRunIdsWithLogDirs(),
                DistBuildCommand.class.getSimpleName().toLowerCase());

        Path analysisSummaryFile =
            postBuildAnalysis.dumpResultsToLogFile(postBuildAnalysis.runAnalysis());
        Path relativePathToSummaryFile = filesystem.getRootPath().relativize(analysisSummaryFile);
        params
            .getBuckEventBus()
            .post(
                ConsoleEvent.warning(
                    "Details of distributed build analysis: %s",
                    relativePathToSummaryFile.toString()));
        distBuildClientStats.stopTimer(POST_BUILD_ANALYSIS);

        exitCode = localBuildExitCode;
      }

      distBuildClientStats.stopTimer(POST_DISTRIBUTED_BUILD_LOCAL_STEPS);

      params
          .getBuckEventBus()
          .post(new DistBuildClientStatsEvent(distBuildClientStats.generateStats()));
      return exitCode;
    }
  }

  private BuckVersion getBuckVersion() throws IOException {
    if (buckBinary == null) {
      String gitHash = System.getProperty(BUCK_GIT_COMMIT_KEY, null);
      if (gitHash == null) {
        throw new HumanReadableException(
            String.format(
                "Property [%s] is not set and the command line flag [%s] was not passed.",
                BUCK_GIT_COMMIT_KEY, BUCK_BINARY_STRING_ARG));
      }

      return BuckVersionUtil.createFromGitHash(gitHash);
    }

    Path binaryPath = Paths.get(buckBinary);
    if (!Files.isRegularFile(binaryPath)) {
      throw new HumanReadableException(
          String.format(
              "Buck binary [%s] passed under flag [%s] does not exist.",
              binaryPath, BUCK_BINARY_STRING_ARG));
    }

    return BuckVersionUtil.createFromLocalBinary(binaryPath);
  }

  private void showOutputs(
      CommandRunnerParams params, ActionGraphAndResolver actionGraphAndResolver) {
    TreeMap<String, String> sortedJsonOutputs = new TreeMap<String, String>();
    Optional<DefaultRuleKeyFactory> ruleKeyFactory = Optional.empty();
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(actionGraphAndResolver.getResolver());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    if (showRuleKey) {
      RuleKeyFieldLoader fieldLoader = new RuleKeyFieldLoader(params.getBuckConfig().getKeySeed());
      ruleKeyFactory =
          Optional.of(
              new DefaultRuleKeyFactory(
                  fieldLoader, params.getFileHashCache(), pathResolver, ruleFinder));
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
      try {
        ObjectMappers.WRITER.withDefaultPrettyPrinter().writeValue(stringWriter, sortedJsonOutputs);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
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
    } catch (BuildTargetException | BuildFileParseException e) {
      throw new ActionGraphCreationException(MoreExceptions.getHumanReadableOrLocalizedMessage(e));
    }
  }

  private ActionGraphAndResolver createActionGraphAndResolver(
      CommandRunnerParams params, TargetGraphAndBuildTargets targetGraphAndBuildTargets)
      throws ActionGraphCreationException {
    buildTargets = targetGraphAndBuildTargets.getBuildTargets();
    buildTargetsHaveBeenCalculated = true;
    ActionGraphAndResolver actionGraphAndResolver =
        params
            .getActionGraphCache()
            .getActionGraph(
                params.getBuckEventBus(),
                targetGraphAndBuildTargets.getTargetGraph(),
                params.getBuckConfig());

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

  protected int executeLocalBuild(
      CommandRunnerParams params,
      ActionGraphAndResolver actionGraphAndResolver,
      WeightedListeningExecutorService executor)
      throws IOException, InterruptedException {

    ArtifactCache artifactCache = params.getArtifactCacheFactory().newInstance(useDistributedBuild);
    if (isArtifactCacheDisabled()) {
      artifactCache = new NoopArtifactCache();
    }

    return executeBuild(
        params,
        actionGraphAndResolver,
        executor,
        artifactCache,
        new LocalCachingBuildEngineDelegate(params.getFileHashCache()),
        params.getBuckConfig(),
        buildTargets);
  }

  private int executeBuild(
      CommandRunnerParams params,
      ActionGraphAndResolver actionGraphAndResolver,
      WeightedListeningExecutorService executor,
      ArtifactCache artifactCache,
      CachingBuildEngineDelegate cachingBuildEngineDelegate,
      BuckConfig rootCellBuckConfig,
      Iterable<BuildTarget> targetsToBuild)
      throws IOException, InterruptedException {
    MetadataChecker.checkAndCleanIfNeeded(params.getCell());
    CachingBuildEngineBuckConfig cachingBuildEngineBuckConfig =
        rootCellBuckConfig.getView(CachingBuildEngineBuckConfig.class);
    try (RuleKeyCacheScope<RuleKey> ruleKeyCacheScope =
            getDefaultRuleKeyCacheScope(
                params,
                new RuleKeyCacheRecycler.SettingsAffectingCache(
                    rootCellBuckConfig.getKeySeed(), actionGraphAndResolver.getActionGraph()));
        CachingBuildEngine buildEngine =
            new CachingBuildEngine(
                cachingBuildEngineDelegate,
                executor,
                new DefaultStepRunner(),
                getBuildEngineMode().orElse(cachingBuildEngineBuckConfig.getBuildEngineMode()),
                cachingBuildEngineBuckConfig.getBuildMetadataStorage(),
                cachingBuildEngineBuckConfig.getBuildDepFiles(),
                cachingBuildEngineBuckConfig.getBuildMaxDepFileCacheEntries(),
                cachingBuildEngineBuckConfig.getBuildArtifactCacheSizeLimit(),
                actionGraphAndResolver.getResolver(),
                params.getBuildInfoStoreManager(),
                cachingBuildEngineBuckConfig.getResourceAwareSchedulingInfo(),
                cachingBuildEngineBuckConfig.getConsoleLogBuildRuleFailuresInline(),
                RuleKeyFactories.of(
                    rootCellBuckConfig.getKeySeed(),
                    cachingBuildEngineDelegate.getFileHashCache(),
                    actionGraphAndResolver.getResolver(),
                    cachingBuildEngineBuckConfig.getBuildInputRuleKeyFileSizeLimit(),
                    ruleKeyCacheScope.getCache()),
                rootCellBuckConfig.getFileHashCacheMode());
        Build build =
            createBuild(
                rootCellBuckConfig,
                actionGraphAndResolver.getResolver(),
                params.getCell(),
                buildEngine,
                artifactCache,
                params.getConsole(),
                params.getClock(),
                getExecutionContext())) {
      lastBuild = build;
      return build.executeAndPrintFailuresToEventBus(
          FluentIterable.from(targetsToBuild)
              .append(getAdditionalTargetsToBuild(actionGraphAndResolver.getResolver())),
          isKeepGoing(),
          params.getBuckEventBus(),
          params.getConsole(),
          getPathToBuildReport(rootCellBuckConfig));
    }
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
    Preconditions.checkNotNull(lastBuild);
    return lastBuild;
  }

  public ImmutableList<BuildTarget> getBuildTargets() {
    Preconditions.checkState(buildTargetsHaveBeenCalculated);
    return ImmutableList.copyOf(buildTargets);
  }

  @Override
  public String getShortDescription() {
    return "builds the specified target";
  }

  protected static TargetGraphAndBuildTargets getTargetGraphForLocalBuild(
      TargetGraphAndBuildTargets unversionedTargetGraph,
      Optional<TargetGraphAndBuildTargets> versionedTargetGraph) {
    // If a versioned target graph was produced then we always use this for the local build,
    // otherwise the unversioned graph is used.
    return versionedTargetGraph.isPresent() ? versionedTargetGraph.get() : unversionedTargetGraph;
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

  public static class ActionGraphCreationException extends Exception {
    public ActionGraphCreationException(String message) {
      super(message);
    }
  }

  protected static class ActionAndTargetGraphs {
    final TargetGraphAndBuildTargets unversionedTargetGraph;
    final Optional<TargetGraphAndBuildTargets> versionedTargetGraph;
    final ActionGraphAndResolver actionGraph;

    protected ActionAndTargetGraphs(
        TargetGraphAndBuildTargets unversionedTargetGraph,
        Optional<TargetGraphAndBuildTargets> versionedTargetGraph,
        ActionGraphAndResolver actionGraph) {
      this.unversionedTargetGraph = unversionedTargetGraph;
      this.versionedTargetGraph = versionedTargetGraph;
      this.actionGraph = actionGraph;
    }

    protected TargetGraphAndBuildTargets getTargetGraphForLocalBuild() {
      // If a versioned target graph was produced then we always use this for the local build,
      // otherwise the unversioned graph is used.
      return BuildCommand.getTargetGraphForLocalBuild(unversionedTargetGraph, versionedTargetGraph);
    }
  }
}
