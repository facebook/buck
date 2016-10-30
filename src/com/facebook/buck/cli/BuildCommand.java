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

import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.command.Build;
import com.facebook.buck.distributed.BuckVersionUtil;
import com.facebook.buck.distributed.BuildJobStateSerializer;
import com.facebook.buck.distributed.DistBuildCellIndexer;
import com.facebook.buck.distributed.DistBuildClientExecutor;
import com.facebook.buck.distributed.DistBuildFileHashes;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildState;
import com.facebook.buck.distributed.DistBuildTargetGraphCodec;
import com.facebook.buck.distributed.DistBuildTypeCoercerFactory;
import com.facebook.buck.distributed.thrift.BuckVersion;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.parser.DefaultParserTargetNodeFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.ParserTargetNodeFactory;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.CachingBuildEngineBuckConfig;
import com.facebook.buck.rules.CachingBuildEngineDelegate;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.LocalCachingBuildEngineDelegate;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodeFactory;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.shell.WorkerProcessPool;
import com.facebook.buck.step.AdbOptions;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.step.TargetDevice;
import com.facebook.buck.step.TargetDeviceOptions;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListeningExecutorService;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.Nullable;

public class BuildCommand extends AbstractCommand {

  private static final String KEEP_GOING_LONG_ARG = "--keep-going";
  private static final String BUILD_REPORT_LONG_ARG = "--build-report";
  private static final String JUST_BUILD_LONG_ARG = "--just-build";
  private static final String DEEP_LONG_ARG = "--deep";
  private static final String POPULATE_CACHE_LONG_ARG = "--populate-cache";
  private static final String SHALLOW_LONG_ARG = "--shallow";
  private static final String REPORT_ABSOLUTE_PATHS = "--report-absolute-paths";
  private static final String SHOW_OUTPUT_LONG_ARG = "--show-output";
  private static final String SHOW_FULL_OUTPUT_LONG_ARG = "--show-full-output";
  private static final String SHOW_RULEKEY_LONG_ARG = "--show-rulekey";
  private static final String DISTRIBUTED_LONG_ARG = "--distributed";
  private static final String BUCK_BINARY_STRING_ARG = "--buck-binary";

  private static final String BUCK_GIT_COMMIT_KEY = "buck.git_commit";

  @Option(
      name = KEEP_GOING_LONG_ARG,
      usage = "Keep going when some targets can't be made.")
  private boolean keepGoing = false;

