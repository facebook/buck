/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.apple.project_generator;

import com.facebook.buck.apple.AppleBinaryDescription;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.XcodeWorkspaceConfigDescription;
import com.facebook.buck.apple.XcodeWorkspaceConfigDescriptionArg;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.ProjectTestsMode;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.halide.HalideBuckConfig;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.parser.BuildFileSpec;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.parser.TargetNodePredicateSpec;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.ProcessManager;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.concurrent.ThreadSafe;

public class XCodeProjectCommandHelper {

  private static final Logger LOG = Logger.get(XCodeProjectCommandHelper.class);

  private static final String XCODE_PROCESS_NAME = "Xcode";

  private final BuckEventBus buckEventBus;
  private final Parser parser;
  private final BuckConfig buckConfig;
  private final Cell cell;
  private final Console console;
  private final Optional<ProcessManager> processManager;
  private final ImmutableMap<String, String> environment;
  private final ListeningExecutorService executorService;
  private final List<String> arguments;
  private final boolean enableParserProfiling;
  private final boolean withTests;
  private final boolean withoutTests;
  private final boolean withoutDependenciesTests;
  private final String modulesToFocusOn;
  private final boolean combinedProject;
  private final boolean dryRun;
  private final boolean readOnly;
  private final Function<Iterable<String>, ImmutableList<TargetNodeSpec>> argsParser;
  private final Function<ImmutableList<String>, Integer> buildRunner;

  public XCodeProjectCommandHelper(
      BuckEventBus buckEventBus,
      Parser parser,
      BuckConfig buckConfig,
      Cell cell,
      Console console,
      Optional<ProcessManager> processManager,
      ImmutableMap<String, String> environment,
      ListeningExecutorService executorService,
      List<String> arguments,
      boolean enableParserProfiling,
      boolean withTests,
      boolean withoutTests,
      boolean withoutDependenciesTests,
      String modulesToFocusOn,
      boolean combinedProject,
      boolean dryRun,
      boolean readOnly,
      Function<Iterable<String>, ImmutableList<TargetNodeSpec>> argsParser,
      Function<ImmutableList<String>, Integer> buildRunner) {
    this.buckEventBus = buckEventBus;
    this.parser = parser;
    this.buckConfig = buckConfig;
    this.cell = cell;
    this.console = console;
    this.processManager = processManager;
    this.environment = environment;
    this.executorService = executorService;
    this.arguments = arguments;
    this.enableParserProfiling = enableParserProfiling;
    this.withTests = withTests;
    this.withoutTests = withoutTests;
    this.withoutDependenciesTests = withoutDependenciesTests;
    this.modulesToFocusOn = modulesToFocusOn;
    this.combinedProject = combinedProject;
    this.dryRun = dryRun;
    this.readOnly = readOnly;
    this.argsParser = argsParser;
    this.buildRunner = buildRunner;
  }

  public int parseTargetsAndRunXCodeGenerator(ListeningExecutorService executor)
      throws IOException, InterruptedException {
    ImmutableSet<BuildTarget> passedInTargetsSet;
    TargetGraph projectGraph;

    try {
      ParserConfig parserConfig = buckConfig.getView(ParserConfig.class);
      passedInTargetsSet =
          ImmutableSet.copyOf(
              Iterables.concat(
                  parser.resolveTargetSpecs(
                      buckEventBus,
                      cell,
                      enableParserProfiling,
                      executor,
                      argsParser.apply(arguments),
                      SpeculativeParsing.of(true),
                      parserConfig.getDefaultFlavorsMode())));
      projectGraph = getProjectGraphForIde(executor, passedInTargetsSet);
    } catch (BuildTargetException | BuildFileParseException | HumanReadableException e) {
      buckEventBus.post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return 1;
    }

    checkForAndKillXcodeIfRunning(getIdePrompt(buckConfig));

    ImmutableSet<BuildTarget> graphRoots;
    if (passedInTargetsSet.isEmpty()) {
      graphRoots =
          getRootsFromPredicate(
              projectGraph,
              node -> node.getDescription() instanceof XcodeWorkspaceConfigDescription);
    } else {
      graphRoots = passedInTargetsSet;
    }

    TargetGraphAndTargets targetGraphAndTargets;
    try {
      targetGraphAndTargets =
          createTargetGraph(
              projectGraph,
              graphRoots,
              isWithTests(buckConfig),
              isWithDependenciesTests(buckConfig),
              passedInTargetsSet.isEmpty(),
              executor);
    } catch (BuildFileParseException
        | TargetGraph.NoSuchNodeException
        | BuildTargetException
        | HumanReadableException e) {
      buckEventBus.post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return 1;
    }

    if (dryRun) {
      for (TargetNode<?, ?> targetNode : targetGraphAndTargets.getTargetGraph().getNodes()) {
        console.getStdOut().println(targetNode.toString());
      }

      return 0;
    }

    return runXcodeProjectGenerator(executor, targetGraphAndTargets, passedInTargetsSet);
  }

