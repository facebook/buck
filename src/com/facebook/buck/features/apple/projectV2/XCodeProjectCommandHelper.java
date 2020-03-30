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

package com.facebook.buck.features.apple.projectV2;

import com.facebook.buck.apple.AppleBinaryDescription;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleBundleDescriptionArg;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.XCodeDescriptions;
import com.facebook.buck.apple.XCodeDescriptionsFactory;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.cli.ProjectTestsMode;
import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellProvider;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.targetgraph.NoSuchTargetException;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphCreationResult;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.config.registry.impl.ConfigurationRuleRegistryFactory;
import com.facebook.buck.core.rules.resolver.impl.MultiThreadedActionGraphBuilder;
import com.facebook.buck.core.rules.transformer.impl.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.util.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.core.util.graph.CycleException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.impl.LegacyToolchainProvider;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.features.apple.common.PathOutputPresenter;
import com.facebook.buck.features.apple.common.XcodeWorkspaceConfigDescription;
import com.facebook.buck.features.apple.common.XcodeWorkspaceConfigDescriptionArg;
import com.facebook.buck.features.halide.HalideBuckConfig;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParsingContext;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.parser.spec.TargetNodeSpec;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.util.CloseableMemoizedSupplier;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.ProcessManager;
import com.facebook.buck.util.collect.MoreSets;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.versions.InstrumentedVersionedTargetGraphCache;
import com.facebook.buck.versions.VersionException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.concurrent.ThreadSafe;
import org.pf4j.PluginManager;

public class XCodeProjectCommandHelper {

  private static final Logger LOG = Logger.get(XCodeProjectCommandHelper.class);

  private static final String XCODE_PROCESS_NAME = "Xcode";

  private final BuckEventBus buckEventBus;
  private final PluginManager pluginManager;
  private final Parser parser;
  private final BuckConfig buckConfig;
  private final InstrumentedVersionedTargetGraphCache versionedTargetGraphCache;
  private final TypeCoercerFactory typeCoercerFactory;
  private final UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory;
  private final Cell cell;
  private final Optional<TargetConfiguration> targetConfiguration;
  private final ImmutableSet<Flavor> appleCxxFlavors;
  private final RuleKeyConfiguration ruleKeyConfiguration;
  private final Console console;
  private final Optional<ProcessManager> processManager;
  private final ImmutableMap<String, String> environment;
  private final ListeningExecutorService executorService;
  private final List<String> arguments;
  private final boolean sharedLibrariesInBundles;
  private final boolean withTests;
  private final boolean withoutTests;
  private final boolean withoutDependenciesTests;
  private final boolean createProjectSchemes;
  private final boolean dryRun;
  private final boolean readOnly;
  private final PathOutputPresenter outputPresenter;
  private final ParsingContext parsingContext;

  private final Function<Iterable<String>, ImmutableList<TargetNodeSpec>> argsParser;
  private final Function<ImmutableList<String>, ExitCode> buildRunner;
  private final Supplier<DepsAwareExecutor<? super ComputeResult, ?>> depsAwareExecutorSupplier;

  private final FocusedTargetMatcher focusedTargetMatcher;

