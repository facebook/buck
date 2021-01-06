/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cli;

import com.facebook.buck.apple.AppleBundle;
import com.facebook.buck.apple.AppleDsym;
import com.facebook.buck.command.Build;
import com.facebook.buck.command.BuildExecutor;
import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.build.engine.delegate.LocalCachingBuildEngineDelegate;
import com.facebook.buck.core.build.engine.type.BuildType;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.graph.ActionAndTargetGraphs;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.model.impl.HashedBuckOutLinkMode;
import com.facebook.buck.core.model.targetgraph.TargetGraphCreationResult;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetOutputLabelParser;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rulekey.calculator.ParallelRuleKeyCalculator;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.transformer.impl.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.listener.FileSerializationOutputRuleDepsListener;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.parser.spec.TargetNodeSpec;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.rules.keys.RuleKeyCacheScope;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.support.cli.config.AliasConfig;
import com.facebook.buck.support.cli.config.CliConfig;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.concurrent.ExecutorPool;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.versions.VersionException;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.immutables.value.Value;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

public class BuildCommand extends AbstractCommand {

  private static final String KEEP_GOING_LONG_ARG = "--keep-going";
  private static final String BUILD_REPORT_LONG_ARG = "--build-report";
  private static final String JUST_BUILD_LONG_ARG = "--just-build";
  private static final String DEEP_LONG_ARG = "--deep";
  private static final String OUT_LONG_ARG = "--out";
  private static final String POPULATE_CACHE_LONG_ARG = "--populate-cache";
  private static final String SHALLOW_LONG_ARG = "--shallow";
  private static final String REPORT_ABSOLUTE_PATHS = "--report-absolute-paths";
  private static final String SHOW_OUTPUT_LONG_ARG = "--show-output";
  private static final String SHOW_OUTPUTS_LONG_ARG = "--show-outputs";
  private static final String SHOW_FULL_OUTPUT_LONG_ARG = "--show-full-output";
  private static final String SHOW_JSON_OUTPUT_LONG_ARG = "--show-json-output";
  private static final String SHOW_FULL_JSON_OUTPUT_LONG_ARG = "--show-full-json-output";
  private static final String SHOW_RULEKEY_LONG_ARG = "--show-rulekey";
  private static final String LOCAL_BUILD_LONG_ARG = "--local";
  static final String BUCK_BINARY_STRING_ARG = "--buck-binary";
  private static final String RULEKEY_LOG_PATH_LONG_ARG = "--rulekeys-log-path";

  private static final String OUTPUT_RULE_DEPS_TO_FILE_ARG = "--output-rule-deps-to-file";
  private static final String ACTION_GRAPH_FILE_NAME = "action_graph.json";
  private static final String RULE_EXEC_TIME_FILE_NAME = "rule_exec_time.json";

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

  /**
   * Enum with values for `--output-format` CLI parameter. Only applies when --show-outputs is used.
   */
  private enum OutputFormat {
    DEFAULT,
    FULL,
    JSON,
    FULL_JSON,
  }

  @Option(
      name = "--output-format",
      usage =
          "Output format (default: list).\n"
              + " default -  output paths are printed relative to the cell.\n"
              + " full - output paths are printed as absolute paths.\n"
              + " json - JSON format with relative paths\n"
              + " full_json - JSON format with absolute paths.\n")
  private OutputFormat outputFormat = OutputFormat.DEFAULT;

  @Option(
      name = SHOW_OUTPUTS_LONG_ARG,
      usage = "Print the path to the outputs for each of the built rules relative to the cell.")
  private boolean showOutputs;

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

  @Option(name = LOCAL_BUILD_LONG_ARG, usage = "Disable remote execution for this build.")
  private boolean forceDisableRemoteExecution = false;

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

  @Option(
      name = OUTPUT_RULE_DEPS_TO_FILE_ARG,
      usage = "Serialize rule dependencies and execution time to the log directory")
  private boolean outputRuleDeps = false;