  /**
   * Returns true if Buck should prompt to kill a running IDE before changing its files, false
   * otherwise.
   */
  private boolean getIdePrompt(BuckConfig buckConfig) {
    return buckConfig.getBooleanValue("project", "ide_prompt", true);
  }

  private ProjectTestsMode testsMode(BuckConfig buckConfig) {
    ProjectTestsMode parameterMode = buckConfig.xcodeProjectTestsMode();

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
  private int runXcodeProjectGenerator(
      ListeningExecutorService executor,
      final TargetGraphAndTargets targetGraphAndTargets,
      ImmutableSet<BuildTarget> passedInTargetsSet)
      throws IOException, InterruptedException {
    int exitCode = 0;
    AppleConfig appleConfig = buckConfig.getView(AppleConfig.class);
    ImmutableSet<ProjectGenerator.Option> options =
        buildWorkspaceGeneratorOptions(
            readOnly,
            isWithTests(buckConfig),
            isWithDependenciesTests(buckConfig),
            combinedProject,
            appleConfig.shouldUseHeaderMapsInXcodeProject(),
            appleConfig.shouldMergeHeaderMapsInXcodeProject(),
            appleConfig.shouldGenerateHeaderSymlinkTreesOnly());

    ImmutableSet<BuildTarget> requiredBuildTargets =
        generateWorkspacesForTargets(
            buckEventBus,
            cell,
            buckConfig,
            executorService,
            targetGraphAndTargets,
            passedInTargetsSet,
            options,
            getFocusModules(executor),
            new HashMap<>(),
            combinedProject);
    if (!requiredBuildTargets.isEmpty()) {
      ImmutableMultimap<Path, String> cellPathToCellName =
          cell.getCellPathResolver().getCellPaths().asMultimap().inverse();
      ImmutableList<String> arguments =
          RichStream.from(requiredBuildTargets)
              .map(
                  target -> {
                    if (!target.getCellPath().equals(cell.getRoot())) {
                      Optional<String> cellName =
                          cellPathToCellName.get(target.getCellPath()).stream().findAny();
                      if (cellName.isPresent()) {
                        return target.withUnflavoredBuildTarget(
                            UnflavoredBuildTarget.of(
                                target.getCellPath(),
                                cellName,
                                target.getBaseName(),
                                target.getShortName()));
                      } else {
                        throw new IllegalStateException(
                            "Failed to find cell name for cell path while constructing parameters to "
                                + "build dependencies for project generation. "
                                + "Build target: "
                                + target
                                + " cell path: "
                                + target.getCellPath());
                      }
                    } else {
                      return target;
                    }
                  })
              .map(Object::toString)
              .toImmutableList();
      exitCode = buildRunner.apply(arguments);
    }
    return exitCode;
  }

  @VisibleForTesting
  static ImmutableSet<BuildTarget> generateWorkspacesForTargets(
      BuckEventBus buckEventBus,
      Cell cell,
      BuckConfig buckConfig,
      ListeningExecutorService executorService,
      final TargetGraphAndTargets targetGraphAndTargets,
      ImmutableSet<BuildTarget> passedInTargetsSet,
      ImmutableSet<ProjectGenerator.Option> options,
      FocusedModuleTargetMatcher focusModules,
      Map<Path, ProjectGenerator> projectGenerators,
      boolean combinedProject)
      throws IOException, InterruptedException {
    ImmutableSet<BuildTarget> targets;
    if (passedInTargetsSet.isEmpty()) {
      targets =
          targetGraphAndTargets
              .getProjectRoots()
              .stream()
              .map(TargetNode::getBuildTarget)
              .collect(MoreCollectors.toImmutableSet());
    } else {
      targets = passedInTargetsSet;
    }

    LazyActionGraph lazyActionGraph =
        new LazyActionGraph(targetGraphAndTargets.getTargetGraph(), buckEventBus);

    LOG.debug("Generating workspace for config targets %s", targets);
    ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder = ImmutableSet.builder();
    for (final BuildTarget inputTarget : targets) {
      TargetNode<?, ?> inputNode = targetGraphAndTargets.getTargetGraph().get(inputTarget);
      XcodeWorkspaceConfigDescriptionArg workspaceArgs;
      if (inputNode.getDescription() instanceof XcodeWorkspaceConfigDescription) {
        TargetNode<XcodeWorkspaceConfigDescriptionArg, ?> castedWorkspaceNode =
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

      CxxPlatform defaultCxxPlatform = cell.getKnownBuildRuleTypes().getDefaultCxxPlatforms();
      WorkspaceAndProjectGenerator generator =
          new WorkspaceAndProjectGenerator(
              cell,
              targetGraphAndTargets.getTargetGraph(),
              workspaceArgs,
              inputTarget,
              options,
              combinedProject,
              focusModules,
              !appleConfig.getXcodeDisableParallelizeBuild(),
              defaultCxxPlatform,
              buckConfig.getView(ParserConfig.class).getBuildFileName(),
              lazyActionGraph::getBuildRuleResolverWhileRequiringSubgraph,
              buckEventBus,
              halideBuckConfig,
              cxxBuckConfig,
              swiftBuckConfig);
      Preconditions.checkNotNull(
          executorService, "CommandRunnerParams does not have executor for PROJECT pool");
      generator.generateWorkspaceAndDependentProjects(projectGenerators, executorService);
      ImmutableSet<BuildTarget> requiredBuildTargetsForWorkspace =
          generator.getRequiredBuildTargets();
      LOG.debug(
          "Required build targets for workspace %s: %s",
          inputTarget, requiredBuildTargetsForWorkspace);
      requiredBuildTargetsBuilder.addAll(requiredBuildTargetsForWorkspace);
    }

    return requiredBuildTargetsBuilder.build();
  }

  private FocusedModuleTargetMatcher getFocusModules(ListeningExecutorService executor)
      throws IOException, InterruptedException {
    if (modulesToFocusOn == null) {
      return FocusedModuleTargetMatcher.noFocus();
    }

    Iterable<String> patterns = Splitter.onPattern("\\s+").split(modulesToFocusOn);
    // Parse patterns with the following syntax:
    // https://buckbuild.com/concept/build_target_pattern.html
    ImmutableList<TargetNodeSpec> specs = argsParser.apply(patterns);

    // Resolve the list of targets matching the patterns.
    ImmutableSet<BuildTarget> passedInTargetsSet;
    ParserConfig parserConfig = buckConfig.getView(ParserConfig.class);
    try {
      passedInTargetsSet =
          parser
              .resolveTargetSpecs(
                  buckEventBus,
                  cell,
                  enableParserProfiling,
                  executor,
                  specs,
                  SpeculativeParsing.of(false),
                  parserConfig.getDefaultFlavorsMode())
              .stream()
              .flatMap(Collection::stream)
              .map(target -> target.withoutCell())
              .collect(MoreCollectors.toImmutableSet());
    } catch (BuildTargetException | BuildFileParseException | HumanReadableException e) {
      buckEventBus.post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return FocusedModuleTargetMatcher.noFocus();
    }
    LOG.debug("Selected targets: %s", passedInTargetsSet.toString());

    ImmutableSet<UnflavoredBuildTarget> passedInUnflavoredTargetsSet =
        RichStream.from(passedInTargetsSet)
            .map(BuildTarget::getUnflavoredBuildTarget)
            .toImmutableSet();
    LOG.debug("Selected unflavored targets: %s", passedInUnflavoredTargetsSet.toString());
    return FocusedModuleTargetMatcher.focusedOn(passedInUnflavoredTargetsSet);
  }

  @VisibleForTesting
  static ImmutableSet<ProjectGenerator.Option> buildWorkspaceGeneratorOptions(
      boolean isReadonly,
      boolean isWithTests,
      boolean isWithDependenciesTests,
      boolean isProjectsCombined,
      boolean shouldUseHeaderMaps,
      boolean shouldMergeHeaderMaps,
      boolean shouldGenerateHeaderSymlinkTreesOnly) {
    ImmutableSet.Builder<ProjectGenerator.Option> optionsBuilder = ImmutableSet.builder();
    if (isReadonly) {
      optionsBuilder.add(ProjectGenerator.Option.GENERATE_READ_ONLY_FILES);
    }
    if (isWithTests) {
      optionsBuilder.add(ProjectGenerator.Option.INCLUDE_TESTS);
    }
    if (isWithDependenciesTests) {
      optionsBuilder.add(ProjectGenerator.Option.INCLUDE_DEPENDENCIES_TESTS);
    }
    if (isProjectsCombined) {
      optionsBuilder.addAll(ProjectGenerator.COMBINED_PROJECT_OPTIONS);
    } else {
      optionsBuilder.addAll(ProjectGenerator.SEPARATED_PROJECT_OPTIONS);
    }
    if (!shouldUseHeaderMaps) {
      optionsBuilder.add(ProjectGenerator.Option.DISABLE_HEADER_MAPS);
    }
    if (shouldMergeHeaderMaps) {
      optionsBuilder.add(ProjectGenerator.Option.MERGE_HEADER_MAPS);
    }
    if (shouldGenerateHeaderSymlinkTreesOnly) {
      optionsBuilder.add(ProjectGenerator.Option.GENERATE_HEADERS_SYMLINK_TREES_ONLY);
    }
    return optionsBuilder.build();
  }

  @SuppressWarnings(value = "unchecked")
  private static TargetNode<XcodeWorkspaceConfigDescriptionArg, ?> castToXcodeWorkspaceTargetNode(
      TargetNode<?, ?> targetNode) {
    Preconditions.checkArgument(
        targetNode.getDescription() instanceof XcodeWorkspaceConfigDescription);
    return (TargetNode<XcodeWorkspaceConfigDescriptionArg, ?>) targetNode;
  }

  private void checkForAndKillXcodeIfRunning(boolean enablePrompt)
      throws InterruptedException, IOException {
    if (!processManager.isPresent()) {
      LOG.warn("Could not check if Xcode is running (no process manager)");
      return;
    }

    if (!processManager.get().isProcessRunning(XCODE_PROCESS_NAME)) {
      LOG.debug("Xcode is not running.");
      return;
    }

    boolean canPromptResult = canPrompt(environment);
    if (enablePrompt && canPromptResult) {
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
                  + "[project]\n"
                  + "  ide_prompt = false\n\n",
              cell.getFilesystem()
                  .getRootPath()
                  .resolve(BuckConfig.BUCK_CONFIG_OVERRIDE_FILE_NAME)
                  .toAbsolutePath());
    } else {
      LOG.debug(
          "Xcode is running, but cannot prompt to kill it (enabled %s, can prompt %s)",
          enablePrompt, canPromptResult);
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

    Optional<String> result;
    try (InputStreamReader stdinReader = new InputStreamReader(System.in, Charsets.UTF_8);
        BufferedReader bufferedStdinReader = new BufferedReader(stdinReader)) {
      result = Optional.ofNullable(bufferedStdinReader.readLine());
    }
    LOG.debug("Result of prompt: [%s]", result);
    return result.isPresent()
        && (result.get().isEmpty() || result.get().toLowerCase(Locale.US).startsWith("y"));
  }

  @VisibleForTesting
  static ImmutableSet<BuildTarget> getRootsFromPredicate(
      TargetGraph projectGraph, Predicate<TargetNode<?, ?>> rootsPredicate) {
    return projectGraph
        .getNodes()
        .stream()
        .filter(rootsPredicate)
        .map(TargetNode::getBuildTarget)
        .collect(MoreCollectors.toImmutableSet());
  }

  private TargetGraph getProjectGraphForIde(
      ListeningExecutorService executor, ImmutableSet<BuildTarget> passedInTargets)
      throws InterruptedException, BuildFileParseException, BuildTargetException, IOException {

    if (passedInTargets.isEmpty()) {
      return parser
          .buildTargetGraphForTargetNodeSpecs(
              buckEventBus,
              cell,
              enableParserProfiling,
              executor,
              ImmutableList.of(
                  TargetNodePredicateSpec.of(
                      x -> true, BuildFileSpec.fromRecursivePath(Paths.get(""), cell.getRoot()))))
          .getTargetGraph();
    }
    Preconditions.checkState(!passedInTargets.isEmpty());
    return parser.buildTargetGraph(
        buckEventBus, cell, enableParserProfiling, executor, passedInTargets);
  }

  private TargetGraphAndTargets createTargetGraph(
      TargetGraph projectGraph,
      ImmutableSet<BuildTarget> graphRoots,
      boolean isWithTests,
      boolean isWithDependenciesTests,
      boolean needsFullRecursiveParse,
      ListeningExecutorService executor)
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException {

    ImmutableSet<BuildTarget> explicitTestTargets = ImmutableSet.of();
    ImmutableSet<BuildTarget> graphRootsOrSourceTargets =
        replaceWorkspacesWithSourceTargetsIfPossible(graphRoots, projectGraph);

    if (isWithTests) {
      FocusedModuleTargetMatcher focusedModules = getFocusModules(executor);

      explicitTestTargets =
          getExplicitTestTargets(
              graphRootsOrSourceTargets, projectGraph, isWithDependenciesTests, focusedModules);
      if (!needsFullRecursiveParse) {
        projectGraph =
            parser.buildTargetGraph(
                buckEventBus,
                cell,
                enableParserProfiling,
                executor,
                Sets.union(graphRoots, explicitTestTargets));
      } else {
        projectGraph =
            parser.buildTargetGraph(
                buckEventBus,
                cell,
                enableParserProfiling,
                executor,
                Sets.union(
                    projectGraph
                        .getNodes()
                        .stream()
                        .map(TargetNode::getBuildTarget)
                        .collect(MoreCollectors.toImmutableSet()),
                    explicitTestTargets));
      }
    }

    return TargetGraphAndTargets.create(graphRoots, projectGraph, isWithTests, explicitTestTargets);
  }

  @VisibleForTesting
  static ImmutableSet<BuildTarget> replaceWorkspacesWithSourceTargetsIfPossible(
      ImmutableSet<BuildTarget> buildTargets, TargetGraph projectGraph) {
    Iterable<TargetNode<?, ?>> targetNodes = projectGraph.getAll(buildTargets);
    ImmutableSet.Builder<BuildTarget> resultBuilder = ImmutableSet.builder();
    for (TargetNode<?, ?> node : targetNodes) {
      if (node.getDescription() instanceof XcodeWorkspaceConfigDescription) {
        TargetNode<XcodeWorkspaceConfigDescriptionArg, ?> castedWorkspaceNode =
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

  private static boolean canGenerateImplicitWorkspaceForDescription(Description<?> description) {
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
      TargetNode<?, ?> sourceTargetNode) {
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
      FocusedModuleTargetMatcher focusedModules) {
    Iterable<TargetNode<?, ?>> projectRoots = projectGraph.getAll(buildTargets);
    Iterable<TargetNode<?, ?>> nodes;
    if (shouldIncludeDependenciesTests) {
      nodes = projectGraph.getSubgraph(projectRoots).getNodes();
    } else {
      nodes = projectRoots;
    }

    return TargetGraphAndTargets.getExplicitTestTargets(
        RichStream.from(nodes)
            .filter(node -> focusedModules.isFocusedOn(node.getBuildTarget()))
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
    private final BuildRuleResolver resolver;

    public LazyActionGraph(TargetGraph targetGraph, BuckEventBus buckEventBus) {
      this.targetGraph = targetGraph;
      this.resolver =
          new BuildRuleResolver(
              targetGraph, new DefaultTargetNodeToBuildRuleTransformer(), buckEventBus);
    }

    public BuildRuleResolver getBuildRuleResolverWhileRequiringSubgraph(TargetNode<?, ?> root) {
      TargetGraph subgraph = targetGraph.getSubgraph(ImmutableList.of(root));

      try {
        synchronized (this) {
          new AbstractBottomUpTraversal<TargetNode<?, ?>, NoSuchBuildTargetException>(subgraph) {
            @Override
            public void visit(TargetNode<?, ?> node) throws NoSuchBuildTargetException {
              resolver.requireRule(node.getBuildTarget());
            }
          }.traverse();
        }
      } catch (NoSuchBuildTargetException e) {
        throw new HumanReadableException(e);
      }
      return resolver;
    }
  }
}