  public XCodeProjectCommandHelper(
      BuckEventBus buckEventBus,
      PluginManager pluginManager,
      Parser parser,
      BuckConfig buckConfig,
      InstrumentedVersionedTargetGraphCache versionedTargetGraphCache,
      TypeCoercerFactory typeCoercerFactory,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory,
      Cell cell,
      RuleKeyConfiguration ruleKeyConfiguration,
      Optional<TargetConfiguration> targetConfiguration,
      Console console,
      Optional<ProcessManager> processManager,
      ImmutableMap<String, String> environment,
      ListeningExecutorService executorService,
      ListeningExecutorService parsingExecutorService,
      CloseableMemoizedSupplier<DepsAwareExecutor<? super ComputeResult, ?>> depsAwareExecutor,
      ImmutableSet<Flavor> appleCxxFlavors,
      boolean sharedLibrariesInBundles,
      boolean enableParserProfiling,
      boolean withTests,
      boolean withoutTests,
      boolean withoutDependenciesTests,
      String focus,
      boolean createProjectSchemes,
      boolean dryRun,
      boolean readOnly,
      PathOutputPresenter outputPresenter,
      Function<Iterable<String>, ImmutableList<TargetNodeSpec>> argsParser,
      Function<ImmutableList<String>, ExitCode> buildRunner,
      List<String> arguments) {
    this.buckEventBus = buckEventBus;
    this.pluginManager = pluginManager;
    this.parser = parser;
    this.buckConfig = buckConfig;
    this.versionedTargetGraphCache = versionedTargetGraphCache;
    this.typeCoercerFactory = typeCoercerFactory;
    this.unconfiguredBuildTargetFactory = unconfiguredBuildTargetFactory;
    this.cell = cell;
    this.targetConfiguration = targetConfiguration;
    this.depsAwareExecutorSupplier = depsAwareExecutor;
    this.appleCxxFlavors = appleCxxFlavors;
    this.ruleKeyConfiguration = ruleKeyConfiguration;
    this.console = console;
    this.processManager = processManager;
    this.environment = environment;
    this.executorService = executorService;
    this.arguments = arguments;
    this.sharedLibrariesInBundles = sharedLibrariesInBundles;
    this.withTests = withTests;
    this.withoutTests = withoutTests;
    this.withoutDependenciesTests = withoutDependenciesTests;
    this.createProjectSchemes = createProjectSchemes;
    this.dryRun = dryRun;
    this.readOnly = readOnly;
    this.outputPresenter = outputPresenter;
    this.argsParser = argsParser;
    this.buildRunner = buildRunner;
    this.parsingContext =
        ParsingContext.builder(cell, parsingExecutorService)
            .setProfilingEnabled(enableParserProfiling)
            .setSpeculativeParsing(SpeculativeParsing.ENABLED)
            .setApplyDefaultFlavorsMode(
                buckConfig.getView(ParserConfig.class).getDefaultFlavorsMode())
            .build();

    this.focusedTargetMatcher = new FocusedTargetMatcher(focus, cell.getCellNameResolver());
  }

  public ExitCode parseTargetsAndRunXCodeGenerator() throws IOException, InterruptedException {
    TargetGraphCreationResult targetGraphCreationResult;

    LOG.debug("EXPERIMENTAL XCODE PROJECT GENERATION");

    LOG.debug("Xcode project generation: Getting the target graph");

    try {
      ImmutableSet<BuildTarget> passedInTargetsSet =
          ImmutableSet.copyOf(
              Iterables.concat(
                  parser.resolveTargetSpecs(
                      parsingContext,
                      argsParser.apply(arguments.isEmpty() ? ImmutableList.of("//...") : arguments),
                      targetConfiguration)));
      if (passedInTargetsSet.isEmpty()) {
        throw new HumanReadableException("Could not find targets matching arguments");
      }
      targetGraphCreationResult = parser.buildTargetGraph(parsingContext, passedInTargetsSet);
      if (arguments.isEmpty()) {
        targetGraphCreationResult =
            targetGraphCreationResult.withBuildTargets(
                getRootsFromPredicate(
                    targetGraphCreationResult.getTargetGraph(),
                    node -> node.getDescription() instanceof XcodeWorkspaceConfigDescription));
      }
    } catch (BuildFileParseException e) {
      buckEventBus.post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return ExitCode.PARSE_ERROR;
    } catch (HumanReadableException e) {
      buckEventBus.post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return ExitCode.BUILD_ERROR;
    }

    LOG.debug("Xcode project generation: Killing existing Xcode if needed");

    checkForAndKillXcodeIfRunning(getIDEForceKill(buckConfig));

    LOG.debug("Xcode project generation: Getting more part of the target graph");

    try {
      targetGraphCreationResult =
          enhanceTargetGraphIfNeeded(
              depsAwareExecutorSupplier,
              targetGraphCreationResult,
              isWithTests(buckConfig),
              isWithDependenciesTests(buckConfig));
    } catch (BuildFileParseException | NoSuchTargetException | VersionException e) {
      buckEventBus.post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return ExitCode.PARSE_ERROR;
    } catch (HumanReadableException e) {
      buckEventBus.post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return ExitCode.BUILD_ERROR;
    }

    Optional<ImmutableMap<BuildTarget, TargetNode<?>>> sharedLibraryToBundle = Optional.empty();

    if (sharedLibrariesInBundles) {
      sharedLibraryToBundle =
          Optional.of(
              computeSharedLibrariesToBundles(
                  targetGraphCreationResult.getTargetGraph().getNodes(),
                  targetGraphCreationResult.getTargetGraph()));
    }

    if (dryRun) {
      for (TargetNode<?> targetNode : targetGraphCreationResult.getTargetGraph().getNodes()) {
        console.getStdOut().println(targetNode.toString());
      }

      return ExitCode.SUCCESS;
    }

    LOG.debug("Xcode project generation: Run the project generator");

    return runXcodeProjectGenerator(targetGraphCreationResult, sharedLibraryToBundle);
  }

