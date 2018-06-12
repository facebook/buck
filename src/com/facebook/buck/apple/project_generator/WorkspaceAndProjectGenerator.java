/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.apple.AppleBuildRules;
import com.facebook.buck.apple.AppleBuildRules.RecursiveDependenciesMode;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleBundleDescriptionArg;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleDependenciesCache;
import com.facebook.buck.apple.AppleTestDescriptionArg;
import com.facebook.buck.apple.XcodeWorkspaceConfigDescription;
import com.facebook.buck.apple.XcodeWorkspaceConfigDescriptionArg;
import com.facebook.buck.apple.xcode.XCScheme;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.halide.HalideBuckConfig;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Stream;

public class WorkspaceAndProjectGenerator {
  private static final Logger LOG = Logger.get(WorkspaceAndProjectGenerator.class);

  private final Cell rootCell;
  private final TargetGraph projectGraph;
  private final AppleDependenciesCache dependenciesCache;
  private final ProjectGenerationStateCache projGenerationStateCache;
  private final XcodeWorkspaceConfigDescriptionArg workspaceArguments;
  private final BuildTarget workspaceBuildTarget;
  private final FocusedModuleTargetMatcher focusModules;
  private final ProjectGeneratorOptions projectGeneratorOptions;
  private final boolean combinedProject;
  private final boolean parallelizeBuild;
  private final CxxPlatform defaultCxxPlatform;
  private final ImmutableSet<Flavor> appleCxxFlavors;

  private Optional<ProjectGenerator> combinedProjectGenerator;
  private final Map<String, SchemeGenerator> schemeGenerators = new HashMap<>();
  private final String buildFileName;
  private final Function<TargetNode<?, ?>, ActionGraphBuilder> graphBuilderForNode;
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

  public WorkspaceAndProjectGenerator(
      Cell cell,
      TargetGraph projectGraph,
      XcodeWorkspaceConfigDescriptionArg workspaceArguments,
      BuildTarget workspaceBuildTarget,
      ProjectGeneratorOptions projectGeneratorOptions,
      boolean combinedProject,
      FocusedModuleTargetMatcher focusModules,
      boolean parallelizeBuild,
      CxxPlatform defaultCxxPlatform,
      ImmutableSet<Flavor> appleCxxFlavors,
      String buildFileName,
      Function<TargetNode<?, ?>, ActionGraphBuilder> graphBuilderForNode,
      BuckEventBus buckEventBus,
      RuleKeyConfiguration ruleKeyConfiguration,
      HalideBuckConfig halideBuckConfig,
      CxxBuckConfig cxxBuckConfig,
      AppleConfig appleConfig,
      SwiftBuckConfig swiftBuckConfig) {
    this.rootCell = cell;
    this.projectGraph = projectGraph;
    this.dependenciesCache = new AppleDependenciesCache(projectGraph);
    this.ruleKeyConfiguration = ruleKeyConfiguration;
    this.projGenerationStateCache = new ProjectGenerationStateCache();
    this.workspaceArguments = workspaceArguments;
    this.workspaceBuildTarget = workspaceBuildTarget;
    this.projectGeneratorOptions = projectGeneratorOptions;
    this.combinedProject = combinedProject;
    this.parallelizeBuild = parallelizeBuild;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.appleCxxFlavors = appleCxxFlavors;
    this.buildFileName = buildFileName;
    this.graphBuilderForNode = graphBuilderForNode;
    this.buckEventBus = buckEventBus;
    this.swiftBuckConfig = swiftBuckConfig;
    this.combinedProjectGenerator = Optional.empty();
    this.halideBuckConfig = halideBuckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.appleConfig = appleConfig;

    this.focusModules =
        focusModules.map(
            inputs ->
                // Update the focused modules list (if present) to contain srcTarget (if present).
                workspaceArguments
                    .getSrcTarget()
                    .map(
                        srcTarget ->
                            ImmutableSet.<UnflavoredBuildTarget>builder()
                                .addAll(inputs)
                                .add(srcTarget.getUnflavoredBuildTarget())
                                .build())
                    .orElse(inputs));
  }

