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
import com.facebook.buck.apple.AppleBundle;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleTestDescription;
import com.facebook.buck.apple.XcodeWorkspaceConfigDescription;
import com.facebook.buck.apple.xcode.XCScheme;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.halide.HalideBuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.Optionals;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

// import com.facebook.buck.io.ProjectFilesystem;

public class WorkspaceAndProjectGenerator {
  private static final Logger LOG = Logger.get(WorkspaceAndProjectGenerator.class);

  private final Cell rootCell;
  private final TargetGraph projectGraph;
  private final XcodeWorkspaceConfigDescription.Arg workspaceArguments;
  private final BuildTarget workspaceBuildTarget;
  private final ImmutableSet<UnflavoredBuildTarget> focusModules;
  private final ImmutableSet<ProjectGenerator.Option> projectGeneratorOptions;
  private final boolean combinedProject;
  private final boolean buildWithBuck;
  private final ImmutableList<String> buildWithBuckFlags;
  private final boolean parallelizeBuild;
  private final ExecutableFinder executableFinder;
  private final ImmutableMap<String, String> environment;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;
  private final CxxPlatform defaultCxxPlatform;

  private Optional<ProjectGenerator> combinedProjectGenerator;
  private Map<String, SchemeGenerator> schemeGenerators = new HashMap<>();
  private final String buildFileName;
  private final Function<TargetNode<?>, SourcePathResolver> sourcePathResolverForNode;
  private final BuckEventBus buckEventBus;

  private final ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder =
      ImmutableSet.builder();
  private final HalideBuckConfig halideBuckConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final AppleConfig appleConfig;
  private final SwiftBuckConfig swiftBuckConfig;