  @Option(
      name = BUILD_REPORT_LONG_ARG,
      usage = "File where build report will be written.")
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
          "Perform a \"deep\" build, which makes the output of all transitive dependencies" +
              " available.",
      forbids = SHALLOW_LONG_ARG)
  private boolean deepBuild = false;

  @Option(
      name = POPULATE_CACHE_LONG_ARG,
      usage =
          "Performs a cache population, which makes the output of all unchanged " +
              "transitive dependencies available (if these outputs are available " +
              "in the remote cache). Does not build changed or unavailable dependencies locally.",
      forbids = {SHALLOW_LONG_ARG, DEEP_LONG_ARG})
  private boolean populateCacheOnly = false;

  @Option(
      name = SHALLOW_LONG_ARG,
      usage =
          "Perform a \"shallow\" build, which only makes the output of all explicitly listed" +
              " targets available.",
      forbids = DEEP_LONG_ARG)
  private boolean shallowBuild = false;

  @Option(
      name = REPORT_ABSOLUTE_PATHS,
      usage =
          "Reports errors using absolute paths to the source files instead of relative paths.")
  private boolean shouldReportAbsolutePaths = false;

  @Option(
      name = SHOW_OUTPUT_LONG_ARG,
      usage = "Print the path to the output for each of the built rules relative to the cell.")
  private boolean showOutput;

  @Option(
      name = SHOW_FULL_OUTPUT_LONG_ARG,
      usage = "Print the absolute path to the output for each of the built rules.")
  private boolean showFullOutput;

  @Option(
      name = SHOW_RULEKEY_LONG_ARG,
      usage = "Print the rulekey for each of the built rules.")
  private boolean showRuleKey;

  @Option(
      name = DISTRIBUTED_LONG_ARG,
      usage = "Whether to run in distributed build mode. (experimental)",
      hidden = true)
  private boolean useDistributedBuild = false;

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

  @Argument
  private List<String> arguments = Lists.newArrayList();

  private boolean buildTargetsHaveBeenCalculated;

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

  /**
   * @return an absolute path or {@link Optional#empty()}.
   */
  public Optional<Path> getPathToBuildReport(BuckConfig buckConfig) {
    return Optional.ofNullable(
        buckConfig.resolvePathThatMayBeOutsideTheProjectFilesystem(buildReport));
  }

  Build createBuild(
      BuckConfig buckConfig,
      ActionGraph graph,
      BuildRuleResolver ruleResolver,
      Cell rootCell,
      Supplier<AndroidPlatformTarget> androidPlatformTargetSupplier,
      BuildEngine buildEngine,
      ArtifactCache artifactCache,
      Console console,
      BuckEventBus eventBus,
      Optional<TargetDevice> targetDevice,
      Optional<ConcurrentMap<String, WorkerProcessPool>> persistentWorkerPools,
      Platform platform,
      ImmutableMap<String, String> environment,
      ObjectMapper objectMapper,
      Clock clock,
      Optional<AdbOptions> adbOptions,
      Optional<TargetDeviceOptions> targetDeviceOptions,
      Map<ExecutorPool, ListeningExecutorService> executors) {
    if (console.getVerbosity() == Verbosity.ALL) {
      console.getStdErr().printf("Creating a build with %d threads.\n", buckConfig.getNumThreads());
    }
    return new Build(
        graph,
        ruleResolver,
        rootCell,
        targetDevice,
        androidPlatformTargetSupplier,
        buildEngine,
        artifactCache,
        buckConfig.createDefaultJavaPackageFinder(),
        console,
        buckConfig.getDefaultTestTimeoutMillis(),
        isCodeCoverageEnabled(),
        isDebugEnabled(),
        shouldReportAbsolutePaths(),
        eventBus,
        platform,
        environment,
        objectMapper,
        clock,
        getConcurrencyLimit(buckConfig),
        adbOptions,
        targetDeviceOptions,
        persistentWorkerPools,
        executors);
  }

  @Nullable
  private Build lastBuild;

  private ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of();

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    int exitCode = checkArguments(params);
    if (exitCode != 0) {
      return exitCode;
    }

    try (CommandThreadManager pool = new CommandThreadManager(
        "Build",
        getConcurrencyLimit(params.getBuckConfig()))) {
      return run(params, pool.getExecutor(), ImmutableSet.of());
    }
  }

  protected int checkArguments(CommandRunnerParams params) {
    if (getArguments().isEmpty()) {
      params.getConsole().printBuildFailure("Must specify at least one build target.");

      // If there are aliases defined in .buckconfig, suggest that the user
      // build one of them. We show the user only the first 10 aliases.
      ImmutableSet<String> aliases = params.getBuckConfig().getAliases();
      if (!aliases.isEmpty()) {
        params.getBuckEventBus().post(ConsoleEvent.severe(String.format(
            "Try building one of the following targets:\n%s",
            Joiner.on(' ').join(Iterators.limit(aliases.iterator(), 10)))));
      }
      return 1;
    }
    return 0;
  }

  protected int run(
      CommandRunnerParams params,
      WeightedListeningExecutorService executorService,
      ImmutableSet<String> additionalTargets) throws IOException, InterruptedException {
    if (!additionalTargets.isEmpty()) {
      this.arguments.addAll(additionalTargets);
    }

    // Post the build started event, setting it to the Parser recorded start time if appropriate.
    BuildEvent.Started started = BuildEvent.started(getArguments(), useDistributedBuild);
    if (params.getParser().getParseStartTime().isPresent()) {
      params.getBuckEventBus().post(
          started,
          params.getParser().getParseStartTime().get());
    } else {
      params.getBuckEventBus().post(started);
    }

    int exitCode;
    try {
      // Parse the build files to create a TargetGraph and ActionGraph.
      TargetGraphAndBuildTargets targetGraphAndBuildTargets =
          createTargetGraph(params, executorService);
      ActionGraphAndResolver actionGraphAndResolver = createActionGraphAndResolver(
          params,
          targetGraphAndBuildTargets);

      if (useDistributedBuild) {
        exitCode = executeDistributedBuild(
            params,
            targetGraphAndBuildTargets,
            actionGraphAndResolver,
            executorService);
      } else {
        exitCode = executeLocalBuild(params, actionGraphAndResolver, executorService);
      }

      if (exitCode == 0 && (showOutput || showFullOutput || showRuleKey)) {
        showOutputs(params, actionGraphAndResolver);
      }
    } catch (ActionGraphCreationException e) {
      params.getConsole().printBuildFailure(e.getMessage());
      exitCode = 1;
    }
    params.getBuckEventBus().post(BuildEvent.finished(started, exitCode));

    return exitCode;
  }

  private BuildJobState computeDistributedBuildJobState(
      DistBuildTargetGraphCodec targetGraphCodec,
      final CommandRunnerParams params,
      TargetGraphAndBuildTargets targetGraphAndBuildTargets,
      ActionGraphAndResolver actionGraphAndResolver,
      final WeightedListeningExecutorService executorService)
      throws InterruptedException, IOException {
    DistBuildCellIndexer cellIndexer =
        new DistBuildCellIndexer(params.getCell());
    DistBuildFileHashes distributedBuildFileHashes = new DistBuildFileHashes(
        actionGraphAndResolver.getActionGraph(),
        new SourcePathResolver(actionGraphAndResolver.getResolver()),
        params.getFileHashCache(),
        cellIndexer,
        executorService,
        params.getBuckConfig().getKeySeed());

    return DistBuildState.dump(
        cellIndexer,
        distributedBuildFileHashes,
        targetGraphCodec,
        targetGraphAndBuildTargets.getTargetGraph()
    );
  }

  private int executeDistributedBuild(
      final CommandRunnerParams params,
      TargetGraphAndBuildTargets targetGraphAndBuildTargets,
      ActionGraphAndResolver actionGraphAndResolver,
      final WeightedListeningExecutorService executorService)
      throws IOException, InterruptedException {
    ProjectFilesystem filesystem = params.getCell().getFilesystem();

    DistBuildTypeCoercerFactory typeCoercerFactory =
        new DistBuildTypeCoercerFactory(params.getObjectMapper());
    ParserTargetNodeFactory<TargetNode<?>> parserTargetNodeFactory =
        DefaultParserTargetNodeFactory.createForDistributedBuild(
            new ConstructorArgMarshaller(typeCoercerFactory),
            new TargetNodeFactory(typeCoercerFactory));
    DistBuildTargetGraphCodec targetGraphCodec = new DistBuildTargetGraphCodec(
        params.getObjectMapper(),
        parserTargetNodeFactory,
        new Function<TargetNode<?>, Map<String, Object>>() {
          @Nullable
          @Override
          public Map<String, Object> apply(TargetNode<?> input) {
            try {
              return params.getParser().getRawTargetNode(
                  params.getBuckEventBus(),
                  params.getCell().getCell(input.getBuildTarget()),
                  false /* enableProfiling */,
                  executorService,
                  input);
            } catch (BuildFileParseException e) {
              throw new RuntimeException(e);
            }
          }
        });

    BuildJobState jobState = computeDistributedBuildJobState(
        targetGraphCodec,
        params,
        targetGraphAndBuildTargets,
        actionGraphAndResolver,
        executorService);

    if (distributedBuildStateFile != null) {
      Path stateDumpPath = Paths.get(distributedBuildStateFile);
      BuildJobStateSerializer.serialize(
          jobState,
          filesystem.newFileOutputStream(stateDumpPath));
      return 0;

    } else {
      BuckVersion buckVersion = getBuckVersion();
      try (DistBuildService service = DistBuildFactory.newDistBuildService(params)) {
        DistBuildClientExecutor build = new DistBuildClientExecutor(
            jobState,
            service,
            1000 /* millisBetweenStatusPoll */,
            buckVersion);
        int exitCode = build.executeAndPrintFailuresToEventBus(
            executorService,
            params.getBuckEventBus());

        // After dist-build is complete, start build locally and we'll find everything in the cache.
        // TODO(shivanker): Add a flag to disable building, and only fetch from the cache.
        if (exitCode == 0) {
          exitCode = executeLocalBuild(params, actionGraphAndResolver, executorService);
        }
        return exitCode;
      }
    }
  }

  private BuckVersion getBuckVersion() throws IOException {
    if (buckBinary == null) {
      String gitHash = System.getProperty(BUCK_GIT_COMMIT_KEY, null);
      if (gitHash == null) {
        throw new HumanReadableException(String.format(
            "Property [%s] is not set and the command line flag [%s] was not passed.",
            BUCK_GIT_COMMIT_KEY,
            BUCK_BINARY_STRING_ARG));
      }

      return BuckVersionUtil.createFromGitHash(gitHash);
    }

    Path binaryPath = Paths.get(buckBinary);
    if (!Files.isRegularFile(binaryPath)) {
      throw new HumanReadableException(String.format(
          "Buck binary [%s] passed under flag [%s] does not exist.",
          binaryPath,
          BUCK_BINARY_STRING_ARG));
    }

    return BuckVersionUtil.createFromLocalBinary(binaryPath);
  }

  private void showOutputs(
      CommandRunnerParams params,
      ActionGraphAndResolver actionGraphAndResolver) {
    Optional<DefaultRuleKeyBuilderFactory> ruleKeyBuilderFactory =
        Optional.empty();
    if (showRuleKey) {
      ruleKeyBuilderFactory = Optional.of(
          new DefaultRuleKeyBuilderFactory(
              params.getBuckConfig().getKeySeed(),
              params.getFileHashCache(),
              new SourcePathResolver(actionGraphAndResolver.getResolver())));
    }
    params.getConsole().getStdOut().println("The outputs are:");
    for (BuildTarget buildTarget : buildTargets) {
      try {
        BuildRule rule = actionGraphAndResolver.getResolver().requireRule(buildTarget);
        Optional<Path> outputPath =
            TargetsCommand.getUserFacingOutputPath(
                rule,
                showFullOutput,
                params.getBuckConfig().getBuckOutCompatLink());
        params.getConsole().getStdOut().printf(
            "%s%s%s\n",
            rule.getFullyQualifiedName(),
            showRuleKey ? " " + ruleKeyBuilderFactory.get().build(rule).toString() : "",
            showOutput || showFullOutput ?
                " " + outputPath.map(Object::toString).orElse("")
                : "");
      } catch (NoSuchBuildTargetException e) {
        throw new HumanReadableException(MoreExceptions.getHumanReadableOrLocalizedMessage(e));
      }
    }
  }

  private TargetGraphAndBuildTargets createTargetGraph(
      CommandRunnerParams params,
      ListeningExecutorService executor)
      throws IOException, InterruptedException, ActionGraphCreationException {
    if (getArguments().isEmpty()) {

      // If there are aliases defined in .buckconfig, suggest that the user
      // build one of them. We show the user only the first 10 aliases.
      ImmutableSet<String> aliases = params.getBuckConfig().getAliases();
      if (!aliases.isEmpty()) {
        params.getBuckEventBus().post(ConsoleEvent.severe(String.format(
            "Try building one of the following targets:\n%s",
            Joiner.on(' ').join(Iterators.limit(aliases.iterator(), 10)))));
      }
      throw new ActionGraphCreationException("Must specify at least one build target.");
    }

    // Parse the build files to create a ActionGraph.
    ParserConfig parserConfig = params.getBuckConfig().getView(ParserConfig.class);
    try {
      return params.getParser()
          .buildTargetGraphForTargetNodeSpecs(
              params.getBuckEventBus(),
              params.getCell(),
              getEnableParserProfiling(),
              executor,
              parseArgumentsAsTargetNodeSpecs(
                  params.getBuckConfig(),
                  getArguments()),
              /* ignoreBuckAutodepsFiles */ false,
              parserConfig.getDefaultFlavorsMode());
    } catch (BuildTargetException | BuildFileParseException e) {
      throw new ActionGraphCreationException(MoreExceptions.getHumanReadableOrLocalizedMessage(e));
    }
  }

  private ActionGraphAndResolver createActionGraphAndResolver(
      CommandRunnerParams params,
      TargetGraphAndBuildTargets targetGraphAndBuildTargets)
      throws ActionGraphCreationException {
    buildTargets = targetGraphAndBuildTargets.getBuildTargets();
    buildTargetsHaveBeenCalculated = true;
    ActionGraphAndResolver actionGraphAndResolver = Preconditions.checkNotNull(
        params.getActionGraphCache().getActionGraph(
            params.getBuckEventBus(),
            params.getBuckConfig().isActionGraphCheckingEnabled(),
            targetGraphAndBuildTargets.getTargetGraph(),
            params.getBuckConfig().getKeySeed()));

    // If the user specified an explicit build target, use that.
    if (justBuildTarget != null) {
      BuildTarget explicitTarget = BuildTargetParser.INSTANCE.parse(
          justBuildTarget,
          BuildTargetPatternParser.fullyQualified(),
          params.getCell().getCellPathResolver());
      Iterable<BuildRule> actionGraphRules =
          Preconditions.checkNotNull(actionGraphAndResolver.getActionGraph().getNodes());
      ImmutableSet<BuildTarget> actionGraphTargets =
          ImmutableSet.copyOf(
              Iterables.transform(actionGraphRules, HasBuildTarget::getBuildTarget));
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

    ArtifactCache artifactCache = params.getArtifactCache();
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
      Iterable<? extends HasBuildTarget> targetsToBuild) throws IOException, InterruptedException {
    CachingBuildEngineBuckConfig cachingBuildEngineBuckConfig =
        rootCellBuckConfig.getView(CachingBuildEngineBuckConfig.class);
    try (Build build = createBuild(
        rootCellBuckConfig,
        actionGraphAndResolver.getActionGraph(),
        actionGraphAndResolver.getResolver(),
        params.getCell(),
        params.getAndroidPlatformTargetSupplier(),
        new CachingBuildEngine(
            cachingBuildEngineDelegate,
            executor,
            new DefaultStepRunner(),
            getBuildEngineMode().orElse(cachingBuildEngineBuckConfig.getBuildEngineMode()),
            cachingBuildEngineBuckConfig.getBuildDepFiles(),
            cachingBuildEngineBuckConfig.getBuildMaxDepFileCacheEntries(),
            cachingBuildEngineBuckConfig.getBuildArtifactCacheSizeLimit(),
            cachingBuildEngineBuckConfig.getBuildInputRuleKeyFileSizeLimit(),
            params.getObjectMapper(),
            actionGraphAndResolver.getResolver(),
            rootCellBuckConfig.getKeySeed(),
            cachingBuildEngineBuckConfig.getResourceAwareSchedulingInfo()),
        artifactCache,
        params.getConsole(),
        params.getBuckEventBus(),
        Optional.empty(),
        params.getPersistentWorkerPools(),
        rootCellBuckConfig.getPlatform(),
        rootCellBuckConfig.getEnvironment(),
        params.getObjectMapper(),
        params.getClock(),
        Optional.empty(),
        Optional.empty(),
        params.getExecutors())) {
      lastBuild = build;
      return build.executeAndPrintFailuresToEventBus(
          targetsToBuild,
          isKeepGoing(),
          params.getBuckEventBus(),
          params.getConsole(),
          getPathToBuildReport(rootCellBuckConfig));
    }
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

  @Override
  protected ImmutableList<String> getOptions() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    builder.addAll(super.getOptions());
    if (keepGoing) {
      builder.add(KEEP_GOING_LONG_ARG);
    }
    if (buildReport != null) {
      builder.add(BUILD_REPORT_LONG_ARG);
      builder.add(buildReport.toString());
    }
    if (justBuildTarget != null) {
      builder.add(JUST_BUILD_LONG_ARG);
      builder.add(justBuildTarget);
    }
    if (shouldReportAbsolutePaths) {
      builder.add(REPORT_ABSOLUTE_PATHS);
    }
    return builder.build();
  }

  public static class ActionGraphCreationException extends Exception {
    public ActionGraphCreationException(String message) {
      super(message);
    }
  }
}