  /** Generate a mapping from libraries to the framework bundles that include them. */
  static ImmutableMap<BuildTarget, TargetNode<?>> computeSharedLibrariesToBundles(
      ImmutableSet<TargetNode<?>> targetNodes, TargetGraph targetGraph)
      throws HumanReadableException {

    Map<BuildTarget, TargetNode<?>> sharedLibraryToBundle = new HashMap<>();
    for (TargetNode<?> targetNode : targetNodes) {
      Optional<TargetNode<CxxLibraryDescription.CommonArg>> binaryNode =
          TargetNodes.castArg(targetNode, AppleBundleDescriptionArg.class)
              .flatMap(bundleNode -> bundleNode.getConstructorArg().getBinary())
              .map(target -> targetGraph.get(target))
              .flatMap(node -> TargetNodes.castArg(node, CxxLibraryDescription.CommonArg.class));
      if (!binaryNode.isPresent()) {
        continue;
      }
      CxxLibraryDescription.CommonArg arg = binaryNode.get().getConstructorArg();
      if (arg.getPreferredLinkage().equals(Optional.of(NativeLinkableGroup.Linkage.SHARED))) {
        BuildTarget binaryBuildTargetWithoutFlavors =
            binaryNode.get().getBuildTarget().withoutFlavors();
        if (sharedLibraryToBundle.containsKey(binaryBuildTargetWithoutFlavors)) {
          throw new HumanReadableException(
              String.format(
                  "Library %s is declared as the 'binary' of multiple bundles:\n first bundle: %s\n second bundle: %s",
                  binaryBuildTargetWithoutFlavors,
                  sharedLibraryToBundle.get(binaryBuildTargetWithoutFlavors).getBuildTarget(),
                  targetNode.getBuildTarget()));
        } else {
          sharedLibraryToBundle.put(binaryBuildTargetWithoutFlavors, targetNode);
        }
      }
    }
    return ImmutableMap.copyOf(sharedLibraryToBundle);
  }

  private static String getIDEForceKillSectionName() {
    return "project";
  }

  private static String getIDEForceKillFieldName() {
    return "ide_force_kill";
  }

  private IDEForceKill getIDEForceKill(BuckConfig buckConfig) {
    Optional<IDEForceKill> forceKill =
        buckConfig.getEnum(
            getIDEForceKillSectionName(), getIDEForceKillFieldName(), IDEForceKill.class);
    if (forceKill.isPresent()) {
      return forceKill.get();
    }

    // Support legacy config if new key is missing.
    Optional<Boolean> legacyPrompty = buckConfig.getBoolean("project", "ide_prompt");
    if (legacyPrompty.isPresent()) {
      return legacyPrompty.get().booleanValue() ? IDEForceKill.PROMPT : IDEForceKill.NEVER;
    }

    return IDEForceKill.PROMPT;
  }

  private ProjectTestsMode getXcodeProjectTestsMode(BuckConfig buckConfig) {
    return buckConfig
        .getEnum("project", "xcode_project_tests_mode", ProjectTestsMode.class)
        .orElse(ProjectTestsMode.WITH_TESTS);
  }