  @Argument private List<String> arguments = new ArrayList<>();

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

  public boolean isRemoteExecutionForceDisabled() {
    return forceDisableRemoteExecution;
  }

  /** @return an absolute path or {@link Optional#empty()}. */
  public Optional<Path> getPathToBuildReport(BuckConfig buckConfig) {
    return Optional.ofNullable(
        buckConfig.resolvePathThatMayBeOutsideTheProjectFilesystem(buildReport));
  }

  private final AtomicReference<Build> lastBuild = new AtomicReference<>(null);
  private final SettableFuture<ParallelRuleKeyCalculator<RuleKey>> localRuleKeyCalculator =
      SettableFuture.create();

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    return runWithoutHelpInternal(params).getExitCode();
  }

  BuildRunResult runWithoutHelpInternal(CommandRunnerParams params) throws Exception {
    assertArguments(params);

    BuckEventBus buckEventBus = params.getBuckEventBus();
    if (outputRuleDeps) {
      FileSerializationOutputRuleDepsListener fileSerializationOutputRuleDepsListener =
          new FileSerializationOutputRuleDepsListener(
              getLogDirectoryPath(params).resolve(RULE_EXEC_TIME_FILE_NAME));
      buckEventBus.register(fileSerializationOutputRuleDepsListener);
    }

    try (CommandThreadManager pool =
            new CommandThreadManager("Build", getConcurrencyLimit(params.getBuckConfig()));
        BuildPrehook prehook = getPrehook(new ListeningProcessExecutor(), params)) {
      prehook.startPrehookScript();
      return run(params, pool, Function.identity(), ImmutableSet.of());
    }
  }

  private Path getLogDirectoryPath(CommandRunnerParams params) {
    InvocationInfo invocationInfo = params.getInvocationInfo().get();
    Path logDirectoryPath = invocationInfo.getLogDirectoryPath();
    ProjectFilesystem filesystem = params.getCells().getRootCell().getFilesystem();
    return filesystem.resolve(logDirectoryPath);
  }

  BuildPrehook getPrehook(ListeningProcessExecutor processExecutor, CommandRunnerParams params) {
    return new BuildPrehook(
        processExecutor,
        params.getCells().getRootCell(),
        params.getBuckEventBus(),
        params.getBuckConfig(),
        params.getEnvironment(),
        getArguments());
  }

  /** @throws CommandLineException if arguments provided are incorrect */
  protected void assertArguments(CommandRunnerParams params) {
    if (!getArguments().isEmpty()) {
      return;
    }
    String message =
        "Must specify at least one build target. See https://buck.build/concept/build_target_pattern.html";
    ImmutableSet<String> aliases = AliasConfig.from(params.getBuckConfig()).getAliases().keySet();
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
      Function<ImmutableList<TargetNodeSpec>, ImmutableList<TargetNodeSpec>> targetNodeSpecEnhancer,
      ImmutableSet<String> additionalTargets)
      throws Exception {
    if (showOutput) {
      CommandHelper.maybePrintShowOutputWarning(
          params.getBuckConfig().getView(CliConfig.class),
          params.getConsole().getAnsi(),
          params.getBuckEventBus());
    }
    if (!additionalTargets.isEmpty()) {
      this.arguments.addAll(additionalTargets);
    }
    BuildEvent.Started started = postBuildStartedEvent(params);
    BuildRunResult result = ImmutableBuildRunResult.of(ExitCode.BUILD_ERROR, ImmutableList.of());
    try {
      result = executeBuildAndProcessResult(params, commandThreadManager, targetNodeSpecEnhancer);
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

  GraphsAndBuildTargets createGraphsAndTargets(
      CommandRunnerParams params,
      ListeningExecutorService executorService,
      Function<ImmutableList<TargetNodeSpec>, ImmutableList<TargetNodeSpec>> targetNodeSpecEnhancer,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger)
      throws ActionGraphCreationException, IOException, InterruptedException {
    ImmutableList<TargetNodeSpec> specs;
    try {
      specs =
          targetNodeSpecEnhancer.apply(
              parseArgumentsAsTargetNodeSpecs(
                  params.getCells().getRootCell(),
                  params.getClientWorkingDir(),
                  getArguments(),
                  params.getBuckConfig()));
    } catch (BuildTargetException e) {
      throw new ActionGraphCreationException(MoreExceptions.getHumanReadableOrLocalizedMessage(e));
    }
    TargetGraphCreationResult unversionedTargetGraph =
        createUnversionedTargetGraph(params, executorService, specs);

    Optional<TargetGraphCreationResult> versionedTargetGraph = Optional.empty();
    try {
      if (params.getBuckConfig().getView(BuildBuckConfig.class).getBuildVersions()) {
        versionedTargetGraph = Optional.of(toVersionedTargetGraph(params, unversionedTargetGraph));
      }
    } catch (VersionException e) {
      throw new ActionGraphCreationException(MoreExceptions.getHumanReadableOrLocalizedMessage(e));
    }

    TargetGraphCreationResult targetGraphForLocalBuild =
        ActionAndTargetGraphs.getTargetGraph(unversionedTargetGraph, versionedTargetGraph);
    checkSingleBuildTargetSpecifiedForOutBuildMode(targetGraphForLocalBuild);
    ActionGraphAndBuilder actionGraph =
        createActionGraphAndResolver(params, targetGraphForLocalBuild, ruleKeyLogger);

    ImmutableSet<BuildTargetWithOutputs> buildTargetsWithOutputs =
        justBuildTarget == null
            ? matchBuildTargetsWithLabelsFromSpecs(
                specs, targetGraphForLocalBuild.getBuildTargets())
            : getBuildTargetsWithOutputsForJustBuild(
                params, params.getTargetConfiguration(), actionGraph, justBuildTarget);

    ActionAndTargetGraphs actionAndTargetGraphs =
        ActionAndTargetGraphs.of(unversionedTargetGraph, versionedTargetGraph, actionGraph);

    return ImmutableGraphsAndBuildTargets.of(actionAndTargetGraphs, buildTargetsWithOutputs);
  }

  private ImmutableSet<BuildTargetWithOutputs> getBuildTargetsWithOutputsForJustBuild(
      CommandRunnerParams params,
      Optional<TargetConfiguration> targetConfiguration,
      ActionGraphAndBuilder actionGraphAndBuilder,
      String justBuildTarget)
      throws ActionGraphCreationException {
    BuildTargetOutputLabelParser.TargetWithOutputLabel targetWithOutputLabel =
        BuildTargetOutputLabelParser.getBuildTargetNameWithOutputLabel(justBuildTarget);
    BuildTarget explicitTarget =
        params
            .getUnconfiguredBuildTargetFactory()
            .create(
                targetWithOutputLabel.getTargetName(),
                params.getCells().getRootCell().getCellNameResolver())
            // TODO(nga): ignores default_target_platform and configuration detector
            .configure(targetConfiguration.orElse(UnconfiguredTargetConfiguration.INSTANCE));
    Iterable<BuildRule> actionGraphRules =
        Objects.requireNonNull(actionGraphAndBuilder.getActionGraph().getNodes());
    if (!Iterables.any(actionGraphRules, rule -> explicitTarget.equals(rule.getBuildTarget()))) {
      throw new ActionGraphCreationException(
          "Targets specified via `--just-build` must be a subset of action graph.");
    }
    return ImmutableSet.of(
        BuildTargetWithOutputs.of(explicitTarget, targetWithOutputLabel.getOutputLabel()));
  }

  private void checkSingleBuildTargetSpecifiedForOutBuildMode(
      TargetGraphCreationResult targetGraphAndBuildTargets) {
    if (outputPathForSingleBuildTarget != null
        && targetGraphAndBuildTargets.getBuildTargets().size() != 1) {
      throw new CommandLineException(
          String.format(
              "When using %s you must specify exactly one build target, but you specified %s",
              OUT_LONG_ARG, targetGraphAndBuildTargets.getBuildTargets()));
    }
  }

  private BuildRunResult executeBuildAndProcessResult(
      CommandRunnerParams params,
      CommandThreadManager commandThreadManager,
      Function<ImmutableList<TargetNodeSpec>, ImmutableList<TargetNodeSpec>> targetNodeSpecEnhancer)
      throws Exception {
    ExitCode exitCode;
    GraphsAndBuildTargets graphsAndBuildTargets;
    try (ThriftRuleKeyLogger ruleKeyLogger = createRuleKeyLogger().orElse(null)) {
      Optional<ThriftRuleKeyLogger> optionalRuleKeyLogger = Optional.ofNullable(ruleKeyLogger);
      graphsAndBuildTargets =
          createGraphsAndTargets(
              params,
              commandThreadManager.getListeningExecutorService(),
              targetNodeSpecEnhancer,
              optionalRuleKeyLogger);

      if (outputRuleDeps) {
        ActionGraphBuilder actionGraphBuilder =
            graphsAndBuildTargets.getGraphs().getActionGraphAndBuilder().getActionGraphBuilder();
        ImmutableSet<BuildTarget> buildTargets = graphsAndBuildTargets.getBuildTargets();
        Path outputPath = getLogDirectoryPath(params).resolve(ACTION_GRAPH_FILE_NAME);
        new ActionGraphSerializer(actionGraphBuilder, buildTargets, outputPath).serialize();
      }

      try (RuleKeyCacheScope<RuleKey> ruleKeyCacheScope =
          getDefaultRuleKeyCacheScope(
              params, graphsAndBuildTargets.getGraphs().getActionGraphAndBuilder())) {
        exitCode =
            executeLocalBuild(
                params,
                graphsAndBuildTargets,
                commandThreadManager.getWeightedListeningExecutorService(),
                optionalRuleKeyLogger,
                Optional.empty(),
                ruleKeyCacheScope,
                lastBuild);
        if (exitCode == ExitCode.SUCCESS) {
          exitCode = processSuccessfulBuild(params, graphsAndBuildTargets, ruleKeyCacheScope);
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

  ExitCode processSuccessfulBuild(
      CommandRunnerParams params,
      GraphsAndBuildTargets graphsAndBuildTargets,
      RuleKeyCacheScope<RuleKey> ruleKeyCacheScope)
      throws IOException {
    BuildBuckConfig buildBuckConfig = params.getBuckConfig().getView(BuildBuckConfig.class);
    if (buildBuckConfig.createBuildOutputSymLinksEnabled()) {
      symLinkBuildResults(params, graphsAndBuildTargets);
    }
    if (buildBuckConfig.shouldBuckOutIncludeTargetConfigHash()
        && !buildBuckConfig.getHashedBuckOutLinkMode().equals(HashedBuckOutLinkMode.NONE)) {
      linkBuildResultsToHashedBuckOut(params, graphsAndBuildTargets);
    }
    ActionAndTargetGraphs graphs = graphsAndBuildTargets.getGraphs();
    // TODO(irenewchen): Merge full output, JSON output, full JSON output into a enum
    if (showOutput
        || showOutputs
        || showFullOutput
        || showJsonOutput
        || showFullJsonOutput
        || showRuleKey) {
      if (outputFormat != OutputFormat.DEFAULT && !showOutputs) {
        params
            .getConsole()
            .printErrorText(String.format("--output-format can only be used with --show-outputs"));
        return ExitCode.BUILD_ERROR;
      }
      showOutputs(params, graphsAndBuildTargets, ruleKeyCacheScope);
    }
    if (outputPathForSingleBuildTarget != null) {
      BuildTarget loneTarget = Iterables.getOnlyElement(graphs.getTargetGraph().getBuildTargets());
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

        ProjectFilesystem projectFilesystem = params.getCells().getRootCell().getFilesystem();
        SourcePathResolverAdapter pathResolver =
            graphs.getActionGraphAndBuilder().getActionGraphBuilder().getSourcePathResolver();

        Path outputAbsPath = pathResolver.getAbsolutePath(output);
        if (projectFilesystem.isDirectory(outputAbsPath)) {
          if (projectFilesystem.isFile(outputPathForSingleBuildTarget)) {
            params
                .getConsole()
                .printErrorText(
                    "buck --out for targets outputting directory must be either nonexistent or a directory!");
            return ExitCode.BUILD_ERROR;
          }
          projectFilesystem.mkdirs(outputPathForSingleBuildTarget);
          projectFilesystem.mergeChildren(
              outputAbsPath,
              outputPathForSingleBuildTarget,
              java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } else {
          Path outputPath;
          if (Files.isDirectory(outputPathForSingleBuildTarget)) {
            Path outputDir = outputPathForSingleBuildTarget.normalize();
            Path outputFilename = outputAbsPath.getFileName();
            outputPath = outputDir.resolve(outputFilename);
          } else {
            outputPath = outputPathForSingleBuildTarget;
          }
          projectFilesystem.copyFile(outputAbsPath, outputPath);
        }
      }
    }
    return ExitCode.SUCCESS;
  }

  private void linkBuildResultsToHashedBuckOut(
      CommandRunnerParams params, GraphsAndBuildTargets graphsAndBuildTargets)
      throws IllegalStateException, IOException {
    BuildBuckConfig buildBuckConfig = params.getBuckConfig().getView(BuildBuckConfig.class);
    if (!buildBuckConfig.shouldBuckOutIncludeTargetConfigHash()) {
      throw new IllegalStateException(
          "buckconfig buck_out_include_target_config_hash must be true to "
              + "hardlink build results to hashed buck-out!");
    }
    ActionGraphBuilder graphBuilder =
        graphsAndBuildTargets.getGraphs().getActionGraphAndBuilder().getActionGraphBuilder();
    SourcePathResolverAdapter pathResolver = graphBuilder.getSourcePathResolver();

    for (BuildTargetWithOutputs buildTargetWithOutputs :
        graphsAndBuildTargets.getBuildTargetWithOutputs()) {
      BuildRule rule = graphBuilder.requireRule(buildTargetWithOutputs.getBuildTarget());
      linkRuleToHashedBuckOut(
          rule,
          pathResolver,
          buildBuckConfig.getBuckOutCompatLink(),
          buildTargetWithOutputs.getOutputLabel(),
          buildBuckConfig.getHashedBuckOutLinkMode());
    }
  }

  private void linkRuleToHashedBuckOut(
      BuildRule rule,
      SourcePathResolverAdapter pathResolver,
      boolean buckOutCompatLink,
      OutputLabel outputLabel,
      HashedBuckOutLinkMode linkMode)
      throws IOException {
    Optional<Path> outputPath =
        PathUtils.getUserFacingOutputPath(
            pathResolver, rule, buckOutCompatLink, outputLabel, showOutputs);
    if (!outputPath.isPresent()) {
      return;
    }
    Path absolutePathWithHash = outputPath.get().toAbsolutePath();
    Optional<Path> maybeAbsolutePathWithoutHash =
        BuildPaths.removeHashFrom(absolutePathWithHash, rule.getBuildTarget());
    if (!maybeAbsolutePathWithoutHash.isPresent()) {
      // hash was not found, for example `export_file` rule outputs files in source directory, not
      // in buck-out
      // so we don't create any links
      return;
    }
    Path absolutePathWithoutHash = maybeAbsolutePathWithoutHash.get();
    MostFiles.deleteRecursivelyIfExists(absolutePathWithoutHash);
    Files.createDirectories(absolutePathWithoutHash.getParent());

    switch (linkMode) {
      case SYMLINK:
        Files.createSymbolicLink(absolutePathWithoutHash, absolutePathWithHash);
        break;
      case HARDLINK:
        boolean isDirectory;
        try {
          isDirectory =
              Files.readAttributes(absolutePathWithHash, BasicFileAttributes.class).isDirectory();
        } catch (NoSuchFileException e) {
          // Rule did not produce a file.
          // It should not be possible, but it happens.
          return;
        }
        if (isDirectory) {
          Files.createSymbolicLink(absolutePathWithoutHash, absolutePathWithHash);
        } else {
          Files.createLink(absolutePathWithoutHash, absolutePathWithHash);
        }
        break;
      case NONE:
        break;
    }
  }

  private void symLinkBuildRuleResult(
      SourcePathResolverAdapter pathResolver,
      BuckConfig buckConfig,
      Path lastOutputDirPath,
      BuildRule rule,
      OutputLabel outputLabel)
      throws IOException {
    Optional<Path> outputPath =
        PathUtils.getUserFacingOutputPath(
            pathResolver,
            rule,
            buckConfig.getView(BuildBuckConfig.class).getBuckOutCompatLink(),
            outputLabel,
            showOutputs);
    if (outputPath.isPresent()) {
      Path absolutePath = outputPath.get();
      Path destPath;
      try {
        destPath = lastOutputDirPath.relativize(absolutePath);
      } catch (IllegalArgumentException e) {
        // Troubleshooting a potential issue with windows relativizing things
        String msg =
            String.format(
                "Could not relativize %s to %s: %s",
                absolutePath, lastOutputDirPath, e.getMessage());
        throw new IllegalArgumentException(msg, e);
      }
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
        params
            .getCells()
            .getRootCell()
            .getFilesystem()
            .getBuckPaths()
            .getLastOutputDir()
            .toAbsolutePath();
    MostFiles.deleteRecursivelyIfExists(lastOutputDirPath);
    Files.createDirectories(lastOutputDirPath);

    ActionGraphBuilder graphBuilder =
        graphsAndBuildTargets.getGraphs().getActionGraphAndBuilder().getActionGraphBuilder();
    SourcePathResolverAdapter pathResolver = graphBuilder.getSourcePathResolver();

    for (BuildTargetWithOutputs targetWithOutputs :
        graphsAndBuildTargets.getBuildTargetWithOutputs()) {
      BuildRule rule = graphBuilder.requireRule(targetWithOutputs.getBuildTarget());
      // If it's an apple bundle, we'd like to also link the dSYM file over here.
      if (rule instanceof AppleBundle) {
        AppleBundle bundle = (AppleBundle) rule;
        Optional<AppleDsym> dsym = bundle.getAppleDsym();
        if (dsym.isPresent()) {
          symLinkBuildRuleResult(
              pathResolver,
              params.getBuckConfig(),
              lastOutputDirPath,
              dsym.get(),
              targetWithOutputs.getOutputLabel());
        }
      }
      symLinkBuildRuleResult(
          pathResolver,
          params.getBuckConfig(),
          lastOutputDirPath,
          rule,
          targetWithOutputs.getOutputLabel());
    }
  }

  private void showOutputs(
      CommandRunnerParams params,
      GraphsAndBuildTargets graphsAndBuildTargets,
      RuleKeyCacheScope<RuleKey> ruleKeyCacheScope)
      throws IOException {
    TreeMap<String, String> sortedJsonOutputs = new TreeMap<>();
    Optional<DefaultRuleKeyFactory> ruleKeyFactory = Optional.empty();
    ActionGraphBuilder graphBuilder =
        graphsAndBuildTargets.getGraphs().getActionGraphAndBuilder().getActionGraphBuilder();
    if (showRuleKey) {
      RuleKeyFieldLoader fieldLoader = new RuleKeyFieldLoader(params.getRuleKeyConfiguration());
      ruleKeyFactory =
          Optional.of(
              new DefaultRuleKeyFactory(
                  fieldLoader,
                  params.getFileHashCache(),
                  graphBuilder,
                  ruleKeyCacheScope.getCache(),
                  Optional.empty()));
    }
    for (BuildTargetWithOutputs targetWithOutputs :
        graphsAndBuildTargets.getBuildTargetWithOutputs()) {
      BuildRule rule = graphBuilder.requireRule(targetWithOutputs.getBuildTarget());
      Optional<Path> outputPath =
          PathUtils.getUserFacingOutputPath(
                  graphBuilder.getSourcePathResolver(),
                  rule,
                  params.getBuckConfig().getView(BuildBuckConfig.class).getBuckOutCompatLink(),
                  targetWithOutputs.getOutputLabel(),
                  showOutputs)
              .map(
                  path ->
                      isShowOutputsPathAbsolute()
                          ? path
                          : params
                              .getCells()
                              .getRootCell()
                              .getFilesystem()
                              .relativize(path)
                              .getPath());

      params.getConsole().getStdOut().flush();
      if (isShowOutputsPathJsonFormat()) {
        sortedJsonOutputs.put(
            targetWithOutputs.toString(), outputPath.map(Object::toString).orElse(""));
      } else {
        params
            .getConsole()
            .getStdOut()
            .printf(
                "%s%s%s\n",
                targetWithOutputs,
                showRuleKey ? " " + ruleKeyFactory.get().build(rule) : "",
                showOutput || showOutputs || showFullOutput ? getOutputPathToShow(outputPath) : "");
      }
    }

    if (isShowOutputsPathJsonFormat()) {
      // Print the build rule information as JSON.
      StringWriter stringWriter = new StringWriter();
      ObjectMappers.WRITER.withDefaultPrettyPrinter().writeValue(stringWriter, sortedJsonOutputs);
      String output = stringWriter.getBuffer().toString();
      params.getConsole().getStdOut().println(output);
    }
  }

  private boolean isShowOutputsPathAbsolute() {
    return showFullOutput
        || showFullJsonOutput
        || showOutputs
            && (outputFormat == OutputFormat.FULL || outputFormat == OutputFormat.FULL_JSON);
  }

  private boolean isShowOutputsPathJsonFormat() {
    return showJsonOutput
        || showFullJsonOutput
        || showOutputs
            && (outputFormat == OutputFormat.JSON || outputFormat == OutputFormat.FULL_JSON);
  }

  private String getOutputPathToShow(Optional<Path> path) {
    return path.map(p -> p.toString().isEmpty() ? "" : " " + p.toString()).orElse("");
  }

  private TargetGraphCreationResult createUnversionedTargetGraph(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      ImmutableList<TargetNodeSpec> specs)
      throws IOException, InterruptedException, ActionGraphCreationException {
    // Parse the build files to create a ActionGraph.
    ParserConfig parserConfig = params.getBuckConfig().getView(ParserConfig.class);
    try {
      return params
          .getParser()
          .buildTargetGraphWithoutTopLevelConfigurationTargets(
              createParsingContext(params.getCells().getRootCell(), executor)
                  .withSpeculativeParsing(SpeculativeParsing.ENABLED)
                  .withApplyDefaultFlavorsMode(parserConfig.getDefaultFlavorsMode()),
              specs,
              params.getTargetConfiguration());
    } catch (BuildTargetException e) {
      throw new ActionGraphCreationException(MoreExceptions.getHumanReadableOrLocalizedMessage(e));
    }
  }

  private static ActionGraphAndBuilder createActionGraphAndResolver(
      CommandRunnerParams params,
      TargetGraphCreationResult targetGraphAndBuildTargets,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger) {
    return params
        .getActionGraphProvider()
        .getActionGraph(
            new DefaultTargetNodeToBuildRuleTransformer(),
            targetGraphAndBuildTargets,
            ruleKeyLogger);
  }

  protected ExitCode executeLocalBuild(
      CommandRunnerParams params,
      GraphsAndBuildTargets graphsAndBuildTargets,
      WeightedListeningExecutorService executor,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger,
      Optional<CountDownLatch> initializeBuildLatch,
      RuleKeyCacheScope<RuleKey> ruleKeyCacheScope,
      AtomicReference<Build> buildReference)
      throws Exception {

    ActionGraphAndBuilder actionGraphAndBuilder =
        graphsAndBuildTargets.getGraphs().getActionGraphAndBuilder();
    boolean remoteExecutionAutoEnabled =
        params
            .getBuckConfig()
            .getView(RemoteExecutionConfig.class)
            .isRemoteExecutionAutoEnabled(
                params.getBuildEnvironmentDescription().getUser(), getArguments());
    BuildExecutor builder =
        new BuildExecutor(
            params.createBuilderArgs(),
            getExecutionContext(),
            actionGraphAndBuilder,
            new LocalCachingBuildEngineDelegate(params.getFileHashCache()),
            executor,
            isKeepGoing(),
            ruleKeyCacheScope,
            getBuildEngineMode(),
            ruleKeyLogger,
            params.getMetadataProvider(),
            params.getTargetConfigurationSerializer(),
            remoteExecutionAutoEnabled,
            isRemoteExecutionForceDisabled());
    // TODO(buck_team): use try-with-resources instead
    try {
      buildReference.set(builder.getBuild());
      localRuleKeyCalculator.set(builder.getCachingBuildEngine().getRuleKeyCalculator());

      // Signal to other threads that lastBuild has now been set.
      initializeBuildLatch.ifPresent(CountDownLatch::countDown);

      Iterable<BuildTarget> targets =
          FluentIterable.concat(
              graphsAndBuildTargets.getBuildTargets(),
              getAdditionalTargetsToBuild(graphsAndBuildTargets));

      return builder.buildTargets(targets, getPathToBuildReport(params.getBuckConfig()));
    } finally {
      builder.shutdown();
    }
  }

  RuleKeyCacheScope<RuleKey> getDefaultRuleKeyCacheScope(
      CommandRunnerParams params, ActionGraphAndBuilder actionGraphAndBuilder) {
    return getDefaultRuleKeyCacheScope(
        params,
        new RuleKeyCacheRecycler.SettingsAffectingCache(
            params.getBuckConfig().getView(BuildBuckConfig.class).getKeySeed(),
            actionGraphAndBuilder.getActionGraph()));
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
    return Objects.requireNonNull(lastBuild.get());
  }

  @Override
  public String getShortDescription() {
    return "builds the specified target";
  }

  @Override
  public Iterable<BuckEventListener> getEventListeners(
      Map<ExecutorPool, ListeningExecutorService> executorPool,
      ScheduledExecutorService scheduledExecutorService) {
    return ImmutableList.of();
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

  @BuckStyleValue
  interface GraphsAndBuildTargets {
    ActionAndTargetGraphs getGraphs();

    ImmutableSet<BuildTargetWithOutputs> getBuildTargetWithOutputs();

    @Value.Lazy
    default ImmutableSet<BuildTarget> getBuildTargets() {
      ImmutableSet.Builder<BuildTarget> builder =
          ImmutableSet.builderWithExpectedSize(getBuildTargetWithOutputs().size());
      getBuildTargetWithOutputs()
          .forEach(targetWithOutputs -> builder.add(targetWithOutputs.getBuildTarget()));
      return builder.build();
    }
  }

  @BuckStyleValue
  interface BuildRunResult {
    ExitCode getExitCode();

    ImmutableSet<BuildTarget> getBuildTargets();
  }
}