  public WorkspaceAndProjectGenerator(
      Cell cell,
      TargetGraph projectGraph,
      XcodeWorkspaceConfigDescription.Arg workspaceArguments,
      BuildTarget workspaceBuildTarget,
      Set<ProjectGenerator.Option> projectGeneratorOptions,
      boolean combinedProject,
      boolean buildWithBuck,
      ImmutableList<String> buildWithBuckFlags,
      ImmutableSet<UnflavoredBuildTarget> focusModules,
      boolean parallelizeBuild,
      ExecutableFinder executableFinder,
      ImmutableMap<String, String> environment,
      FlavorDomain<CxxPlatform> cxxPlatforms,
      CxxPlatform defaultCxxPlatform,
      String buildFileName,
      Function<TargetNode<?>, SourcePathResolver> sourcePathResolverForNode,
      BuckEventBus buckEventBus,
      HalideBuckConfig halideBuckConfig,
      CxxBuckConfig cxxBuckConfig,
      AppleConfig appleConfig,
      SwiftBuckConfig swiftBuckConfig) {
    this.rootCell = cell;
    this.projectGraph = projectGraph;
    this.workspaceArguments = workspaceArguments;
    this.workspaceBuildTarget = workspaceBuildTarget;
    this.projectGeneratorOptions = ImmutableSet.copyOf(projectGeneratorOptions);
    this.combinedProject = combinedProject;
    this.buildWithBuck = buildWithBuck;
    this.buildWithBuckFlags = buildWithBuckFlags;
    this.parallelizeBuild = parallelizeBuild;
    this.executableFinder = executableFinder;
    this.environment = environment;
    this.cxxPlatforms = cxxPlatforms;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.buildFileName = buildFileName;
    this.sourcePathResolverForNode = sourcePathResolverForNode;
    this.buckEventBus = buckEventBus;
    this.swiftBuckConfig = swiftBuckConfig;
    this.combinedProjectGenerator = Optional.empty();
    this.halideBuckConfig = halideBuckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.appleConfig = appleConfig;

    ImmutableSet.Builder<UnflavoredBuildTarget> builder = ImmutableSet.builder();
    builder.addAll(focusModules);
    // Add the main target only if focusModules is actually used.
    if (!focusModules.isEmpty() && workspaceArguments.srcTarget.isPresent()) {
      builder.add(workspaceArguments.srcTarget.get().getUnflavoredBuildTarget());
    }
    this.focusModules = builder.build();
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

  public Path generateWorkspaceAndDependentProjects(
      Map<Path, ProjectGenerator> projectGenerators,
      ListeningExecutorService listeningExecutorService)
      throws IOException, InterruptedException {
    LOG.debug("Generating workspace for target %s", workspaceBuildTarget);

    String workspaceName = XcodeWorkspaceConfigDescription.getWorkspaceNameFromArg(
        workspaceArguments);
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

    WorkspaceGenerator workspaceGenerator = new WorkspaceGenerator(
        rootCell.getFilesystem(),
        combinedProject ? "project" : workspaceName,
        outputDirectory);

    ImmutableMap.Builder<String, XcodeWorkspaceConfigDescription.Arg> schemeConfigsBuilder =
        ImmutableMap.builder();
    ImmutableSetMultimap.Builder<String, Optional<TargetNode<?>>> schemeNameToSrcTargetNodeBuilder =
        ImmutableSetMultimap.builder();
    ImmutableSetMultimap.Builder<String, TargetNode<?>>
        buildForTestNodesBuilder = ImmutableSetMultimap.builder();
    ImmutableSetMultimap.Builder<String, TargetNode<AppleTestDescription.Arg>>
        testsBuilder = ImmutableSetMultimap.builder();

    buildWorkspaceSchemes(
        projectGraph,
        projectGeneratorOptions.contains(ProjectGenerator.Option.INCLUDE_TESTS),
        projectGeneratorOptions.contains(ProjectGenerator.Option.INCLUDE_DEPENDENCIES_TESTS),
        workspaceName,
        workspaceArguments,
        schemeConfigsBuilder,
        schemeNameToSrcTargetNodeBuilder,
        buildForTestNodesBuilder,
        testsBuilder);

    ImmutableMap<String, XcodeWorkspaceConfigDescription.Arg> schemeConfigs =
        schemeConfigsBuilder.build();
    ImmutableSetMultimap<String, Optional<TargetNode<?>>> schemeNameToSrcTargetNode =
        schemeNameToSrcTargetNodeBuilder.build();
    ImmutableSetMultimap<String, TargetNode<?>> buildForTestNodes =
        buildForTestNodesBuilder.build();
    ImmutableSetMultimap<String, TargetNode<AppleTestDescription.Arg>> tests =
        testsBuilder.build();

    ImmutableSet<BuildTarget> targetsInRequiredProjects =
        Stream.concat(
            schemeNameToSrcTargetNode.values().stream().flatMap(Optionals::toStream),
            buildForTestNodes.values().stream())
            .map(HasBuildTarget::getBuildTarget)
            .collect(MoreCollectors.toImmutableSet());
    ImmutableMultimap.Builder<BuildTarget, PBXTarget> buildTargetToPbxTargetMapBuilder =
        ImmutableMultimap.builder();
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder =
        ImmutableMap.builder();
    Optional<BuildTarget> targetToBuildWithBuck = getTargetToBuildWithBuck();
    generateProjects(
        projectGenerators,
        listeningExecutorService,
        workspaceName,
        outputDirectory,
        workspaceGenerator,
        targetsInRequiredProjects,
        buildTargetToPbxTargetMapBuilder,
        targetToProjectPathMapBuilder,
        targetToBuildWithBuck);
    final Multimap<BuildTarget, PBXTarget> buildTargetToTarget =
        buildTargetToPbxTargetMapBuilder.build();

    writeWorkspaceSchemes(
        workspaceName,
        outputDirectory,
        schemeConfigs,
        schemeNameToSrcTargetNode,
        buildForTestNodes,
        tests,
        targetToProjectPathMapBuilder.build(),
        input -> buildTargetToTarget.get(input.getBuildTarget()),
        getTargetNodeToPBXTargetTransformFunction(buildTargetToTarget, buildWithBuck));

    return workspaceGenerator.writeWorkspace();
  }

  private void generateProjects(
      Map<Path, ProjectGenerator> projectGenerators,
      ListeningExecutorService listeningExecutorService,
      String workspaceName,
      Path outputDirectory,
      WorkspaceGenerator workspaceGenerator,
      ImmutableSet<BuildTarget> targetsInRequiredProjects,
      ImmutableMultimap.Builder<BuildTarget, PBXTarget> buildTargetToPbxTargetMapBuilder,
      ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder,
      Optional<BuildTarget> targetToBuildWithBuck)
      throws IOException, InterruptedException {
    if (combinedProject) {
      generateCombinedProject(
          workspaceName,
          outputDirectory,
          workspaceGenerator,
          targetsInRequiredProjects,
          buildTargetToPbxTargetMapBuilder,
          targetToProjectPathMapBuilder,
          targetToBuildWithBuck);
    } else {
      generateProject(
          projectGenerators,
          listeningExecutorService,
          workspaceGenerator,
          targetsInRequiredProjects,
          buildTargetToPbxTargetMapBuilder,
          targetToProjectPathMapBuilder,
          targetToBuildWithBuck);
    }
  }

  private static Function<BuildTarget, PBXTarget> getTargetNodeToPBXTargetTransformFunction(
      final Multimap<BuildTarget, PBXTarget> buildTargetToTarget,
      final boolean buildWithBuck) {
    return input -> {
      ImmutableList<PBXTarget> targets = ImmutableList.copyOf(buildTargetToTarget.get(input));
      if (targets.size() == 1) {
        return targets.get(0);
      }
      // The only reason why a build target would map to more than one project target is if
      // there are two project targets: one is the usual one, the other is a target that just
      // shells out to Buck.
      Preconditions.checkState(targets.size() == 2);
      PBXTarget first = targets.get(0);
      PBXTarget second = targets.get(1);
      Preconditions.checkState(
          first.getName().endsWith(ProjectGenerator.BUILD_WITH_BUCK_POSTFIX) ^
              second.getName().endsWith(ProjectGenerator.BUILD_WITH_BUCK_POSTFIX));
      PBXTarget buildWithBuckTarget;
      PBXTarget buildWithXcodeTarget;
      if (first.getName().endsWith(ProjectGenerator.BUILD_WITH_BUCK_POSTFIX)) {
        buildWithBuckTarget = first;
        buildWithXcodeTarget = second;
      } else {
        buildWithXcodeTarget = first;
        buildWithBuckTarget = second;
      }
      return buildWithBuck ? buildWithBuckTarget : buildWithXcodeTarget;
    };
  }

  private void generateProject(
      final Map<Path, ProjectGenerator> projectGenerators,
      ListeningExecutorService listeningExecutorService,
      WorkspaceGenerator workspaceGenerator,
      ImmutableSet<BuildTarget> targetsInRequiredProjects,
      ImmutableMultimap.Builder<BuildTarget, PBXTarget> buildTargetToPbxTargetMapBuilder,
      ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder,
      final Optional<BuildTarget> targetToBuildWithBuck)
      throws IOException, InterruptedException {
    ImmutableMultimap.Builder<Cell, BuildTarget> projectCellToBuildTargetsBuilder =
        ImmutableMultimap.builder();
    for (TargetNode<?> targetNode : projectGraph.getNodes()) {
      BuildTarget buildTarget = targetNode.getBuildTarget();
      projectCellToBuildTargetsBuilder.put(rootCell.getCell(buildTarget), buildTarget);
    }
    ImmutableMultimap<Cell, BuildTarget> projectCellToBuildTargets =
        projectCellToBuildTargetsBuilder.build();
    List<ListenableFuture<GenerationResult>> projectGeneratorFutures = new ArrayList<>();
    for (final Cell projectCell : projectCellToBuildTargets.keySet()) {
      ImmutableMultimap.Builder<Path, BuildTarget> projectDirectoryToBuildTargetsBuilder =
          ImmutableMultimap.builder();
      final ImmutableSet<BuildTarget> cellRules =
          ImmutableSet.copyOf(projectCellToBuildTargets.get(projectCell));
      for (BuildTarget buildTarget : cellRules) {
        projectDirectoryToBuildTargetsBuilder.put(buildTarget.getBasePath(), buildTarget);
      }
      ImmutableMultimap<Path, BuildTarget> projectDirectoryToBuildTargets =
        projectDirectoryToBuildTargetsBuilder.build();
      final Path relativeTargetCell = rootCell.getRoot().relativize(projectCell.getRoot());
      for (final Path projectDirectory : projectDirectoryToBuildTargets.keySet()) {
        final ImmutableSet<BuildTarget> rules = filterRulesForProjectDirectory(
            projectGraph,
            ImmutableSet.copyOf(projectDirectoryToBuildTargets.get(projectDirectory)));
        if (Sets.intersection(targetsInRequiredProjects, rules).isEmpty()) {
          continue;
        }

        projectGeneratorFutures.add(
            listeningExecutorService.submit(
                () -> {
                  GenerationResult result = generateProjectForDirectory(
                        projectGenerators,
                        targetToBuildWithBuck,
                        projectCell,
                        projectDirectory,
                        rules);
                  // convert the projectPath to relative to the target cell here
                  result = GenerationResult.of(
                      relativeTargetCell.resolve(result.getProjectPath()),
                      result.isProjectGenerated(),
                      result.getRequiredBuildTargets(),
                      result.getBuildTargetToGeneratedTargetMap());
                  return result;
                }));
      }
    }

    List<GenerationResult> generationResults;
    try {
      generationResults = Futures.allAsList(projectGeneratorFutures).get();
    } catch (ExecutionException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), IOException.class);
      Throwables.propagateIfPossible(e.getCause());
      throw new IllegalStateException("Unexpected exception: ", e);
    }
    for (GenerationResult result : generationResults) {
      if (!result.isProjectGenerated()) {
        continue;
      }
      workspaceGenerator.addFilePath(result.getProjectPath());
      processGenerationResult(
          buildTargetToPbxTargetMapBuilder,
          targetToProjectPathMapBuilder,
          result);
    }
  }

  private void processGenerationResult(
      ImmutableMultimap.Builder<BuildTarget, PBXTarget> buildTargetToPbxTargetMapBuilder,
      ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder,
      GenerationResult result) {
    requiredBuildTargetsBuilder.addAll(result.getRequiredBuildTargets());
    buildTargetToPbxTargetMapBuilder.putAll(result.getBuildTargetToGeneratedTargetMap());
    for (PBXTarget target : result.getBuildTargetToGeneratedTargetMap().values()) {
      targetToProjectPathMapBuilder.put(target, result.getProjectPath());
    }
  }

  private GenerationResult generateProjectForDirectory(
      Map<Path, ProjectGenerator> projectGenerators,
      Optional<BuildTarget> targetToBuildWithBuck,
      Cell projectCell,
      Path projectDirectory,
      final ImmutableSet<BuildTarget> rules) throws IOException {
    boolean shouldGenerateProjects = false;
    ProjectGenerator generator;
    synchronized (projectGenerators) {
      generator = projectGenerators.get(projectDirectory);
      if (generator != null) {
        LOG.debug("Already generated project for target %s, skipping", projectDirectory);
      } else {
        LOG.debug(
            "Generating project for directory %s with targets %s",
            projectDirectory,
            rules);
        String projectName;
        if (projectDirectory.getFileName().toString().equals("")) {
          // If we're generating a project in the root directory, use a generic name.
          projectName = "Project";
        } else {
          // Otherwise, name the project the same thing as the directory we're in.
          projectName = projectDirectory.getFileName().toString();
        }
        generator = new ProjectGenerator(
            projectGraph,
            rules,
            projectCell,
            projectDirectory,
            projectName,
            buildFileName,
            projectGeneratorOptions,
            Optionals.bind(
                targetToBuildWithBuck,
                input -> rules.contains(input)
                    ? Optional.of(input)
                    : Optional.empty()),
            buildWithBuckFlags,
            focusModules,
            executableFinder,
            environment,
            cxxPlatforms,
            defaultCxxPlatform,
            sourcePathResolverForNode,
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
    ImmutableMultimap<BuildTarget, PBXTarget> buildTargetToGeneratedTargetMap =
        ImmutableMultimap.of();
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
        buildTargetToGeneratedTargetMap);
  }

  private void generateCombinedProject(
      String workspaceName,
      Path outputDirectory,
      WorkspaceGenerator workspaceGenerator,
      ImmutableSet<BuildTarget> targetsInRequiredProjects,
      ImmutableMultimap.Builder<BuildTarget, PBXTarget> buildTargetToPbxTargetMapBuilder,
      ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder,
      Optional<BuildTarget> targetToBuildWithBuck) throws IOException {
    LOG.debug("Generating a combined project");
    ProjectGenerator generator = new ProjectGenerator(
        projectGraph,
        targetsInRequiredProjects,
        rootCell,
        outputDirectory.getParent(),
        workspaceName,
        buildFileName,
        projectGeneratorOptions,
        targetToBuildWithBuck,
        buildWithBuckFlags,
        focusModules,
        executableFinder,
        environment,
        cxxPlatforms,
        defaultCxxPlatform,
        sourcePathResolverForNode,
        buckEventBus,
        halideBuckConfig,
        cxxBuckConfig,
        appleConfig,
        swiftBuckConfig);
    combinedProjectGenerator = Optional.of(generator);
    generator.createXcodeProjects();

    GenerationResult result = GenerationResult.of(
        generator.getProjectPath(),
        generator.isProjectGenerated(),
        generator.getRequiredBuildTargets(),
        generator.getBuildTargetToGeneratedTargetMap());
    workspaceGenerator.addFilePath(result.getProjectPath(), Optional.empty());
    processGenerationResult(
        buildTargetToPbxTargetMapBuilder,
        targetToProjectPathMapBuilder,
        result);
  }

  private Optional<BuildTarget> getTargetToBuildWithBuck() {
    if (buildWithBuck) {
      return workspaceArguments.srcTarget;
    } else {
      return Optional.empty();
    }
  }

  private void buildWorkspaceSchemes(
      TargetGraph projectGraph,
      boolean includeProjectTests,
      boolean includeDependenciesTests,
      String workspaceName,
      XcodeWorkspaceConfigDescription.Arg workspaceArguments,
      ImmutableMap.Builder<String, XcodeWorkspaceConfigDescription.Arg> schemeConfigsBuilder,
      ImmutableSetMultimap.Builder<String, Optional<TargetNode<?>>>
          schemeNameToSrcTargetNodeBuilder,
      ImmutableSetMultimap.Builder<String, TargetNode<?>>
          buildForTestNodesBuilder,
      ImmutableSetMultimap.Builder<String, TargetNode<AppleTestDescription.Arg>> testsBuilder) {
    ImmutableSetMultimap.Builder<String, TargetNode<AppleTestDescription.Arg>>
        extraTestNodesBuilder = ImmutableSetMultimap.builder();
    addWorkspaceScheme(
        projectGraph,
        workspaceName,
        workspaceArguments,
        schemeConfigsBuilder,
        schemeNameToSrcTargetNodeBuilder,
        extraTestNodesBuilder);
    addExtraWorkspaceSchemes(
        projectGraph,
        workspaceArguments.extraSchemes,
        schemeConfigsBuilder,
        schemeNameToSrcTargetNodeBuilder,
        extraTestNodesBuilder);
    ImmutableSetMultimap<String, Optional<TargetNode<?>>> schemeNameToSrcTargetNode =
        schemeNameToSrcTargetNodeBuilder.build();
    ImmutableSetMultimap<String, TargetNode<AppleTestDescription.Arg>>
        extraTestNodes = extraTestNodesBuilder.build();

    buildWorkspaceSchemeTests(
        workspaceArguments.srcTarget,
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
      String schemeName,
      XcodeWorkspaceConfigDescription.Arg schemeArguments,
      ImmutableMap.Builder<String, XcodeWorkspaceConfigDescription.Arg> schemeConfigsBuilder,
      ImmutableSetMultimap.Builder<String, Optional<TargetNode<?>>>
          schemeNameToSrcTargetNodeBuilder,
      ImmutableSetMultimap.Builder<String, TargetNode<AppleTestDescription.Arg>>
          extraTestNodesBuilder) {
    LOG.debug("Adding scheme %s", schemeName);
    schemeConfigsBuilder.put(schemeName, schemeArguments);
    if (schemeArguments.srcTarget.isPresent()) {
      schemeNameToSrcTargetNodeBuilder.putAll(
          schemeName,
          Iterables.transform(
              AppleBuildRules.getSchemeBuildableTargetNodes(
                  projectGraph,
                  projectGraph.get(
                      schemeArguments.srcTarget.get().getBuildTarget())),
              Optional::of));
    } else {
      schemeNameToSrcTargetNodeBuilder.put(
          XcodeWorkspaceConfigDescription.getWorkspaceNameFromArg(schemeArguments),
          Optional.empty());
    }

    for (BuildTarget extraTarget : schemeArguments.extraTargets) {
      schemeNameToSrcTargetNodeBuilder.putAll(
          schemeName,
          Iterables.transform(
              AppleBuildRules.getSchemeBuildableTargetNodes(
                  projectGraph,
                  Preconditions.checkNotNull(projectGraph.get(extraTarget))),
              Optional::of));
    }

    extraTestNodesBuilder.putAll(
        schemeName,
        getExtraTestTargetNodes(
            projectGraph,
            schemeArguments.extraTests));
  }

  private static void addExtraWorkspaceSchemes(
      TargetGraph projectGraph,
      ImmutableSortedMap<String, BuildTarget> extraSchemes,
      ImmutableMap.Builder<String, XcodeWorkspaceConfigDescription.Arg> schemeConfigsBuilder,
      ImmutableSetMultimap.Builder<String, Optional<TargetNode<?>>>
          schemeNameToSrcTargetNodeBuilder,
      ImmutableSetMultimap.Builder<String, TargetNode<AppleTestDescription.Arg>>
          extraTestNodesBuilder) {
    for (Map.Entry<String, BuildTarget> extraSchemeEntry : extraSchemes.entrySet()) {
      BuildTarget extraSchemeTarget = extraSchemeEntry.getValue();
      Optional<TargetNode<?>> extraSchemeNode = projectGraph.getOptional(extraSchemeTarget);
      if (!extraSchemeNode.isPresent() ||
          extraSchemeNode.get().getType() != XcodeWorkspaceConfigDescription.TYPE) {
        throw new HumanReadableException(
            "Extra scheme target '%s' should be of type 'xcode_workspace_config'",
            extraSchemeTarget);
      }
      XcodeWorkspaceConfigDescription.Arg extraSchemeArg =
          (XcodeWorkspaceConfigDescription.Arg) extraSchemeNode.get().getConstructorArg();
      String schemeName = extraSchemeEntry.getKey();
      addWorkspaceScheme(
          projectGraph,
          schemeName,
          extraSchemeArg,
          schemeConfigsBuilder,
          schemeNameToSrcTargetNodeBuilder,
          extraTestNodesBuilder);
    }
  }

  private static ImmutableSet<BuildTarget> filterRulesForProjectDirectory(
      TargetGraph projectGraph,
      ImmutableSet<BuildTarget> projectBuildTargets) {
    // ProjectGenerator implicitly generates targets for all apple_binary rules which
    // are referred to by apple_bundle rules' 'binary' field.
    //
    // We used to support an explicit xcode_project_config() which
    // listed all dependencies explicitly, but now that we synthesize
    // one, we need to ensure we continue to only pass apple_binary
    // targets which do not belong to apple_bundle rules.
    ImmutableSet.Builder<BuildTarget> binaryTargetsInsideBundlesBuilder =
        ImmutableSet.builder();
    for (TargetNode<?> projectTargetNode : projectGraph.getAll(projectBuildTargets)) {
      if (projectTargetNode.getType() == AppleBundleDescription.TYPE) {
        AppleBundleDescription.Arg appleBundleDescriptionArg =
            (AppleBundleDescription.Arg) projectTargetNode.getConstructorArg();
        // We don't support apple_bundle rules referring to apple_binary rules
        // outside their current directory.
        Preconditions.checkState(
            appleBundleDescriptionArg.binary.getBasePath().equals(
                projectTargetNode.getBuildTarget().getBasePath()),
            "apple_bundle target %s contains reference to binary %s outside base path %s",
            projectTargetNode.getBuildTarget(),
            appleBundleDescriptionArg.binary,
            projectTargetNode.getBuildTarget().getBasePath());
        binaryTargetsInsideBundlesBuilder.add(appleBundleDescriptionArg.binary);
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
   *
   * @return test targets that should be run.
   */
  private ImmutableSet<TargetNode<AppleTestDescription.Arg>> getOrderedTestNodes(
      Optional<BuildTarget> mainTarget,
      TargetGraph targetGraph,
      boolean includeProjectTests,
      boolean includeDependenciesTests,
      ImmutableSet<TargetNode<?>> orderedTargetNodes,
      ImmutableSet<TargetNode<AppleTestDescription.Arg>> extraTestBundleTargets) {
    LOG.debug("Getting ordered test target nodes for %s", orderedTargetNodes);
    ImmutableSet.Builder<TargetNode<AppleTestDescription.Arg>> testsBuilder =
        ImmutableSet.builder();
    if (includeProjectTests) {
      Optional<TargetNode<?>> mainTargetNode = Optional.empty();
      if (mainTarget.isPresent()) {
        mainTargetNode = targetGraph.getOptional(mainTarget.get());
      }
      for (TargetNode<?> node : orderedTargetNodes) {
        if (includeDependenciesTests ||
            (mainTargetNode.isPresent() && node.equals(mainTargetNode.get()))) {
          if (!(node.getConstructorArg() instanceof HasTests)) {
            continue;
          }
          for (BuildTarget explicitTestTarget : ((HasTests) node.getConstructorArg()).getTests()) {
            Optional<TargetNode<?>> explicitTestNode = targetGraph.getOptional(explicitTestTarget);
            if (explicitTestNode.isPresent()) {
              Optional<TargetNode<AppleTestDescription.Arg>> castedNode =
                  explicitTestNode.get().castArg(AppleTestDescription.Arg.class);
              if (castedNode.isPresent()) {
                testsBuilder.add(castedNode.get());
              } else {
                buckEventBus.post(ConsoleEvent.warning(
                    "Test target specified in '%s' is not a apple_test;" +
                        " not including in project: '%s'",
                    node.getBuildTarget(),
                    explicitTestTarget));
              }
            } else {
              throw new HumanReadableException(
                  "Test target specified in '%s' is not in the target graph: '%s'",
                  node.getBuildTarget(),
                  explicitTestTarget);
            }
          }
        }
      }
    }
    for (TargetNode<AppleTestDescription.Arg> extraTestTarget : extraTestBundleTargets) {
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
  private static ImmutableSet<TargetNode<?>> getTransitiveDepsAndInputs(
      final TargetGraph projectGraph,
      Iterable<? extends TargetNode<?>> nodes,
      final ImmutableSet<TargetNode<?>> excludes) {
    return FluentIterable
        .from(nodes)
        .transformAndConcat(
            new Function<TargetNode<?>, Iterable<TargetNode<?>>>() {
              @Override
              public Iterable<TargetNode<?>> apply(TargetNode<?> input) {
                return AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                    projectGraph,
                    AppleBuildRules.RecursiveDependenciesMode.BUILDING,
                    input,
                    Optional.empty());
              }
            })
        .append(nodes)
        .filter(
            input -> !excludes.contains(input) &&
                AppleBuildRules.isXcodeTargetBuildRuleType(input.getType()))
        .toSet();
  }

  private static ImmutableSet<TargetNode<AppleTestDescription.Arg>> getExtraTestTargetNodes(
      TargetGraph graph,
      Iterable<BuildTarget> targets) {
    ImmutableSet.Builder<TargetNode<AppleTestDescription.Arg>> builder = ImmutableSet.builder();
    for (TargetNode<?> node : graph.getAll(targets)) {
      Optional<TargetNode<AppleTestDescription.Arg>> castedNode =
          node.castArg(AppleTestDescription.Arg.class);
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
      ImmutableSetMultimap<String, TargetNode<AppleTestDescription.Arg>> extraTestNodes,
      ImmutableSetMultimap.Builder<String, TargetNode<AppleTestDescription.Arg>>
          selectedTestsBuilder,
      ImmutableSetMultimap.Builder<String, TargetNode<?>>
          buildForTestNodesBuilder) {
    for (String schemeName : schemeNameToSrcTargetNode.keySet()) {
      ImmutableSet<TargetNode<?>> targetNodes =
          schemeNameToSrcTargetNode.get(schemeName).stream()
              .flatMap(Optionals::toStream)
              .collect(MoreCollectors.toImmutableSet());
      ImmutableSet<TargetNode<AppleTestDescription.Arg>> testNodes =
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
          TopologicalSort.sort(
              projectGraph,
              getTransitiveDepsAndInputs(projectGraph, testNodes, targetNodes)::contains));
    }
  }

  private void writeWorkspaceSchemes(
      String workspaceName,
      Path outputDirectory,
      ImmutableMap<String, XcodeWorkspaceConfigDescription.Arg> schemeConfigs,
      ImmutableSetMultimap<String, Optional<TargetNode<?>>> schemeNameToSrcTargetNode,
      ImmutableSetMultimap<String, TargetNode<?>> buildForTestNodes,
      ImmutableSetMultimap<String, TargetNode<AppleTestDescription.Arg>> ungroupedTests,
      ImmutableMap<PBXTarget, Path> targetToProjectPathMap,
      Function<TargetNode<?>, Collection<PBXTarget>> targetNodeToPBXTargetTransformer,
      Function<BuildTarget, PBXTarget> buildTargetToPBXTargetTransformer) throws IOException {
    for (Map.Entry<String, XcodeWorkspaceConfigDescription.Arg> schemeConfigEntry :
        schemeConfigs.entrySet()) {
      String schemeName = schemeConfigEntry.getKey();
      XcodeWorkspaceConfigDescription.Arg schemeConfigArg = schemeConfigEntry.getValue();
      if (schemeConfigArg.srcTarget.isPresent() &&
          !ProjectGenerator.shouldIncludeBuildTargetIntoFocusedProject(
            focusModules, schemeConfigArg.srcTarget.get())) {
        continue;
      }
      Iterable<PBXTarget> orderedBuildTargets = schemeNameToSrcTargetNode.get(schemeName).stream()
          .distinct()
          .flatMap(Optionals::toStream)
          .flatMap(targetNode -> targetNodeToPBXTargetTransformer.apply(targetNode).stream())
          .collect(MoreCollectors.toImmutableSet());
      FluentIterable<PBXTarget> orderedBuildTestTargets = FluentIterable
          .from(buildForTestNodes.get(schemeName))
          .transformAndConcat(targetNodeToPBXTargetTransformer);
      FluentIterable<PBXTarget> orderedRunTestTargets = FluentIterable
          .from(ungroupedTests.get(schemeName))
          .transformAndConcat(targetNodeToPBXTargetTransformer);
      Optional<String> runnablePath = schemeConfigArg.explicitRunnablePath;
      Optional<BuildTarget> targetToBuildWithBuck = getTargetToBuildWithBuck();
      if (buildWithBuck && targetToBuildWithBuck.isPresent() && !runnablePath.isPresent()) {
        Optional<String> productName = getProductName(
            schemeNameToSrcTargetNode.get(schemeName),
            targetToBuildWithBuck);
        String binaryName = AppleBundle.getBinaryName(targetToBuildWithBuck.get(), productName);
        runnablePath = Optional.of(
            rootCell.getFilesystem().resolve(
                ProjectGenerator.getScratchPathForAppBundle(
                    rootCell.getFilesystem(),
                    targetToBuildWithBuck.get(),
                    binaryName)
            ).toString());
      }
      Optional<String> remoteRunnablePath;
      if (schemeConfigArg.isRemoteRunnable.orElse(false)) {
        // XXX TODO(bhamiltoncx): Figure out the actual name of the binary to launch
        remoteRunnablePath = Optional.of("/" + workspaceName);
      } else {
        remoteRunnablePath = Optional.empty();
      }
      SchemeGenerator schemeGenerator = new SchemeGenerator(
          rootCell.getFilesystem(),
          schemeConfigArg.srcTarget.map(buildTargetToPBXTargetTransformer::apply),
          orderedBuildTargets,
          orderedBuildTestTargets,
          orderedRunTestTargets,
          schemeName,
          combinedProject ?
              outputDirectory :
              outputDirectory.resolve(workspaceName + ".xcworkspace"),
          buildWithBuck,
          parallelizeBuild,
          runnablePath,
          remoteRunnablePath,
          XcodeWorkspaceConfigDescription.getActionConfigNamesFromArg(workspaceArguments),
          targetToProjectPathMap,
          schemeConfigArg.launchStyle.orElse(XCScheme.LaunchAction.LaunchStyle.AUTO));
      schemeGenerator.writeScheme();
      schemeGenerators.put(schemeName, schemeGenerator);
    }
  }

  private Optional<String> getProductName(
      ImmutableSet<Optional<TargetNode<?>>> targetNodes,
      Optional<BuildTarget> targetToBuildWithBuck) {
    Optional<String> productName = Optional.empty();
    Optional<TargetNode<?>> buildWithBuckTargetNode = getTargetNodeForBuildTarget(
        targetToBuildWithBuck,
        targetNodes);
    if (buildWithBuckTargetNode.isPresent() && targetToBuildWithBuck.isPresent()) {
      productName = Optional.of(
          ProjectGenerator.getProductName(
              buildWithBuckTargetNode.get(),
              targetToBuildWithBuck.get()));
    }
    return productName;
  }

  private Optional<TargetNode<?>> getTargetNodeForBuildTarget(
      Optional<BuildTarget> targetToBuildWithBuck,
      ImmutableSet<Optional<TargetNode<?>>> targetNodes) {
    Optional<TargetNode<?>> buildWithBuckTargetNode = Optional.empty();
    for (Optional<TargetNode<?>> targetNode : targetNodes) {
      if (targetNode.isPresent()) {
        UnflavoredBuildTarget unflavoredBuildTarget =
            targetNode.get().getBuildTarget().getUnflavoredBuildTarget();
        if (unflavoredBuildTarget.equals(targetToBuildWithBuck.get().getUnflavoredBuildTarget())) {
          buildWithBuckTargetNode = targetNode;
          break;
        }
      }
    }
    return buildWithBuckTargetNode;
  }
}