  private ProjectTestsMode testsMode(BuckConfig buckConfig) {
    ProjectTestsMode parameterMode = getXcodeProjectTestsMode(buckConfig);

    if (withoutTests) {
      parameterMode = ProjectTestsMode.WITHOUT_TESTS;
    } else if (withoutDependenciesTests) {
      parameterMode = ProjectTestsMode.WITHOUT_DEPENDENCIES_TESTS;
    } else if (withTests) {
      parameterMode = ProjectTestsMode.WITH_TESTS;
    }

    return parameterMode;
  }

  private boolean isWithTests(BuckConfig buckConfig) {
    return testsMode(buckConfig) != ProjectTestsMode.WITHOUT_TESTS;
  }

  private boolean isWithDependenciesTests(BuckConfig buckConfig) {
    return testsMode(buckConfig) == ProjectTestsMode.WITH_TESTS;
  }

  /** Run xcode specific project generation actions. */
  private ExitCode runXcodeProjectGenerator(
      TargetGraphCreationResult targetGraphCreationResult,
      Optional<ImmutableMap<BuildTarget, TargetNode<?>>> sharedLibraryToBundle)
      throws IOException, InterruptedException {
    ExitCode exitCode = ExitCode.SUCCESS;
    AppleConfig appleConfig = buckConfig.getView(AppleConfig.class);
    ProjectGeneratorOptions options =
        ProjectGeneratorOptions.builder()
            .setShouldGenerateReadOnlyFiles(readOnly)
            .setShouldIncludeTests(isWithTests(buckConfig))
            .setShouldIncludeDependenciesTests(isWithDependenciesTests(buckConfig))
            .setShouldAddLinkedLibrariesAsFlags(appleConfig.shouldAddLinkedLibrariesAsFlags())
            .setShouldForceLoadLinkWholeLibraries(
                appleConfig.shouldAddLinkerFlagsForLinkWholeLibraries())
            .setShouldGenerateMissingUmbrellaHeader(
                appleConfig.shouldGenerateMissingUmbrellaHeaders())
            .setShouldUseShortNamesForTargets(true)
            .setShouldGenerateProjectSchemes(createProjectSchemes)
            .build();

    LOG.debug("Xcode project generation: Generates workspaces for targets");

    ImmutableList<Result> results =
        generateWorkspacesForTargets(
            buckEventBus,
            pluginManager,
            cell,
            buckConfig,
            ruleKeyConfiguration,
            executorService,
            targetGraphCreationResult,
            options,
            appleCxxFlavors,
            focusedTargetMatcher,
            sharedLibraryToBundle);
    ImmutableSet<BuildTarget> requiredBuildTargets =
        results.stream()
            .flatMap(b -> b.getBuildTargets().stream())
            .collect(ImmutableSet.toImmutableSet());
    if (!requiredBuildTargets.isEmpty()) {
      ImmutableList<String> arguments =
          RichStream.from(requiredBuildTargets)
              .map(
                  target -> {
                    // TODO(T47190884): Use our NewCellPathResolver to look up the path.
                    if (!target.getCell().equals(cell.getCanonicalName())) {
                      CanonicalCellName cellName = target.getCell();

                      return target.withUnflavoredBuildTarget(
                          UnflavoredBuildTarget.of(
                              cellName, target.getBaseName(), target.getShortName()));
                    } else {
                      return target;
                    }
                  })
              .map(Object::toString)
              .toImmutableList();
      exitCode = buildRunner.apply(arguments);
    }

    // Write all output paths to stdout if requested.
    // IMPORTANT: this shuts down RenderingConsole since it writes to stdout.
    // (See DirtyPrintStreamDecorator and note how RenderingConsole uses it.)
    // Thus this must be the *last* thing we do, or we disable progress UI.
    //
    // This is still not the "right" way to do this; we should probably use
    // RenderingConsole#printToStdOut since it ensures we do one last render.
    for (Result result : results) {
      outputPresenter.present(
          result.inputTarget.getFullyQualifiedName(), result.outputRelativePath);
    }

    return exitCode;
  }

  /** A result with metadata about the subcommand helper's output. */
  public static class Result {
    private final BuildTarget inputTarget;
    private final Path outputRelativePath;
    private final PBXProject project;
    private final ImmutableSet<BuildTarget> buildTargets;