  @VisibleForTesting
  Optional<ProjectGenerator> getCombinedProjectGenerator() {
    return combinedProjectGenerator;
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

  public Path generateWorkspaceAndDependentProjects(
      Map<Path, ProjectGenerator> projectGenerators,
      ListeningExecutorService listeningExecutorService)
      throws IOException, InterruptedException {
    LOG.debug("Generating workspace for target %s", workspaceBuildTarget);

    String workspaceName =
        XcodeWorkspaceConfigDescription.getWorkspaceNameFromArg(workspaceArguments);
    Path outputDirectory;
    if (combinedProject) {
      workspaceName += "-Combined";
      outputDirectory =
          BuildTargets.getGenPath(rootCell.getFilesystem(), workspaceBuildTarget, "%s")
              .getParent()
              .resolve(workspaceName + ".xcodeproj");
    } else {
      outputDirectory = workspaceBuildTarget.getBasePath();
    }

    WorkspaceGenerator workspaceGenerator =
        new WorkspaceGenerator(
            rootCell.getFilesystem(), combinedProject ? "project" : workspaceName, outputDirectory);

    ImmutableMap.Builder<String, XcodeWorkspaceConfigDescriptionArg> schemeConfigsBuilder =
        ImmutableMap.builder();
    ImmutableSetMultimap.Builder<String, Optional<TargetNode<?, ?>>>
        schemeNameToSrcTargetNodeBuilder = ImmutableSetMultimap.builder();
    ImmutableSetMultimap.Builder<String, TargetNode<?, ?>> buildForTestNodesBuilder =
        ImmutableSetMultimap.builder();
    ImmutableSetMultimap.Builder<String, TargetNode<AppleTestDescriptionArg, ?>> testsBuilder =
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
    ImmutableSetMultimap<String, Optional<TargetNode<?, ?>>> schemeNameToSrcTargetNode =
        schemeNameToSrcTargetNodeBuilder.build();
    ImmutableSetMultimap<String, TargetNode<?, ?>> buildForTestNodes =
        buildForTestNodesBuilder.build();
    ImmutableSetMultimap<String, TargetNode<AppleTestDescriptionArg, ?>> tests =
        testsBuilder.build();

    ImmutableSet<BuildTarget> targetsInRequiredProjects =
        Stream.concat(
                schemeNameToSrcTargetNode.values().stream().flatMap(Optionals::toStream),
                buildForTestNodes.values().stream())
            .map(TargetNode::getBuildTarget)
            .collect(ImmutableSet.toImmutableSet());
    ImmutableMap.Builder<BuildTarget, PBXTarget> buildTargetToPbxTargetMapBuilder =
        ImmutableMap.builder();
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();
    generateProjects(
        projectGenerators,
        listeningExecutorService,
        workspaceName,
        outputDirectory,
        workspaceGenerator,
        targetsInRequiredProjects,
        buildTargetToPbxTargetMapBuilder,
        targetToProjectPathMapBuilder);

    writeWorkspaceMetaData(outputDirectory, workspaceName);

    if (projectGeneratorOptions.shouldGenerateHeaderSymlinkTreesOnly()) {
      return workspaceGenerator.getWorkspaceDir();
    } else {
      ImmutableMap<BuildTarget, PBXTarget> buildTargetToTarget =
          buildTargetToPbxTargetMapBuilder.build();

      writeWorkspaceSchemes(
          workspaceName,
          outputDirectory,
          schemeConfigs,
          schemeNameToSrcTargetNode,
          buildForTestNodes,
          tests,
          targetToProjectPathMapBuilder.build(),
          buildTargetToTarget);

      return workspaceGenerator.writeWorkspace();
    }
  }

  private void writeWorkspaceMetaData(Path outputDirectory, String workspaceName)
      throws IOException {
    Path path =
        combinedProject ? outputDirectory : outputDirectory.resolve(workspaceName + ".xcworkspace");
    rootCell.getFilesystem().mkdirs(path);
    ImmutableList<String> requiredTargetsStrings =
        getRequiredBuildTargets()
            .stream()
            .map(Object::toString)
            .collect(ImmutableList.toImmutableList());
    ImmutableMap<String, Object> data =
        ImmutableMap.of(
            "required-targets",
            requiredTargetsStrings,
            "xcconfig-paths",
            getXcconfigPaths(),
            "copy-in-xcode",
            getFilesToCopyInXcode());
    String jsonString = ObjectMappers.WRITER.writeValueAsString(data);
    rootCell
        .getFilesystem()
        .writeContentsToPath(jsonString, path.resolve("buck-project.meta.json"));
  }

  private void generateProjects(
      Map<Path, ProjectGenerator> projectGenerators,
      ListeningExecutorService listeningExecutorService,
      String workspaceName,
      Path outputDirectory,
      WorkspaceGenerator workspaceGenerator,
      ImmutableSet<BuildTarget> targetsInRequiredProjects,
      ImmutableMap.Builder<BuildTarget, PBXTarget> buildTargetToPbxTargetMapBuilder,
      ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder)
      throws IOException, InterruptedException {
    if (combinedProject) {
      generateCombinedProject(
          workspaceName,
          outputDirectory,
          workspaceGenerator,
          targetsInRequiredProjects,
          buildTargetToPbxTargetMapBuilder,
          targetToProjectPathMapBuilder);
    } else {
      generateProject(
          projectGenerators,
          listeningExecutorService,
          workspaceGenerator,
          targetsInRequiredProjects,
          buildTargetToPbxTargetMapBuilder,
          targetToProjectPathMapBuilder);
    }
  }

  private void generateProject(
      Map<Path, ProjectGenerator> projectGenerators,
      ListeningExecutorService listeningExecutorService,
      WorkspaceGenerator workspaceGenerator,
      ImmutableSet<BuildTarget> targetsInRequiredProjects,
      ImmutableMap.Builder<BuildTarget, PBXTarget> buildTargetToPbxTargetMapBuilder,
      ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder)
      throws IOException, InterruptedException {
    ImmutableMultimap.Builder<Cell, BuildTarget> projectCellToBuildTargetsBuilder =
        ImmutableMultimap.builder();
    for (TargetNode<?, ?> targetNode : projectGraph.getNodes()) {
      BuildTarget buildTarget = targetNode.getBuildTarget();
      projectCellToBuildTargetsBuilder.put(rootCell.getCell(buildTarget), buildTarget);
    }
    ImmutableMultimap<Cell, BuildTarget> projectCellToBuildTargets =
        projectCellToBuildTargetsBuilder.build();
    List<ListenableFuture<GenerationResult>> projectGeneratorFutures = new ArrayList<>();
    for (Cell projectCell : projectCellToBuildTargets.keySet()) {
      ImmutableMultimap.Builder<Path, BuildTarget> projectDirectoryToBuildTargetsBuilder =
          ImmutableMultimap.builder();
      ImmutableSet<BuildTarget> cellRules =
          ImmutableSet.copyOf(projectCellToBuildTargets.get(projectCell));
      for (BuildTarget buildTarget : cellRules) {
        projectDirectoryToBuildTargetsBuilder.put(buildTarget.getBasePath(), buildTarget);
      }
      ImmutableMultimap<Path, BuildTarget> projectDirectoryToBuildTargets =
          projectDirectoryToBuildTargetsBuilder.build();
      Path relativeTargetCell = rootCell.getRoot().relativize(projectCell.getRoot());
      for (Path projectDirectory : projectDirectoryToBuildTargets.keySet()) {
        ImmutableSet<BuildTarget> rules =
            filterRulesForProjectDirectory(
                projectGraph,
                ImmutableSet.copyOf(projectDirectoryToBuildTargets.get(projectDirectory)));
        if (Sets.intersection(targetsInRequiredProjects, rules).isEmpty()) {
          continue;
        }

        boolean isMainProject =
            workspaceArguments.getSrcTarget().isPresent()
                && rules.contains(workspaceArguments.getSrcTarget().get());
        projectGeneratorFutures.add(
            listeningExecutorService.submit(
                () -> {
                  GenerationResult result =
                      generateProjectForDirectory(
                          projectGenerators,
                          projectCell,
                          projectDirectory,
                          rules,
                          isMainProject,
                          targetsInRequiredProjects);
                  // convert the projectPath to relative to the target cell here
                  result =
                      GenerationResult.of(
                          relativeTargetCell.resolve(result.getProjectPath()),
                          result.isProjectGenerated(),
                          result.getRequiredBuildTargets(),
                          result.getXcconfigPaths(),
                          result.getFilesToCopyInXcode(),
                          result.getBuildTargetToGeneratedTargetMap());
                  return result;
                }));
      }
    }

    List<GenerationResult> generationResults;
    try {
      generationResults = Futures.allAsList(projectGeneratorFutures).get();
    } catch (ExecutionException e) {
      Throwables.throwIfInstanceOf(e.getCause(), IOException.class);
      Throwables.throwIfUnchecked(e.getCause());
      throw new IllegalStateException("Unexpected exception: ", e);
    }
    for (GenerationResult result : generationResults) {
      if (!result.isProjectGenerated()) {
        continue;
      }
      workspaceGenerator.addFilePath(result.getProjectPath());
      processGenerationResult(
          buildTargetToPbxTargetMapBuilder, targetToProjectPathMapBuilder, result);
    }
  }

  private void processGenerationResult(
      ImmutableMap.Builder<BuildTarget, PBXTarget> buildTargetToPbxTargetMapBuilder,
      ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder,
      GenerationResult result) {
    requiredBuildTargetsBuilder.addAll(result.getRequiredBuildTargets());
    ImmutableSortedSet<Path> relativeXcconfigPaths =
        result
            .getXcconfigPaths()
            .stream()
            .map((Path p) -> rootCell.getFilesystem().relativize(p))
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    xcconfigPathsBuilder.addAll(relativeXcconfigPaths);
    filesToCopyInXcodeBuilder.addAll(result.getFilesToCopyInXcode());
    buildTargetToPbxTargetMapBuilder.putAll(result.getBuildTargetToGeneratedTargetMap());
    for (PBXTarget target : result.getBuildTargetToGeneratedTargetMap().values()) {
      targetToProjectPathMapBuilder.put(target, result.getProjectPath());
    }
  }

  private GenerationResult generateProjectForDirectory(
      Map<Path, ProjectGenerator> projectGenerators,
      Cell projectCell,
      Path projectDirectory,
      ImmutableSet<BuildTarget> rules,
      boolean isMainProject,
      ImmutableSet<BuildTarget> targetsInRequiredProjects)
      throws IOException {
    boolean shouldGenerateProjects = false;
    ProjectGenerator generator;
    synchronized (projectGenerators) {
      generator = projectGenerators.get(projectDirectory);
      if (generator != null) {
        LOG.debug("Already generated project for target %s, skipping", projectDirectory);
      } else {
        LOG.debug("Generating project for directory %s with targets %s", projectDirectory, rules);
        String projectName;
        if (projectDirectory.getFileName().toString().equals("")) {
          // If we're generating a project in the root directory, use a generic name.
          projectName = "Project";
        } else {
          // Otherwise, name the project the same thing as the directory we're in.
          projectName = projectDirectory.getFileName().toString();
        }
        generator =
            new ProjectGenerator(
                projectGraph,
                dependenciesCache,
                projGenerationStateCache,
                rules,
                projectCell,
                projectDirectory,
                projectName,
                buildFileName,
                projectGeneratorOptions,
                ruleKeyConfiguration,
                isMainProject,
                workspaceArguments.getSrcTarget(),
                targetsInRequiredProjects,
                focusModules,
                defaultCxxPlatform,
                appleCxxFlavors,
                graphBuilderForNode,
                buckEventBus,
                halideBuckConfig,
                cxxBuckConfig,
                appleConfig,
                swiftBuckConfig);
        projectGenerators.put(projectDirectory, generator);
        shouldGenerateProjects = true;
      }
    }

    ImmutableSet<BuildTarget> requiredBuildTargets = ImmutableSet.of();
    ImmutableMap<BuildTarget, PBXTarget> buildTargetToGeneratedTargetMap = ImmutableMap.of();
    if (shouldGenerateProjects) {
      generator.createXcodeProjects();
    }
    if (generator.isProjectGenerated()) {
      requiredBuildTargets = generator.getRequiredBuildTargets();
      buildTargetToGeneratedTargetMap = generator.getBuildTargetToGeneratedTargetMap();
    }

    return GenerationResult.of(
        generator.getProjectPath(),
        generator.isProjectGenerated(),
        requiredBuildTargets,
        generator.getXcconfigPaths(),
        generator.getFilesToCopyInXcode(),
        buildTargetToGeneratedTargetMap);
  }

  private void generateCombinedProject(
      String workspaceName,
      Path outputDirectory,
      WorkspaceGenerator workspaceGenerator,
      ImmutableSet<BuildTarget> targetsInRequiredProjects,
      ImmutableMap.Builder<BuildTarget, PBXTarget> buildTargetToPbxTargetMapBuilder,
      ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder)
      throws IOException {
    LOG.debug("Generating a combined project");
    ProjectGenerator generator =
        new ProjectGenerator(
            projectGraph,
            dependenciesCache,
            projGenerationStateCache,
            targetsInRequiredProjects,
            rootCell,
            outputDirectory.getParent(),
            workspaceName,
            buildFileName,
            projectGeneratorOptions,
            ruleKeyConfiguration,
            true,
            workspaceArguments.getSrcTarget(),
            targetsInRequiredProjects,
            focusModules,
            defaultCxxPlatform,
            appleCxxFlavors,
            graphBuilderForNode,
            buckEventBus,
            halideBuckConfig,
            cxxBuckConfig,
            appleConfig,
            swiftBuckConfig);
    combinedProjectGenerator = Optional.of(generator);
    generator.createXcodeProjects();

    GenerationResult result =
        GenerationResult.of(
            generator.getProjectPath(),
            generator.isProjectGenerated(),
            generator.getRequiredBuildTargets(),
            generator.getXcconfigPaths(),
            generator.getFilesToCopyInXcode(),
            generator.getBuildTargetToGeneratedTargetMap());
    workspaceGenerator.addFilePath(result.getProjectPath(), Optional.empty());
    processGenerationResult(
        buildTargetToPbxTargetMapBuilder, targetToProjectPathMapBuilder, result);
  }

  private void buildWorkspaceSchemes(
      TargetGraph projectGraph,
      boolean includeProjectTests,
      boolean includeDependenciesTests,
      String workspaceName,
      XcodeWorkspaceConfigDescriptionArg workspaceArguments,
      ImmutableMap.Builder<String, XcodeWorkspaceConfigDescriptionArg> schemeConfigsBuilder,
      ImmutableSetMultimap.Builder<String, Optional<TargetNode<?, ?>>>
          schemeNameToSrcTargetNodeBuilder,
      ImmutableSetMultimap.Builder<String, TargetNode<?, ?>> buildForTestNodesBuilder,
      ImmutableSetMultimap.Builder<String, TargetNode<AppleTestDescriptionArg, ?>> testsBuilder) {
    ImmutableSetMultimap.Builder<String, TargetNode<AppleTestDescriptionArg, ?>>
        extraTestNodesBuilder = ImmutableSetMultimap.builder();
    addWorkspaceScheme(
        projectGraph,
        dependenciesCache,
        workspaceName,
        workspaceArguments,
        schemeConfigsBuilder,
        schemeNameToSrcTargetNodeBuilder,
        extraTestNodesBuilder);
    addExtraWorkspaceSchemes(
        projectGraph,
        dependenciesCache,
        workspaceArguments.getExtraSchemes(),
        schemeConfigsBuilder,
        schemeNameToSrcTargetNodeBuilder,
        extraTestNodesBuilder);
    ImmutableSetMultimap<String, Optional<TargetNode<?, ?>>> schemeNameToSrcTargetNode =
        schemeNameToSrcTargetNodeBuilder.build();
    ImmutableSetMultimap<String, TargetNode<AppleTestDescriptionArg, ?>> extraTestNodes =
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
      TargetGraph projectGraph,
      AppleDependenciesCache dependenciesCache,
      String schemeName,
      XcodeWorkspaceConfigDescriptionArg schemeArguments,
      ImmutableMap.Builder<String, XcodeWorkspaceConfigDescriptionArg> schemeConfigsBuilder,
      ImmutableSetMultimap.Builder<String, Optional<TargetNode<?, ?>>>
          schemeNameToSrcTargetNodeBuilder,
      ImmutableSetMultimap.Builder<String, TargetNode<AppleTestDescriptionArg, ?>>
          extraTestNodesBuilder) {
    LOG.debug("Adding scheme %s", schemeName);
    schemeConfigsBuilder.put(schemeName, schemeArguments);
    if (schemeArguments.getSrcTarget().isPresent()) {
      schemeNameToSrcTargetNodeBuilder.putAll(
          schemeName,
          Iterables.transform(
              AppleBuildRules.getSchemeBuildableTargetNodes(
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
                  projectGraph,
                  Optional.of(dependenciesCache),
                  Preconditions.checkNotNull(projectGraph.get(extraTarget))),
              Optional::of));
    }

    for (BuildTarget extraShallowTarget : schemeArguments.getExtraShallowTargets()) {
      schemeNameToSrcTargetNodeBuilder.put(
          schemeName, Optional.of(projectGraph.get(extraShallowTarget)));
    }

    extraTestNodesBuilder.putAll(
        schemeName, getExtraTestTargetNodes(projectGraph, schemeArguments.getExtraTests()));
  }

  private static void addExtraWorkspaceSchemes(
      TargetGraph projectGraph,
      AppleDependenciesCache dependenciesCache,
      ImmutableSortedMap<String, BuildTarget> extraSchemes,
      ImmutableMap.Builder<String, XcodeWorkspaceConfigDescriptionArg> schemeConfigsBuilder,
      ImmutableSetMultimap.Builder<String, Optional<TargetNode<?, ?>>>
          schemeNameToSrcTargetNodeBuilder,
      ImmutableSetMultimap.Builder<String, TargetNode<AppleTestDescriptionArg, ?>>
          extraTestNodesBuilder) {
    for (Map.Entry<String, BuildTarget> extraSchemeEntry : extraSchemes.entrySet()) {
      BuildTarget extraSchemeTarget = extraSchemeEntry.getValue();
      Optional<TargetNode<?, ?>> extraSchemeNode = projectGraph.getOptional(extraSchemeTarget);
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
          projectGraph,
          dependenciesCache,
          schemeName,
          extraSchemeArg,
          schemeConfigsBuilder,
          schemeNameToSrcTargetNodeBuilder,
          extraTestNodesBuilder);
    }
  }

