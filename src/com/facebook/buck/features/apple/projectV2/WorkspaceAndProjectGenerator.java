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

import com.facebook.buck.apple.AppleBuildRules;
import com.facebook.buck.apple.AppleBuildRules.RecursiveDependenciesMode;
import com.facebook.buck.apple.AppleBundleDescriptionArg;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleDependenciesCache;
import com.facebook.buck.apple.AppleNativeTargetDescriptionArg;
import com.facebook.buck.apple.AppleTestDescriptionArg;
import com.facebook.buck.apple.XCodeDescriptions;
import com.facebook.buck.apple.xcode.PBXObjectGIDFactory;
import com.facebook.buck.apple.xcode.XCScheme;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.apple.xcode.xcodeproj.ProductType;
import com.facebook.buck.apple.xcode.xcodeproj.ProductTypes;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.util.graph.TopologicalSort;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.HeaderMode;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.features.apple.common.CopyInXcode;
import com.facebook.buck.features.apple.common.SchemeActionType;
import com.facebook.buck.features.apple.common.WorkspaceMetadataWriter;
import com.facebook.buck.features.apple.common.XcodeWorkspaceConfigDescription;
import com.facebook.buck.features.apple.common.XcodeWorkspaceConfigDescriptionArg;
import com.facebook.buck.features.halide.HalideBuckConfig;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class WorkspaceAndProjectGenerator {
  private static final Logger LOG = Logger.get(WorkspaceAndProjectGenerator.class);

  private final XCodeDescriptions xcodeDescriptions;
  private final Cell rootCell;
  private final TargetGraph projectGraph;
  private final AppleDependenciesCache dependenciesCache;
  private final ProjectGenerationStateCache projGenerationStateCache;
  private final XcodeWorkspaceConfigDescriptionArg workspaceArguments;
  private final BuildTarget workspaceBuildTarget;
  private final FocusedTargetMatcher focusedTargetMatcher; // @audited (chatatap)
  private final ProjectGeneratorOptions projectGeneratorOptions;
  private final boolean parallelizeBuild;
  private final CxxPlatform defaultCxxPlatform;
  private final ImmutableSet<Flavor> appleCxxFlavors;

  private final Map<String, SchemeGenerator> schemeGenerators = new HashMap<>();
  private final String buildFileName;
  private final Function<TargetNode<?>, ActionGraphBuilder> graphBuilderForNode;
  private final BuckEventBus buckEventBus;
  private final RuleKeyConfiguration ruleKeyConfiguration;

  private final ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder =
      ImmutableSet.builder();
  private final ImmutableSortedSet.Builder<Path> xcconfigPathsBuilder =
      ImmutableSortedSet.naturalOrder();
  private final ImmutableList.Builder<CopyInXcode> filesToCopyInXcodeBuilder =
      ImmutableList.builder();
  private final HalideBuckConfig halideBuckConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final AppleConfig appleConfig;
  private final SwiftBuckConfig swiftBuckConfig;
  private final Optional<ImmutableMap<BuildTarget, TargetNode<?>>> sharedLibraryToBundle;

  /** The result of generating a workspace project. */
  public static class Result {
    public final Path workspacePath;
    public final PBXProject project;
    public final ImmutableMap<BuildTarget, PBXTarget> buildTargetToPBXTarget;

    public Result(
        Path workspacePath,
        PBXProject project,
        ImmutableMap<BuildTarget, PBXTarget> buildTargetToPBXTarget) {
      this.workspacePath = workspacePath;
      this.project = project;
      this.buildTargetToPBXTarget = buildTargetToPBXTarget;
    }
  }

  public WorkspaceAndProjectGenerator(
      XCodeDescriptions xcodeDescriptions,
      Cell cell,
      TargetGraph projectGraph,
      XcodeWorkspaceConfigDescriptionArg workspaceArguments,
      BuildTarget workspaceBuildTarget,
      ProjectGeneratorOptions projectGeneratorOptions,
      FocusedTargetMatcher focusedTargetMatcher,
      boolean parallelizeBuild,
      CxxPlatform defaultCxxPlatform,
      ImmutableSet<Flavor> appleCxxFlavors,
      String buildFileName,
      Function<TargetNode<?>, ActionGraphBuilder> graphBuilderForNode,
      BuckEventBus buckEventBus,
      RuleKeyConfiguration ruleKeyConfiguration,
      HalideBuckConfig halideBuckConfig,
      CxxBuckConfig cxxBuckConfig,
      AppleConfig appleConfig,
      SwiftBuckConfig swiftBuckConfig,
      Optional<ImmutableMap<BuildTarget, TargetNode<?>>> sharedLibraryToBundle) {
    this.xcodeDescriptions = xcodeDescriptions;
    this.rootCell = cell;
    this.projectGraph = projectGraph;
    this.dependenciesCache = new AppleDependenciesCache(projectGraph);
    this.ruleKeyConfiguration = ruleKeyConfiguration;
    this.projGenerationStateCache = new ProjectGenerationStateCache();
    this.workspaceArguments = workspaceArguments;
    this.workspaceBuildTarget = workspaceBuildTarget;
    this.projectGeneratorOptions = projectGeneratorOptions;
    this.parallelizeBuild = parallelizeBuild;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.appleCxxFlavors = appleCxxFlavors;
    this.buildFileName = buildFileName;
    this.graphBuilderForNode = graphBuilderForNode;
    this.buckEventBus = buckEventBus;
    this.swiftBuckConfig = swiftBuckConfig;
    this.halideBuckConfig = halideBuckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.appleConfig = appleConfig;
    this.sharedLibraryToBundle = sharedLibraryToBundle;

    this.focusedTargetMatcher = focusedTargetMatcher;
    // Add the workspace target to the focused target matcher.
    workspaceArguments
        .getSrcTarget()
        .ifPresent(buildTarget -> this.focusedTargetMatcher.addTarget(buildTarget));
  }

  @VisibleForTesting
  Map<String, SchemeGenerator> getSchemeGenerators() {
    return schemeGenerators;
  }

  public ImmutableSet<BuildTarget> getRequiredBuildTargets() {
    return requiredBuildTargetsBuilder.build();
  }

  private ImmutableSet<Path> getXcconfigPaths() {
    return xcconfigPathsBuilder.build();
  }

  private ImmutableList<CopyInXcode> getFilesToCopyInXcode() {
    return filesToCopyInXcodeBuilder.build();
  }

  /**
   * Generates a workspace and all projects
   *
   * @return A result indicating the output of the generation
   * @throws IOException
   */
  public Result generateWorkspaceAndDependentProjects(
      ListeningExecutorService listeningExecutorService) throws IOException, InterruptedException {
    LOG.debug("Generating workspace for target %s", workspaceBuildTarget);

    String workspaceName =
        XcodeWorkspaceConfigDescription.getWorkspaceNameFromArg(workspaceArguments);
    Path outputDirectory =
        workspaceBuildTarget
            .getCellRelativeBasePath()
            .getPath()
            .toPath(rootCell.getFilesystem().getFileSystem());

    WorkspaceGenerator workspaceGenerator =
        new WorkspaceGenerator(rootCell.getFilesystem(), workspaceName, outputDirectory);

    ImmutableMap.Builder<String, XcodeWorkspaceConfigDescriptionArg> schemeConfigsBuilder =
        ImmutableMap.builder();
    ImmutableSetMultimap.Builder<String, Optional<TargetNode<?>>> schemeNameToSrcTargetNodeBuilder =
        ImmutableSetMultimap.builder();
    ImmutableSetMultimap.Builder<String, TargetNode<?>> buildForTestNodesBuilder =
        ImmutableSetMultimap.builder();
    ImmutableSetMultimap.Builder<String, TargetNode<AppleTestDescriptionArg>> testsBuilder =
        ImmutableSetMultimap.builder();

    buildWorkspaceSchemes(
        projectGraph,
        projectGeneratorOptions.shouldIncludeTests(),
        projectGeneratorOptions.shouldIncludeDependenciesTests(),
        workspaceName,
        workspaceArguments,
        schemeConfigsBuilder,
        schemeNameToSrcTargetNodeBuilder,
        buildForTestNodesBuilder,
        testsBuilder);

    ImmutableMap<String, XcodeWorkspaceConfigDescriptionArg> schemeConfigs =
        schemeConfigsBuilder.build();
    ImmutableSetMultimap<String, Optional<TargetNode<?>>> schemeNameToSrcTargetNode =
        schemeNameToSrcTargetNodeBuilder.build();
    ImmutableSetMultimap<String, TargetNode<?>> buildForTestNodes =
        buildForTestNodesBuilder.build();
    ImmutableSetMultimap<String, TargetNode<AppleTestDescriptionArg>> tests = testsBuilder.build();

    ImmutableSet<BuildTarget> targetsInRequiredProjects =
        Stream.concat(
                schemeNameToSrcTargetNode.values().stream().flatMap(RichStream::from),
                buildForTestNodes.values().stream())
            .map(TargetNode::getBuildTarget)
            .collect(ImmutableSet.toImmutableSet());
    ImmutableMap.Builder<BuildTarget, PBXTarget> buildTargetToPbxTargetMapBuilder =
        ImmutableMap.builder();
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXObjectGIDFactory objectFactory = new PBXObjectGIDFactory();
    XcodeProjectWriteOptions xcodeProjectWriteOptions =
        XcodeProjectWriteOptions.of(
            objectFactory.createProject(workspaceName), objectFactory, outputDirectory);

    generateProject(
        listeningExecutorService,
        xcodeProjectWriteOptions,
        workspaceGenerator,
        targetsInRequiredProjects,
        buildTargetToPbxTargetMapBuilder,
        targetToProjectPathMapBuilder);

    writeWorkspaceMetaData(outputDirectory, workspaceName);

    ImmutableMap<BuildTarget, PBXTarget> buildTargetToPBXTarget =
        buildTargetToPbxTargetMapBuilder.build();
    ImmutableMap<PBXTarget, Path> targetToProjectPathMap = targetToProjectPathMapBuilder.build();

    ImmutableSetMultimap<String, PBXTarget> schemeBuildForTestNodeTargets =
        WorkspaceAndProjectGenerator.mapFromSchemeToPBXProject(
            buildForTestNodes, buildTargetToPBXTarget);
    ImmutableSetMultimap<String, PBXTarget> schemeUngroupedTestTargets =
        WorkspaceAndProjectGenerator.mapFromSchemeToPBXProject(tests, buildTargetToPBXTarget);

    LOG.debug("Generating schemes for all sub-projects.");

    writeWorkspaceSchemes(
        workspaceName,
        outputDirectory,
        schemeConfigs,
        schemeNameToSrcTargetNode,
        schemeBuildForTestNodeTargets,
        schemeUngroupedTestTargets,
        targetToProjectPathMap,
        buildTargetToPBXTarget);

    requiredBuildTargetsBuilder.addAll(getModularNodesToGenerate());

    Path workspacePath = workspaceGenerator.writeWorkspace();
    return new Result(workspacePath, xcodeProjectWriteOptions.project(), buildTargetToPBXTarget);
  }

  /**
   * Transform a map from scheme name to `TargetNode` to scheme name to the associated `PBXProject`.
   *
   * @param schemeToTargetNodes Map to transform.
   * @return Map of scheme name to associated `PXBProject`s.
   */
  private static ImmutableSetMultimap<String, PBXTarget> mapFromSchemeToPBXProject(
      ImmutableSetMultimap<String, ? extends TargetNode<?>> schemeToTargetNodes,
      ImmutableMap<BuildTarget, PBXTarget> buildTargetToPBXTarget) {
    ImmutableSetMultimap<String, PBXTarget> schemeToPBXProject =
        ImmutableSetMultimap.copyOf(
            schemeToTargetNodes.entries().stream()
                .map(
                    stringTargetNodeEntry ->
                        Maps.immutableEntry(
                            stringTargetNodeEntry.getKey(),
                            buildTargetToPBXTarget.get(
                                stringTargetNodeEntry.getValue().getBuildTarget())))
                .filter(
                    stringPBXTargetEntry -> {
                      return stringPBXTargetEntry.getValue() != null;
                    })
                .collect(Collectors.toList()));
    return schemeToPBXProject;
  }

  private void writeWorkspaceMetaData(Path outputDirectory, String workspaceName)
      throws IOException {

    WorkspaceMetadataWriter workspaceMetadataWriter =
        new WorkspaceMetadataWriter(
            "2",
            getRequiredBuildTargets(),
            getXcconfigPaths(),
            getFilesToCopyInXcode(),
            rootCell.getFilesystem());
    workspaceMetadataWriter.writeToWorkspaceAtPath(
        outputDirectory.resolve(workspaceName + ".xcworkspace"));
  }

  private void generateProject(
      ListeningExecutorService listeningExecutorService,
      XcodeProjectWriteOptions xcodeProjectWriteOptions,
      WorkspaceGenerator workspaceGenerator,
      ImmutableSet<BuildTarget> targetsInRequiredProjects,
      ImmutableMap.Builder<BuildTarget, PBXTarget> buildTargetToPbxTargetMapBuilder,
      ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder)
      throws IOException, InterruptedException {
    ImmutableSet.Builder<BuildTarget> buildTargets = ImmutableSet.builder();

    HashSet<BuildTarget> unflavoredTargetsToGenerate = new HashSet<>();

    for (TargetNode<?> targetNode : projectGraph.getNodes()) {
      BuildTarget buildTarget = targetNode.getBuildTarget();

      BuildTarget targetWithoutAppleCxxFlavors =
          targetNode.getBuildTarget().withoutFlavors(appleCxxFlavors);
      BuildTarget targetWithoutSpecificFlavors =
          targetWithoutAppleCxxFlavors.withoutFlavors(CxxDescriptionEnhancer.STATIC_FLAVOR);

      // Filter out targets that are not part of our focus set as well as
      // ignore certain flavors when considering a target previously generated:
      //  - AppleCxx  : Differing platforms should be generated as one target.
      //  - static    : Static is the default. This avoids duplication when `static` is passed
      // directly.
      // This allows us to dedupe targets with different flavors, if any.
      if (focusedTargetMatcher.matches(targetNode.getBuildTarget())
          && !unflavoredTargetsToGenerate.contains(targetWithoutSpecificFlavors)) {
        buildTargets.add(buildTarget);
        unflavoredTargetsToGenerate.add(targetWithoutSpecificFlavors);
      }
    }

    GenerationResult generationResult =
        generateProjectForDirectory(
            listeningExecutorService,
            xcodeProjectWriteOptions,
            rootCell,
            buildTargets.build(),
            targetsInRequiredProjects);

    processGenerationResult(
        buildTargetToPbxTargetMapBuilder, targetToProjectPathMapBuilder, generationResult);
    workspaceGenerator.addFilePath(xcodeProjectWriteOptions.xcodeProjPath(), Optional.empty());
  }

  private void processGenerationResult(
      ImmutableMap.Builder<BuildTarget, PBXTarget> buildTargetToPbxTargetMapBuilder,
      ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder,
      GenerationResult result) {
    requiredBuildTargetsBuilder.addAll(result.getRequiredBuildTargets());
    ImmutableSortedSet<Path> relativeXcconfigPaths =
        result.getXcconfigPaths().stream()
            .map((Path p) -> rootCell.getFilesystem().relativize(p).getPath())
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    xcconfigPathsBuilder.addAll(relativeXcconfigPaths);
    filesToCopyInXcodeBuilder.addAll(result.getFilesToCopyInXcode());
    buildTargetToPbxTargetMapBuilder.putAll(result.getBuildTargetToGeneratedTargetMap());
    for (PBXTarget target : result.getBuildTargetToGeneratedTargetMap().values()) {
      targetToProjectPathMapBuilder.put(target, result.getProjectPath());
    }
  }

  private GenerationResult generateProjectForDirectory(
      ListeningExecutorService listeningExecutorService,
      XcodeProjectWriteOptions xcodeProjectWriteOptions,
      Cell projectCell,
      ImmutableSet<BuildTarget> rules,
      ImmutableSet<BuildTarget> targetsInRequiredProjects)
      throws IOException, InterruptedException {
    ProjectGenerator generator =
        new ProjectGenerator(
            xcodeDescriptions,
            projectGraph,
            dependenciesCache,
            projGenerationStateCache,
            rules,
            projectCell,
            buildFileName,
            projectGeneratorOptions,
            ruleKeyConfiguration,
            workspaceArguments.getSrcTarget().get(),
            targetsInRequiredProjects,
            defaultCxxPlatform,
            appleCxxFlavors,
            graphBuilderForNode,
            buckEventBus,
            halideBuckConfig,
            cxxBuckConfig,
            appleConfig,
            swiftBuckConfig,
            sharedLibraryToBundle);

    ProjectGenerator.Result result =
        generator.createXcodeProject(xcodeProjectWriteOptions, listeningExecutorService);

    result.sourcePathsToBuild.stream()
        .filter(sourcePath -> focusedTargetMatcher.matches(sourcePath.getTarget()))
        .forEach(
            sourcePath -> {
              Utils.addRequiredBuildTargetFromSourcePath(
                  sourcePath, requiredBuildTargetsBuilder, projectGraph, graphBuilderForNode);
            });

    ImmutableMap<BuildTarget, PBXTarget> buildTargetToGeneratedTargetMap =
        result.buildTargetsToGeneratedTargetMap;

    ImmutableSetMultimap.Builder<PBXProject, PBXTarget> targetMapBuilder =
        ImmutableSetMultimap.builder();
    targetMapBuilder.putAll(
        xcodeProjectWriteOptions.project(), buildTargetToGeneratedTargetMap.values());

    return GenerationResult.of(
        xcodeProjectWriteOptions.xcodeProjPath(),
        true,
        result.requiredBuildTargets,
        result.xcconfigPaths,
        ImmutableList.of(),
        buildTargetToGeneratedTargetMap,
        targetMapBuilder.build());
  }

  private void buildWorkspaceSchemes(
      TargetGraph projectGraph,
      boolean includeProjectTests,
      boolean includeDependenciesTests,
      String workspaceName,
      XcodeWorkspaceConfigDescriptionArg workspaceArguments,
      ImmutableMap.Builder<String, XcodeWorkspaceConfigDescriptionArg> schemeConfigsBuilder,
      ImmutableSetMultimap.Builder<String, Optional<TargetNode<?>>>
          schemeNameToSrcTargetNodeBuilder,
      ImmutableSetMultimap.Builder<String, TargetNode<?>> buildForTestNodesBuilder,
      ImmutableSetMultimap.Builder<String, TargetNode<AppleTestDescriptionArg>> testsBuilder) {
    ImmutableSetMultimap.Builder<String, TargetNode<AppleTestDescriptionArg>>
        extraTestNodesBuilder = ImmutableSetMultimap.builder();
    addWorkspaceScheme(
        xcodeDescriptions,
        projectGraph,
        dependenciesCache,
        workspaceName,
        workspaceArguments,
        schemeConfigsBuilder,
        schemeNameToSrcTargetNodeBuilder,
        extraTestNodesBuilder);
    addWorkspaceExtensionSchemes(
        projectGraph,
        workspaceName,
        workspaceArguments,
        schemeConfigsBuilder,
        schemeNameToSrcTargetNodeBuilder);
    addExtraWorkspaceSchemes(
        xcodeDescriptions,
        projectGraph,
        dependenciesCache,
        workspaceArguments.getExtraSchemes(),
        schemeConfigsBuilder,
        schemeNameToSrcTargetNodeBuilder,
        extraTestNodesBuilder);
    ImmutableSetMultimap<String, Optional<TargetNode<?>>> schemeNameToSrcTargetNode =
        schemeNameToSrcTargetNodeBuilder.build();
    ImmutableSetMultimap<String, TargetNode<AppleTestDescriptionArg>> extraTestNodes =
        extraTestNodesBuilder.build();

    buildWorkspaceSchemeTests(
        workspaceArguments.getSrcTarget(),
        projectGraph,
        includeProjectTests,
        includeDependenciesTests,
        schemeNameToSrcTargetNode,
        extraTestNodes,
        testsBuilder,
        buildForTestNodesBuilder);
  }

  private static void addWorkspaceScheme(
      XCodeDescriptions xcodeDescriptions,
      TargetGraph projectGraph,
      AppleDependenciesCache dependenciesCache,
      String schemeName,
      XcodeWorkspaceConfigDescriptionArg schemeArguments,
      ImmutableMap.Builder<String, XcodeWorkspaceConfigDescriptionArg> schemeConfigsBuilder,
      ImmutableSetMultimap.Builder<String, Optional<TargetNode<?>>>
          schemeNameToSrcTargetNodeBuilder,
      ImmutableSetMultimap.Builder<String, TargetNode<AppleTestDescriptionArg>>
          extraTestNodesBuilder) {
    LOG.debug("Adding scheme %s", schemeName);
    schemeConfigsBuilder.put(schemeName, schemeArguments);
    if (schemeArguments.getSrcTarget().isPresent()) {
      schemeNameToSrcTargetNodeBuilder.putAll(
          schemeName,
          Iterables.transform(
              AppleBuildRules.getSchemeBuildableTargetNodes(
                  xcodeDescriptions,
                  projectGraph,
                  Optional.of(dependenciesCache),
                  projectGraph.get(schemeArguments.getSrcTarget().get())),
              Optional::of));
    } else {
      schemeNameToSrcTargetNodeBuilder.put(
          XcodeWorkspaceConfigDescription.getWorkspaceNameFromArg(schemeArguments),
          Optional.empty());
    }

    for (BuildTarget extraTarget : schemeArguments.getExtraTargets()) {
      schemeNameToSrcTargetNodeBuilder.putAll(
          schemeName,
          Iterables.transform(
              AppleBuildRules.getSchemeBuildableTargetNodes(
                  xcodeDescriptions,
                  projectGraph,
                  Optional.of(dependenciesCache),
                  Objects.requireNonNull(projectGraph.get(extraTarget))),
              Optional::of));
    }

    for (BuildTarget extraShallowTarget : schemeArguments.getExtraShallowTargets()) {
      schemeNameToSrcTargetNodeBuilder.put(
          schemeName, Optional.of(projectGraph.get(extraShallowTarget)));
    }

    extraTestNodesBuilder.putAll(
        schemeName, getExtraTestTargetNodes(projectGraph, schemeArguments.getExtraTests()));
  }

  /**
   * Add a workspace scheme for each extension bundled with the source target of the workspace.
   *
   * @param projectGraph
   * @param schemeName
   * @param schemeArguments
   * @param schemeConfigsBuilder
   * @param schemeNameToSrcTargetNodeBuilder
   */
  private static void addWorkspaceExtensionSchemes(
      TargetGraph projectGraph,
      String schemeName,
      XcodeWorkspaceConfigDescriptionArg schemeArguments,
      ImmutableMap.Builder<String, XcodeWorkspaceConfigDescriptionArg> schemeConfigsBuilder,
      ImmutableSetMultimap.Builder<String, Optional<TargetNode<?>>>
          schemeNameToSrcTargetNodeBuilder) {
    if (!schemeArguments.getSrcTarget().isPresent()) {
      return;
    }

    LOG.debug("Potentially adding extension schemes for %s", schemeName);

    BuildTarget sourceBuildTarget = schemeArguments.getSrcTarget().get();
    TargetNode<?> sourceTargetNode = projectGraph.get(sourceBuildTarget);
    Set<BuildTarget> sourceTargetBuildDeps = sourceTargetNode.getBuildDeps();

    // Filter all of the source target's deps to find the bundled extensions that get an implicit
    // scheme.
    ImmutableSet<BuildTarget> implicitSchemeBuildTargets =
        sourceTargetBuildDeps.stream()
            .filter(t -> shouldIncludeImplicitExtensionSchemeForTargetNode(projectGraph.get(t)))
            .collect(ImmutableSet.toImmutableSet());

    // Create scheme for each bundled extension to allow Xcode to automatically begin debugging them
    // when this scheme it selected.
    implicitSchemeBuildTargets.forEach(
        (buildTarget -> {
          String extensionSchemeName = schemeName + "+" + buildTarget.getShortName();
          TargetNode<?> targetNode = projectGraph.get(buildTarget);

          schemeConfigsBuilder.put(
              extensionSchemeName, createImplicitExtensionWorkspaceArgs(sourceBuildTarget));

          schemeNameToSrcTargetNodeBuilder.put(extensionSchemeName, Optional.of(sourceTargetNode));
          schemeNameToSrcTargetNodeBuilder.put(extensionSchemeName, Optional.of(targetNode));
        }));
  }

  /**
   * @param targetNode `TargetNode` for potential implicit scheme.
   * @return True if implicit scheme should be included for `targetNode`, currently includes all app
   *     extensions to make debugging easier. Otherwise, false.
   */
  private static boolean shouldIncludeImplicitExtensionSchemeForTargetNode(
      TargetNode<?> targetNode) {
    // Bundle description required to determine product type for target node.
    if (!(targetNode.getConstructorArg() instanceof AppleBundleDescriptionArg)) {
      return false;
    }

    // Product type required to determine if product type matches.
    AppleBundleDescriptionArg bundleArg =
        (AppleBundleDescriptionArg) targetNode.getConstructorArg();
    if (!bundleArg.getXcodeProductType().isPresent()) {
      return false;
    }

    // Only create schemes for APP_EXTENSION.
    ProductType productType = ProductType.of(bundleArg.getXcodeProductType().get());
    return productType.equals(ProductTypes.APP_EXTENSION);
  }

  /**
   * @param sourceBuildTarget - The BuildTarget which will act as our fake workspace's `src_target`.
   * @return Workspace Args that describe a generic extension Xcode workspace containing
   *     `src_target` and one of its extensions.
   */
  private static XcodeWorkspaceConfigDescriptionArg createImplicitExtensionWorkspaceArgs(
      BuildTarget sourceBuildTarget) {
    return XcodeWorkspaceConfigDescriptionArg.builder()
        .setName("extension-dummy")
        .setSrcTarget(sourceBuildTarget)
        .setWasCreatedForAppExtension(true)
        .build();
  }

  private static void addExtraWorkspaceSchemes(
      XCodeDescriptions xcodeDescriptions,
      TargetGraph projectGraph,
      AppleDependenciesCache dependenciesCache,
      ImmutableSortedMap<String, BuildTarget> extraSchemes,
      ImmutableMap.Builder<String, XcodeWorkspaceConfigDescriptionArg> schemeConfigsBuilder,
      ImmutableSetMultimap.Builder<String, Optional<TargetNode<?>>>
          schemeNameToSrcTargetNodeBuilder,
      ImmutableSetMultimap.Builder<String, TargetNode<AppleTestDescriptionArg>>
          extraTestNodesBuilder) {
    for (Map.Entry<String, BuildTarget> extraSchemeEntry : extraSchemes.entrySet()) {
      BuildTarget extraSchemeTarget = extraSchemeEntry.getValue();
      Optional<TargetNode<?>> extraSchemeNode = projectGraph.getOptional(extraSchemeTarget);
      if (!extraSchemeNode.isPresent()
          || !(extraSchemeNode.get().getDescription() instanceof XcodeWorkspaceConfigDescription)) {
        throw new HumanReadableException(
            "Extra scheme target '%s' should be of type 'xcode_workspace_config'",
            extraSchemeTarget);
      }
      XcodeWorkspaceConfigDescriptionArg extraSchemeArg =
          (XcodeWorkspaceConfigDescriptionArg) extraSchemeNode.get().getConstructorArg();
      String schemeName = extraSchemeEntry.getKey();
      addWorkspaceScheme(
          xcodeDescriptions,
          projectGraph,
          dependenciesCache,
          schemeName,
          extraSchemeArg,
          schemeConfigsBuilder,
          schemeNameToSrcTargetNodeBuilder,
          extraTestNodesBuilder);
    }
  }

  /**
   * Find tests to run.
   *
   * @param targetGraph input target graph
   * @param includeProjectTests whether to include tests of nodes in the project
   * @param orderedTargetNodes target nodes for which to fetch tests for
   * @param extraTestBundleTargets extra tests to include
   * @return test targets that should be run.
   */
  private ImmutableSet<TargetNode<AppleTestDescriptionArg>> getOrderedTestNodes(
      Optional<BuildTarget> mainTarget,
      TargetGraph targetGraph,
      boolean includeProjectTests,
      boolean includeDependenciesTests,
      ImmutableSet<TargetNode<?>> orderedTargetNodes,
      ImmutableSet<TargetNode<AppleTestDescriptionArg>> extraTestBundleTargets) {
    ImmutableSet.Builder<TargetNode<AppleTestDescriptionArg>> testsBuilder = ImmutableSet.builder();
    if (includeProjectTests) {
      Optional<TargetNode<?>> mainTargetNode = Optional.empty();
      if (mainTarget.isPresent()) {
        mainTargetNode = targetGraph.getOptional(mainTarget.get());
      }
      for (TargetNode<?> node : orderedTargetNodes) {
        if (includeDependenciesTests
            || (mainTargetNode.isPresent() && node.equals(mainTargetNode.get()))) {
          if (!(node.getConstructorArg() instanceof HasTests)) {
            continue;
          }
          ImmutableList<BuildTarget> focusedTests =
              ((HasTests) node.getConstructorArg())
                  .getTests().stream()
                      .filter(t -> focusedTargetMatcher.matches(t))
                      .collect(ImmutableList.toImmutableList());
          // Show a warning if the target is not focused but the tests are.
          if (focusedTests.size() > 0 && !focusedTargetMatcher.matches(node.getBuildTarget())) {
            buckEventBus.post(
                ConsoleEvent.warning(
                    "Skipping tests of %s since it's not focused", node.getBuildTarget()));
            continue;
          }
          for (BuildTarget explicitTestTarget : focusedTests) {
            Optional<TargetNode<?>> explicitTestNode = targetGraph.getOptional(explicitTestTarget);
            if (explicitTestNode.isPresent()) {
              Optional<TargetNode<AppleTestDescriptionArg>> castedNode =
                  TargetNodes.castArg(explicitTestNode.get(), AppleTestDescriptionArg.class);
              if (castedNode.isPresent()) {
                testsBuilder.add(castedNode.get());
              } else {
                LOG.debug(
                    "Test target specified in '%s' is not a apple_test;"
                        + " not including in project: '%s'",
                    node.getBuildTarget(), explicitTestTarget);
              }
            } else {
              throw new HumanReadableException(
                  "Test target specified in '%s' is not in the target graph: '%s'",
                  node.getBuildTarget(), explicitTestTarget);
            }
          }
        }
      }
    }
    for (TargetNode<AppleTestDescriptionArg> extraTestTarget : extraTestBundleTargets) {
      testsBuilder.add(extraTestTarget);
    }
    return testsBuilder.build();
  }

  @SuppressWarnings("unchecked")
  private ImmutableSet<BuildTarget> getModularNodesToGenerate() {
    return projectGraph.getNodes().stream()
        .filter(
            modularNode ->
                modularNode.getConstructorArg() instanceof AppleNativeTargetDescriptionArg)
        .filter(NodeHelper::isModularAppleLibrary)
        .map(
            nativeTargetNode -> {
              TargetNode<AppleNativeTargetDescriptionArg> modularNode =
                  (TargetNode<AppleNativeTargetDescriptionArg>) nativeTargetNode;
              HeaderMode headerMode =
                  HeaderMode.forModuleMapMode(
                      modularNode
                          .getConstructorArg()
                          .getModulemapMode()
                          .orElse(appleConfig.moduleMapMode()));
              Flavor defaultPlatformFlavor =
                  modularNode
                      .getConstructorArg()
                      .getDefaultPlatform()
                      .orElse(defaultCxxPlatform.getFlavor());
              return NodeHelper.getModularMapTarget(modularNode, headerMode, defaultPlatformFlavor);
            })
        .collect(ImmutableSet.toImmutableSet());
  }

  /**
   * Find transitive dependencies of inputs for building.
   *
   * @param projectGraph {@link TargetGraph} containing nodes
   * @param nodes Nodes to fetch dependencies for.
   * @param excludes Nodes to exclude from dependencies list.
   * @return targets and their dependencies that should be build.
   */
  private static ImmutableSet<TargetNode<?>> getTransitiveDepsAndInputs(
      XCodeDescriptions xcodeDescriptions,
      TargetGraph projectGraph,
      AppleDependenciesCache dependenciesCache,
      Iterable<? extends TargetNode<?>> nodes,
      ImmutableSet<TargetNode<?>> excludes) {
    return FluentIterable.from(nodes)
        .transformAndConcat(
            input ->
                AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                    xcodeDescriptions,
                    projectGraph,
                    Optional.of(dependenciesCache),
                    RecursiveDependenciesMode.BUILDING,
                    input,
                    Optional.empty()))
        .append(nodes)
        .filter(
            input ->
                !excludes.contains(input)
                    && xcodeDescriptions.isXcodeDescription(input.getDescription()))
        .toSet();
  }

  private static ImmutableSet<TargetNode<AppleTestDescriptionArg>> getExtraTestTargetNodes(
      TargetGraph graph, Iterable<BuildTarget> targets) {
    ImmutableSet.Builder<TargetNode<AppleTestDescriptionArg>> builder = ImmutableSet.builder();
    for (TargetNode<?> node : graph.getAll(targets)) {
      Optional<TargetNode<AppleTestDescriptionArg>> castedNode =
          TargetNodes.castArg(node, AppleTestDescriptionArg.class);
      if (castedNode.isPresent()) {
        builder.add(castedNode.get());
      } else {
        throw new HumanReadableException(
            "Extra test target is not a test: '%s'", node.getBuildTarget());
      }
    }
    return builder.build();
  }

  private void buildWorkspaceSchemeTests(
      Optional<BuildTarget> mainTarget,
      TargetGraph projectGraph,
      boolean includeProjectTests,
      boolean includeDependenciesTests,
      ImmutableSetMultimap<String, Optional<TargetNode<?>>> schemeNameToSrcTargetNode,
      ImmutableSetMultimap<String, TargetNode<AppleTestDescriptionArg>> extraTestNodes,
      ImmutableSetMultimap.Builder<String, TargetNode<AppleTestDescriptionArg>>
          selectedTestsBuilder,
      ImmutableSetMultimap.Builder<String, TargetNode<?>> buildForTestNodesBuilder) {
    for (String schemeName : schemeNameToSrcTargetNode.keySet()) {
      ImmutableSet<TargetNode<?>> targetNodes =
          schemeNameToSrcTargetNode.get(schemeName).stream()
              .flatMap(RichStream::from)
              .collect(ImmutableSet.toImmutableSet());
      ImmutableSet<TargetNode<AppleTestDescriptionArg>> testNodes =
          getOrderedTestNodes(
              mainTarget,
              projectGraph,
              includeProjectTests,
              includeDependenciesTests,
              targetNodes,
              extraTestNodes.get(schemeName));
      selectedTestsBuilder.putAll(schemeName, testNodes);
      buildForTestNodesBuilder.putAll(
          schemeName,
          Iterables.filter(
              TopologicalSort.sort(projectGraph),
              getTransitiveDepsAndInputs(
                      xcodeDescriptions, projectGraph, dependenciesCache, testNodes, targetNodes)
                  ::contains));
    }
  }

  private void writeWorkspaceSchemes(
      String workspaceName,
      Path outputDirectory,
      ImmutableMap<String, XcodeWorkspaceConfigDescriptionArg> schemeConfigs,
      ImmutableSetMultimap<String, Optional<TargetNode<?>>> schemeNameToSrcTargetNode,
      ImmutableSetMultimap<String, PBXTarget> buildForTestTargets,
      ImmutableSetMultimap<String, PBXTarget> ungroupedTestTargets,
      ImmutableMap<PBXTarget, Path> targetToProjectPathMap,
      ImmutableMap<BuildTarget, PBXTarget> buildTargetToPBXTarget)
      throws IOException {
    for (Map.Entry<String, XcodeWorkspaceConfigDescriptionArg> schemeConfigEntry :
        schemeConfigs.entrySet()) {
      String schemeName = schemeConfigEntry.getKey();
      XcodeWorkspaceConfigDescriptionArg schemeConfigArg = schemeConfigEntry.getValue();
      if (schemeConfigArg.getSrcTarget().isPresent()
          && !focusedTargetMatcher.matches(schemeConfigArg.getSrcTarget().get())) {
        continue;
      }

      ImmutableSet<PBXTarget> orderedBuildTargets =
          schemeNameToSrcTargetNode.get(schemeName).stream()
              .distinct()
              .flatMap(RichStream::from)
              .map(TargetNode::getBuildTarget)
              .map(buildTargetToPBXTarget::get)
              .filter(Objects::nonNull)
              .collect(ImmutableSet.toImmutableSet());
      ImmutableSet<PBXTarget> orderedBuildTestTargets = buildForTestTargets.get(schemeName);
      ImmutableSet<PBXTarget> orderedRunTestTargets = ungroupedTestTargets.get(schemeName);

      Optional<String> runnablePath = schemeConfigArg.getExplicitRunnablePath();
      Optional<String> remoteRunnablePath;
      if (schemeConfigArg.getIsRemoteRunnable().orElse(false)) {
        // XXX TODO(beng): Figure out the actual name of the binary to launch
        remoteRunnablePath = Optional.of("/" + workspaceName);
      } else {
        remoteRunnablePath = Optional.empty();
      }

      Path schemeOutputDirectory = outputDirectory.resolve(workspaceName + ".xcworkspace");

      Optional<ImmutableMap<SchemeActionType, PBXTarget>> expandVariablesBasedOn = Optional.empty();
      if (schemeConfigArg.getExpandVariablesBasedOn().isPresent()) {
        Map<SchemeActionType, PBXTarget> mapTargets =
          schemeConfigArg.getExpandVariablesBasedOn().get().entrySet()
          .stream().collect(Collectors.toMap(Map.Entry::getKey, e -> buildTargetToPBXTarget.get(e.getValue()) ));
        expandVariablesBasedOn = Optional.of(ImmutableMap.copyOf(mapTargets));
      }

      SchemeGenerator schemeGenerator =
          buildSchemeGenerator(
              targetToProjectPathMap,
              schemeOutputDirectory,
              schemeName,
              schemeConfigArg.getSrcTarget().map(buildTargetToPBXTarget::get),
              Optional.of(schemeConfigArg),
              orderedBuildTargets,
              orderedBuildTestTargets,
              orderedRunTestTargets,
              runnablePath,
              remoteRunnablePath,
              expandVariablesBasedOn);
      schemeGenerator.writeScheme();
      schemeGenerators.put(schemeName, schemeGenerator);
    }
  }

  private SchemeGenerator buildSchemeGenerator(
      ImmutableMap<PBXTarget, Path> targetToProjectPathMap,
      Path outputDirectory,
      String schemeName,
      Optional<PBXTarget> primaryTarget,
      Optional<XcodeWorkspaceConfigDescriptionArg> schemeConfigArg,
      ImmutableSet<PBXTarget> orderedBuildTargets,
      ImmutableSet<PBXTarget> orderedBuildTestTargets,
      ImmutableSet<PBXTarget> orderedRunTestTargets,
      Optional<String> runnablePath,
      Optional<String> remoteRunnablePath,
      Optional<ImmutableMap<SchemeActionType, PBXTarget>> expandVariablesBasedOn) {
    Optional<ImmutableMap<SchemeActionType, ImmutableMap<String, String>>> environmentVariables =
        Optional.empty();
    Optional<ImmutableMap<SchemeActionType, ImmutableMap<String, String>>> commandLineArguments =
        Optional.empty();
    Optional<
            ImmutableMap<
                SchemeActionType, ImmutableMap<XCScheme.AdditionalActions, ImmutableList<String>>>>
        additionalSchemeActions = Optional.empty();
    XCScheme.LaunchAction.LaunchStyle launchStyle = XCScheme.LaunchAction.LaunchStyle.AUTO;
    Optional<XCScheme.LaunchAction.WatchInterface> watchInterface = Optional.empty();
    Optional<String> notificationPayloadFile = Optional.empty();
    Optional<Boolean> wasCreatedForAppExtension = Optional.empty();
    Optional<String> applicationLanguage = Optional.empty();
    Optional<String> applicationRegion = Optional.empty();

    if (schemeConfigArg.isPresent()) {
      environmentVariables = schemeConfigArg.get().getEnvironmentVariables();
      commandLineArguments = schemeConfigArg.get().getCommandLineArguments();
      additionalSchemeActions = schemeConfigArg.get().getAdditionalSchemeActions();
      launchStyle = schemeConfigArg.get().getLaunchStyle().orElse(launchStyle);
      watchInterface = schemeConfigArg.get().getWatchInterface();
      notificationPayloadFile = schemeConfigArg.get().getNotificationPayloadFile();
      wasCreatedForAppExtension = schemeConfigArg.get().getWasCreatedForAppExtension();
      applicationLanguage = schemeConfigArg.get().getApplicationLanguage();
      applicationRegion = schemeConfigArg.get().getApplicationRegion();
    }

    return new SchemeGenerator(
        rootCell.getFilesystem(),
        primaryTarget,
        orderedBuildTargets,
        orderedBuildTestTargets,
        orderedRunTestTargets,
        schemeName,
        outputDirectory,
        parallelizeBuild,
        wasCreatedForAppExtension,
        runnablePath,
        remoteRunnablePath,
        XcodeWorkspaceConfigDescription.getActionConfigNamesFromArg(schemeConfigArg),
        targetToProjectPathMap,
        environmentVariables,
        expandVariablesBasedOn,
        commandLineArguments,
        additionalSchemeActions,
        launchStyle,
        watchInterface,
        notificationPayloadFile,
        applicationLanguage,
        applicationRegion
        );
  }
}