    public Result(
        BuildTarget inputTarget,
        Path outputRelativePath,
        PBXProject project,
        ImmutableSet<BuildTarget> buildTargets) {
      this.inputTarget = inputTarget;
      this.outputRelativePath = outputRelativePath;
      this.project = project;
      this.buildTargets = buildTargets;
    }

    public BuildTarget getInputTarget() {
      return inputTarget;
    }

    public Path getOutputRelativePath() {
      return outputRelativePath;
    }

    public PBXProject getProject() {
      return project;
    }

    public ImmutableSet<BuildTarget> getBuildTargets() {
      return buildTargets;
    }
  }

  @VisibleForTesting
  static ImmutableList<Result> generateWorkspacesForTargets(
      BuckEventBus buckEventBus,
      PluginManager pluginManager,
      Cell cell,
      BuckConfig buckConfig,
      RuleKeyConfiguration ruleKeyConfiguration,
      ListeningExecutorService executorService,
      TargetGraphCreationResult targetGraphCreationResult,
      ProjectGeneratorOptions options,
      ImmutableSet<Flavor> appleCxxFlavors,
      FocusedTargetMatcher focusedTargetMatcher, // @audited(chatatap)]
      Optional<ImmutableMap<BuildTarget, TargetNode<?>>> sharedLibraryToBundle)
      throws IOException, InterruptedException {

    LazyActionGraph lazyActionGraph =
        new LazyActionGraph(targetGraphCreationResult.getTargetGraph(), cell.getCellProvider());

    XCodeDescriptions xcodeDescriptions = XCodeDescriptionsFactory.create(pluginManager);

    LOG.debug(
        "Generating workspace for config targets %s", targetGraphCreationResult.getBuildTargets());
    ImmutableList.Builder<Result> generationResultsBuilder = ImmutableList.builder();
    for (BuildTarget inputTarget : targetGraphCreationResult.getBuildTargets()) {
      TargetNode<?> inputNode = targetGraphCreationResult.getTargetGraph().get(inputTarget);
      XcodeWorkspaceConfigDescriptionArg workspaceArgs;
      if (inputNode.getDescription() instanceof XcodeWorkspaceConfigDescription) {
        TargetNode<XcodeWorkspaceConfigDescriptionArg> castedWorkspaceNode =
            castToXcodeWorkspaceTargetNode(inputNode);
        workspaceArgs = castedWorkspaceNode.getConstructorArg();
      } else if (canGenerateImplicitWorkspaceForDescription(inputNode.getDescription())) {
        workspaceArgs = createImplicitWorkspaceArgs(inputNode);
      } else {
        throw new HumanReadableException(
            "%s must be a xcode_workspace_config, apple_binary, apple_bundle, or apple_library",
            inputNode);
      }

      AppleConfig appleConfig = buckConfig.getView(AppleConfig.class);
      HalideBuckConfig halideBuckConfig = new HalideBuckConfig(buckConfig);
      CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(buckConfig);
      SwiftBuckConfig swiftBuckConfig = new SwiftBuckConfig(buckConfig);

      CxxPlatformsProvider cxxPlatformsProvider =
          cell.getToolchainProvider()
              .getByName(
                  CxxPlatformsProvider.DEFAULT_NAME,
                  inputTarget.getTargetConfiguration(),
                  CxxPlatformsProvider.class);

      CxxPlatform defaultCxxPlatform =
          LegacyToolchainProvider.getLegacyTotallyUnsafe(
              cxxPlatformsProvider.getDefaultUnresolvedCxxPlatform());
      Cell workspaceCell = cell.getCell(inputTarget.getCell());
      WorkspaceAndProjectGenerator generator =
          new WorkspaceAndProjectGenerator(
              xcodeDescriptions,
              workspaceCell,
              targetGraphCreationResult.getTargetGraph(),
              workspaceArgs,
              inputTarget,
              options,
              focusedTargetMatcher,
              !appleConfig.getXcodeDisableParallelizeBuild(),
              defaultCxxPlatform,
              appleCxxFlavors,
              buckConfig.getView(ParserConfig.class).getBuildFileName(),
              lazyActionGraph::getActionGraphBuilderWhileRequiringSubgraph,
              buckEventBus,
              ruleKeyConfiguration,
              halideBuckConfig,
              cxxBuckConfig,
              appleConfig,
              swiftBuckConfig,
              sharedLibraryToBundle);
      Objects.requireNonNull(
          executorService, "CommandRunnerParams does not have executor for PROJECT pool");
      WorkspaceAndProjectGenerator.Result result =
          generator.generateWorkspaceAndDependentProjects(executorService);

      ImmutableSet<BuildTarget> requiredBuildTargetsForWorkspace =
          generator.getRequiredBuildTargets();
      LOG.verbose(
          "Required build targets for workspace %s: %s",
          inputTarget, requiredBuildTargetsForWorkspace);

      Path absolutePath = workspaceCell.getFilesystem().resolve(result.workspacePath);
      RelPath relativePath = cell.getFilesystem().relativize(absolutePath);

      generationResultsBuilder.add(
          new Result(
              inputTarget,
              relativePath.getPath(),
              result.project,
              requiredBuildTargetsForWorkspace));
    }

    return generationResultsBuilder.build();
  }