  private static ImmutableSet<BuildTarget> filterRulesForProjectDirectory(
      TargetGraph projectGraph, ImmutableSet<BuildTarget> projectBuildTargets) {
    // ProjectGenerator implicitly generates targets for all apple_binary rules which
    // are referred to by apple_bundle rules' 'binary' field.
    //
    // We used to support an explicit xcode_project_config() which
    // listed all dependencies explicitly, but now that we synthesize
    // one, we need to ensure we continue to only pass apple_binary
    // targets which do not belong to apple_bundle rules.
    ImmutableSet.Builder<BuildTarget> binaryTargetsInsideBundlesBuilder = ImmutableSet.builder();
    for (TargetNode<?, ?> projectTargetNode : projectGraph.getAll(projectBuildTargets)) {
      if (projectTargetNode.getDescription() instanceof AppleBundleDescription) {
        AppleBundleDescriptionArg appleBundleDescriptionArg =
            (AppleBundleDescriptionArg) projectTargetNode.getConstructorArg();
        // We don't support apple_bundle rules referring to apple_binary rules
        // outside their current directory.
        BuildTarget binaryTarget =
            appleBundleDescriptionArg
                .getBinary()
                .orElseThrow(
                    () ->
                        new HumanReadableException(
                            "apple_bundle rules without binary attribute are not supported."));
        Preconditions.checkState(
            binaryTarget.getBasePath().equals(projectTargetNode.getBuildTarget().getBasePath()),
            "apple_bundle target %s contains reference to binary %s outside base path %s",
            projectTargetNode.getBuildTarget(),
            appleBundleDescriptionArg.getBinary(),
            projectTargetNode.getBuildTarget().getBasePath());
        binaryTargetsInsideBundlesBuilder.add(binaryTarget);
      }
    }
    ImmutableSet<BuildTarget> binaryTargetsInsideBundles =
        binaryTargetsInsideBundlesBuilder.build();

    // Remove all apple_binary targets which are inside bundles from
    // the rest of the build targets in the project.
    return ImmutableSet.copyOf(Sets.difference(projectBuildTargets, binaryTargetsInsideBundles));
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
  private ImmutableSet<TargetNode<AppleTestDescriptionArg, ?>> getOrderedTestNodes(
      Optional<BuildTarget> mainTarget,
      TargetGraph targetGraph,
      boolean includeProjectTests,
      boolean includeDependenciesTests,
      ImmutableSet<TargetNode<?, ?>> orderedTargetNodes,
      ImmutableSet<TargetNode<AppleTestDescriptionArg, ?>> extraTestBundleTargets) {
    LOG.debug("Getting ordered test target nodes for %s", orderedTargetNodes);
    ImmutableSet.Builder<TargetNode<AppleTestDescriptionArg, ?>> testsBuilder =
        ImmutableSet.builder();
    if (includeProjectTests) {
      Optional<TargetNode<?, ?>> mainTargetNode = Optional.empty();
      if (mainTarget.isPresent()) {
        mainTargetNode = targetGraph.getOptional(mainTarget.get());
      }
      for (TargetNode<?, ?> node : orderedTargetNodes) {
        if (includeDependenciesTests
            || (mainTargetNode.isPresent() && node.equals(mainTargetNode.get()))) {
          if (!(node.getConstructorArg() instanceof HasTests)) {
            continue;
          }
          ImmutableList<BuildTarget> focusedTests =
              ((HasTests) node.getConstructorArg())
                  .getTests()
                  .stream()
                  .filter(t -> focusModules.isFocusedOn(t))
                  .collect(ImmutableList.toImmutableList());
          // Show a warning if the target is not focused but the tests are.
          if (focusedTests.size() > 0 && !focusModules.isFocusedOn(node.getBuildTarget())) {
            buckEventBus.post(
                ConsoleEvent.warning(
                    "Skipping tests of %s since it's not focused", node.getBuildTarget()));
            continue;
          }
          for (BuildTarget explicitTestTarget : focusedTests) {
            Optional<TargetNode<?, ?>> explicitTestNode =
                targetGraph.getOptional(explicitTestTarget);
            if (explicitTestNode.isPresent()) {
              Optional<TargetNode<AppleTestDescriptionArg, ?>> castedNode =
                  explicitTestNode.get().castArg(AppleTestDescriptionArg.class);
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
    for (TargetNode<AppleTestDescriptionArg, ?> extraTestTarget : extraTestBundleTargets) {
      testsBuilder.add(extraTestTarget);
    }
    return testsBuilder.build();
  }

  /**
   * Find transitive dependencies of inputs for building.
   *
   * @param projectGraph {@link TargetGraph} containing nodes
   * @param nodes Nodes to fetch dependencies for.
   * @param excludes Nodes to exclude from dependencies list.
   * @return targets and their dependencies that should be build.
   */
  private static ImmutableSet<TargetNode<?, ?>> getTransitiveDepsAndInputs(
      TargetGraph projectGraph,
      AppleDependenciesCache dependenciesCache,
      Iterable<? extends TargetNode<?, ?>> nodes,
      ImmutableSet<TargetNode<?, ?>> excludes) {
    return FluentIterable.from(nodes)
        .transformAndConcat(
            input ->
                AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                    projectGraph,
                    Optional.of(dependenciesCache),
                    RecursiveDependenciesMode.BUILDING,
                    input,
                    Optional.empty()))
        .append(nodes)
        .filter(
            input ->
                !excludes.contains(input)
                    && AppleBuildRules.isXcodeTargetDescription(input.getDescription()))
        .toSet();
  }

  private static ImmutableSet<TargetNode<AppleTestDescriptionArg, ?>> getExtraTestTargetNodes(
      TargetGraph graph, Iterable<BuildTarget> targets) {
    ImmutableSet.Builder<TargetNode<AppleTestDescriptionArg, ?>> builder = ImmutableSet.builder();
    for (TargetNode<?, ?> node : graph.getAll(targets)) {
      Optional<TargetNode<AppleTestDescriptionArg, ?>> castedNode =
          node.castArg(AppleTestDescriptionArg.class);
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
      ImmutableSetMultimap<String, Optional<TargetNode<?, ?>>> schemeNameToSrcTargetNode,
      ImmutableSetMultimap<String, TargetNode<AppleTestDescriptionArg, ?>> extraTestNodes,
      ImmutableSetMultimap.Builder<String, TargetNode<AppleTestDescriptionArg, ?>>
          selectedTestsBuilder,
      ImmutableSetMultimap.Builder<String, TargetNode<?, ?>> buildForTestNodesBuilder) {
    for (String schemeName : schemeNameToSrcTargetNode.keySet()) {
      ImmutableSet<TargetNode<?, ?>> targetNodes =
          schemeNameToSrcTargetNode
              .get(schemeName)
              .stream()
              .flatMap(Optionals::toStream)
              .collect(ImmutableSet.toImmutableSet());
      ImmutableSet<TargetNode<AppleTestDescriptionArg, ?>> testNodes =
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
              getTransitiveDepsAndInputs(projectGraph, dependenciesCache, testNodes, targetNodes)
                  ::contains));
    }
  }

  private void writeWorkspaceSchemes(
      String workspaceName,
      Path outputDirectory,
      ImmutableMap<String, XcodeWorkspaceConfigDescriptionArg> schemeConfigs,
      ImmutableSetMultimap<String, Optional<TargetNode<?, ?>>> schemeNameToSrcTargetNode,
      ImmutableSetMultimap<String, TargetNode<?, ?>> buildForTestNodes,
      ImmutableSetMultimap<String, TargetNode<AppleTestDescriptionArg, ?>> ungroupedTests,
      ImmutableMap<PBXTarget, Path> targetToProjectPathMap,
      ImmutableMap<BuildTarget, PBXTarget> buildTargetToPBXTarget)
      throws IOException {
    for (Map.Entry<String, XcodeWorkspaceConfigDescriptionArg> schemeConfigEntry :
        schemeConfigs.entrySet()) {
      String schemeName = schemeConfigEntry.getKey();
      XcodeWorkspaceConfigDescriptionArg schemeConfigArg = schemeConfigEntry.getValue();
      if (schemeConfigArg.getSrcTarget().isPresent()
          && !focusModules.isFocusedOn(schemeConfigArg.getSrcTarget().get())) {
        continue;
      }
      ImmutableSet<PBXTarget> orderedBuildTargets =
          schemeNameToSrcTargetNode
              .get(schemeName)
              .stream()
              .distinct()
              .flatMap(Optionals::toStream)
              .map(TargetNode::getBuildTarget)
              .map(buildTargetToPBXTarget::get)
              .filter(Objects::nonNull)
              .collect(ImmutableSet.toImmutableSet());
      ImmutableSet<PBXTarget> orderedBuildTestTargets =
          buildForTestNodes
              .get(schemeName)
              .stream()
              .map(TargetNode::getBuildTarget)
              .map(buildTargetToPBXTarget::get)
              .filter(Objects::nonNull)
              .collect(ImmutableSet.toImmutableSet());
      ImmutableSet<PBXTarget> orderedRunTestTargets =
          ungroupedTests
              .get(schemeName)
              .stream()
              .map(TargetNode::getBuildTarget)
              .map(buildTargetToPBXTarget::get)
              .filter(Objects::nonNull)
              .collect(ImmutableSet.toImmutableSet());
      Optional<String> runnablePath = schemeConfigArg.getExplicitRunnablePath();
      Optional<String> remoteRunnablePath;
      if (schemeConfigArg.getIsRemoteRunnable().orElse(false)) {
        // XXX TODO(beng): Figure out the actual name of the binary to launch
        remoteRunnablePath = Optional.of("/" + workspaceName);
      } else {
        remoteRunnablePath = Optional.empty();
      }
      SchemeGenerator schemeGenerator =
          new SchemeGenerator(
              rootCell.getFilesystem(),
              schemeConfigArg.getSrcTarget().map(buildTargetToPBXTarget::get),
              orderedBuildTargets,
              orderedBuildTestTargets,
              orderedRunTestTargets,
              schemeName,
              combinedProject
                  ? outputDirectory
                  : outputDirectory.resolve(workspaceName + ".xcworkspace"),
              parallelizeBuild,
              runnablePath,
              remoteRunnablePath,
              XcodeWorkspaceConfigDescription.getActionConfigNamesFromArg(workspaceArguments),
              targetToProjectPathMap,
              schemeConfigArg.getEnvironmentVariables(),
              schemeConfigArg.getAdditionalSchemeActions(),
              schemeConfigArg.getLaunchStyle().orElse(XCScheme.LaunchAction.LaunchStyle.AUTO));
      schemeGenerator.writeScheme();
      schemeGenerators.put(schemeName, schemeGenerator);
    }
  }
}