  @SuppressWarnings(value = "unchecked")
  private static TargetNode<XcodeWorkspaceConfigDescriptionArg> castToXcodeWorkspaceTargetNode(
      TargetNode<?> targetNode) {
    Preconditions.checkArgument(
        targetNode.getDescription() instanceof XcodeWorkspaceConfigDescription);
    return (TargetNode<XcodeWorkspaceConfigDescriptionArg>) targetNode;
  }

  private void checkForAndKillXcodeIfRunning(IDEForceKill forceKill)
      throws InterruptedException, IOException {
    if (forceKill == IDEForceKill.NEVER) {
      // We don't even check if Xcode is running because pkill can hang.
      LOG.debug("Prompt to kill Xcode is disabled");
      return;
    }

    if (!processManager.isPresent()) {
      LOG.warn("Could not check if Xcode is running (no process manager)");
      return;
    }

    if (!processManager.get().isProcessRunning(XCODE_PROCESS_NAME)) {
      LOG.debug("Xcode is not running.");
      return;
    }

    switch (forceKill) {
      case PROMPT:
        {
          boolean canPromptResult = canPrompt(environment);
          if (canPromptResult) {
            if (prompt(
                "Xcode is currently running. Buck will modify files Xcode currently has "
                    + "open, which can cause it to become unstable.\n\n"
                    + "Kill Xcode and continue?")) {
              processManager.get().killProcess(XCODE_PROCESS_NAME);
            } else {
              console
                  .getStdOut()
                  .println(
                      console
                          .getAnsi()
                          .asWarningText(
                              "Xcode is running. Generated projects might be lost or corrupted if Xcode "
                                  + "currently has them open."));
            }
            console
                .getStdOut()
                .format(
                    "To disable this prompt in the future, add the following to %s: \n\n"
                        + "[%s]\n"
                        + "  %s = %s\n\n"
                        + "If you would like to always kill Xcode, use '%s'.\n",
                    cell.getFilesystem()
                        .getRootPath()
                        .resolve(Configs.DEFAULT_BUCK_CONFIG_OVERRIDE_FILE_NAME),
                    getIDEForceKillSectionName(),
                    getIDEForceKillFieldName(),
                    IDEForceKill.NEVER.toString().toLowerCase(),
                    IDEForceKill.ALWAYS.toString().toLowerCase());
          } else {
            LOG.debug(
                "Xcode is running, but cannot prompt to kill it (force kill %s, can prompt %s)",
                forceKill.toString(), canPromptResult);
          }
          break;
        }
      case ALWAYS:
        {
          LOG.debug("Will try to force kill Xcode without prompting...");
          processManager.get().killProcess(XCODE_PROCESS_NAME);
          console.getStdOut().println(console.getAnsi().asWarningText("Xcode was force killed."));
          break;
        }
      case NEVER:
        break;
    }
  }

  private boolean canPrompt(ImmutableMap<String, String> environment) {
    String nailgunStdinTty = environment.get("NAILGUN_TTY_0");
    if (nailgunStdinTty != null) {
      return nailgunStdinTty.equals("1");
    } else {
      return System.console() != null;
    }
  }

  private boolean prompt(String prompt) throws IOException {
    Preconditions.checkState(canPrompt(environment));

    LOG.debug("Displaying prompt %s..", prompt);
    console.getStdOut().print(console.getAnsi().asWarningText(prompt + " [Y/n] "));

    // Do not close readers! Otherwise they close System.in in turn
    // Another design may be to provide reader in the context, to use instead of System.in
    BufferedReader bufferedStdinReader =
        new BufferedReader(new InputStreamReader(System.in, Charsets.UTF_8));
    Optional<String> result = Optional.ofNullable(bufferedStdinReader.readLine());

    LOG.debug("Result of prompt: [%s]", result.orElse(""));
    return result.isPresent()
        && (result.get().isEmpty() || result.get().toLowerCase(Locale.US).startsWith("y"));
  }

  @VisibleForTesting
  static ImmutableSet<BuildTarget> getRootsFromPredicate(
      TargetGraph projectGraph, Predicate<TargetNode<?>> rootsPredicate) {
    return projectGraph.getNodes().stream()
        .filter(rootsPredicate)
        .map(TargetNode::getBuildTarget)
        .collect(ImmutableSet.toImmutableSet());
  }

  private TargetGraphCreationResult enhanceTargetGraphIfNeeded(
      Supplier<DepsAwareExecutor<? super ComputeResult, ?>> depsAwareExecutorSupplier,
      TargetGraphCreationResult targetGraphCreationResult,
      boolean isWithTests,
      boolean isWithDependenciesTests)
      throws IOException, InterruptedException, BuildFileParseException, VersionException {

    ImmutableSet<BuildTarget> originalBuildTargets = targetGraphCreationResult.getBuildTargets();

    if (isWithTests) {
      ImmutableSet<BuildTarget> graphRootsOrSourceTargets =
          replaceWorkspacesWithSourceTargetsIfPossible(targetGraphCreationResult);

      ImmutableSet<BuildTarget> explicitTestTargets =
          getExplicitTestTargets(
              graphRootsOrSourceTargets,
              targetGraphCreationResult.getTargetGraph(),
              isWithDependenciesTests,
              focusedTargetMatcher);

      targetGraphCreationResult =
          parser.buildTargetGraph(
              parsingContext,
              MoreSets.union(targetGraphCreationResult.getBuildTargets(), explicitTestTargets));
    }

    if (buckConfig.getView(BuildBuckConfig.class).getBuildVersions()) {
      targetGraphCreationResult =
          versionedTargetGraphCache.toVersionedTargetGraph(
              depsAwareExecutorSupplier.get(),
              buckConfig,
              typeCoercerFactory,
              unconfiguredBuildTargetFactory,
              targetGraphCreationResult,
              targetConfiguration,
              buckEventBus,
              new Cells(cell));
    }
    return targetGraphCreationResult.withBuildTargets(originalBuildTargets);
  }

  @VisibleForTesting
  static ImmutableSet<BuildTarget> replaceWorkspacesWithSourceTargetsIfPossible(
      TargetGraphCreationResult targetGraphCreationResult) {
    Iterable<TargetNode<?>> targetNodes =
        targetGraphCreationResult
            .getTargetGraph()
            .getAll(targetGraphCreationResult.getBuildTargets());
    ImmutableSet.Builder<BuildTarget> resultBuilder = ImmutableSet.builder();
    for (TargetNode<?> node : targetNodes) {
      if (node.getDescription() instanceof XcodeWorkspaceConfigDescription) {
        TargetNode<XcodeWorkspaceConfigDescriptionArg> castedWorkspaceNode =
            castToXcodeWorkspaceTargetNode(node);
        Optional<BuildTarget> srcTarget = castedWorkspaceNode.getConstructorArg().getSrcTarget();
        if (srcTarget.isPresent()) {
          resultBuilder.add(srcTarget.get());
        } else {
          resultBuilder.add(node.getBuildTarget());
        }
      } else {
        resultBuilder.add(node.getBuildTarget());
      }
    }
    return resultBuilder.build();
  }

  private static boolean canGenerateImplicitWorkspaceForDescription(
      BaseDescription<?> description) {
    // We weren't given a workspace target, but we may have been given something that could
    // still turn into a workspace (for example, a library or an actual app rule). If that's the
    // case we still want to generate a workspace.
    return description instanceof AppleBinaryDescription
        || description instanceof AppleBundleDescription
        || description instanceof AppleLibraryDescription;
  }

  /**
   * @param sourceTargetNode - The TargetNode which will act as our fake workspaces `src_target`
   * @return Workspace Args that describe a generic Xcode workspace containing `src_target` and its
   *     tests
   */
  private static XcodeWorkspaceConfigDescriptionArg createImplicitWorkspaceArgs(
      TargetNode<?> sourceTargetNode) {
    return XcodeWorkspaceConfigDescriptionArg.builder()
        .setName("dummy")
        .setSrcTarget(sourceTargetNode.getBuildTarget())
        .build();
  }

  /**
   * @param buildTargets The set of targets for which we would like to find tests
   * @param projectGraph A TargetGraph containing all nodes and their tests.
   * @param shouldIncludeDependenciesTests Should or not include tests that test dependencies
   * @return A set of all test targets that test any of {@code buildTargets} or their dependencies.
   */
  @VisibleForTesting
  static ImmutableSet<BuildTarget> getExplicitTestTargets(
      ImmutableSet<BuildTarget> buildTargets,
      TargetGraph projectGraph,
      boolean shouldIncludeDependenciesTests,
      FocusedTargetMatcher focusedTargetMatcher) {
    Iterable<TargetNode<?>> projectRoots = projectGraph.getAll(buildTargets);
    Iterable<TargetNode<?>> nodes;
    if (shouldIncludeDependenciesTests) {
      nodes = projectGraph.getSubgraph(projectRoots).getNodes();
    } else {
      nodes = projectRoots;
    }

    return TargetNodes.getTestTargetsForNodes(
        RichStream.from(nodes)
            .filter(node -> focusedTargetMatcher.matches(node.getBuildTarget()))
            .iterator());
  }

  /**
   * An action graph where subtrees are populated as needed.
   *
   * <p>This is useful when only select sub-graphs of the action graph needs to be generated, but
   * the subgraph is not known at this point in time. The synchronization and bottom-up traversal is
   * necessary as this will be accessed from multiple threads during project generation, and
   * BuildRuleResolver is not 100% thread safe when it comes to mutations.
   */
  @ThreadSafe
  private static class LazyActionGraph {
    private final TargetGraph targetGraph;
    private final ActionGraphBuilder graphBuilder;
    private final Set<BuildTarget> traversedTargets;

    public LazyActionGraph(TargetGraph targetGraph, CellProvider cellProvider) {
      this.targetGraph = targetGraph;
      this.graphBuilder =
          new MultiThreadedActionGraphBuilder(
              MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
              targetGraph,
              ConfigurationRuleRegistryFactory.createRegistry(targetGraph),
              new DefaultTargetNodeToBuildRuleTransformer(),
              cellProvider);
      this.traversedTargets = new HashSet<>();
    }

    public ActionGraphBuilder getActionGraphBuilderWhileRequiringSubgraph(TargetNode<?> root) {
      synchronized (this) {
        try {
          List<BuildTarget> currentTargets = new ArrayList<>();
          for (TargetNode<?> targetNode :
              new AcyclicDepthFirstPostOrderTraversal<TargetNode<?>>(
                      node ->
                          traversedTargets.contains(node.getBuildTarget())
                              ? Collections.emptyIterator()
                              : targetGraph.getOutgoingNodesFor(node).iterator())
                  .traverse(ImmutableList.of(root))) {
            if (!traversedTargets.contains(targetNode.getBuildTarget())
                && targetNode.getRuleType().isBuildRule()) {
              graphBuilder.requireRule(targetNode.getBuildTarget());
              currentTargets.add(targetNode.getBuildTarget());
            }
          }
          traversedTargets.addAll(currentTargets);
        } catch (NoSuchBuildTargetException e) {
          throw new HumanReadableException(e);
        } catch (CycleException e) {
          throw new RuntimeException(e);
        }
        return graphBuilder;
      }
    }
  }
}
