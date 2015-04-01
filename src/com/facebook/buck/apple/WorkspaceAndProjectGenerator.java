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

package com.facebook.buck.apple;

import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public class WorkspaceAndProjectGenerator {
  private static final Logger LOG = Logger.get(WorkspaceAndProjectGenerator.class);

  private final ProjectFilesystem projectFilesystem;
  private final TargetGraph projectGraph;
  private final XcodeWorkspaceConfigDescription.Arg workspaceArguments;
  private final BuildTarget workspaceBuildTarget;
  private final ImmutableSet<ProjectGenerator.Option> projectGeneratorOptions;
  private final ImmutableSet<TargetNode<AppleTestDescription.Arg>> extraTestBundleTargetNodes;
  private final boolean combinedProject;
  private ImmutableSet<TargetNode<AppleTestDescription.Arg>> groupableTests = ImmutableSet.of();

  private Optional<ProjectGenerator> combinedProjectGenerator;
  private Optional<ProjectGenerator> combinedTestsProjectGenerator = Optional.absent();
  private Optional<SchemeGenerator> schemeGenerator = Optional.absent();
  private final String buildFileName;
  private final Function<TargetNode<?>, Path> outputPathOfNode;

  private final ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder =
      ImmutableSet.builder();

  public WorkspaceAndProjectGenerator(
      ProjectFilesystem projectFilesystem,
      TargetGraph projectGraph,
      XcodeWorkspaceConfigDescription.Arg workspaceArguments,
      BuildTarget workspaceBuildTarget,
      Set<ProjectGenerator.Option> projectGeneratorOptions,
      boolean combinedProject,
      String buildFileName,
      Function<TargetNode<?>, Path> outputPathOfNode) {
    this.projectFilesystem = projectFilesystem;
    this.projectGraph = projectGraph;
    this.workspaceArguments = workspaceArguments;
    this.workspaceBuildTarget = workspaceBuildTarget;
    this.projectGeneratorOptions = ImmutableSet.copyOf(projectGeneratorOptions);
    this.combinedProject = combinedProject;
    this.buildFileName = buildFileName;
    this.outputPathOfNode = outputPathOfNode;
    this.combinedProjectGenerator = Optional.absent();
    extraTestBundleTargetNodes = getExtraTestTargetNodes(
        projectGraph, workspaceArguments.extraTests.get());
  }

  @VisibleForTesting
  Optional<ProjectGenerator> getCombinedProjectGenerator() {
    return combinedProjectGenerator;
  }

  @VisibleForTesting
  Optional<SchemeGenerator> getSchemeGenerator() {
    return schemeGenerator;
  }

  /**
   * Return the project generator to generate the combined test bundles.
   * This is only set when generating separated projects.
   */
  @VisibleForTesting
  Optional<ProjectGenerator> getCombinedTestsProjectGenerator() {
    return combinedTestsProjectGenerator;
  }

  public ImmutableSet<BuildTarget> getRequiredBuildTargets() {
    return requiredBuildTargetsBuilder.build();
  }

  /**
   * Set the tests that can be grouped. These tests will always be generated as static libraries,
   * and linked into synthetic test targets.
   *
   * While it may seem unnecessary to do this for tests which may not be able to share a bundle with
   * any other test, note that WorkspaceAndProjectGenerator only has a limited view of all the tests
   * that exists, but the generated projects are shared amongst all Workspaces.
   */
  public WorkspaceAndProjectGenerator setGroupableTests(
      Set<TargetNode<AppleTestDescription.Arg>> tests) {
    groupableTests = ImmutableSet.copyOf(tests);
    return this;
  }

  public Path generateWorkspaceAndDependentProjects(
        Map<Path, ProjectGenerator> projectGenerators)
      throws IOException {
    LOG.debug("Generating workspace for target %s", workspaceBuildTarget);

    String workspaceName = XcodeWorkspaceConfigDescription.getWorkspaceNameFromArg(
        workspaceArguments);
    Path outputDirectory;
    if (combinedProject) {
      workspaceName += "-Combined";
      outputDirectory =
          BuildTargets.getGenPath(workspaceBuildTarget, "%s").getParent();
    } else {
      outputDirectory = workspaceBuildTarget.getBasePath();
    }

    WorkspaceGenerator workspaceGenerator = new WorkspaceGenerator(
        projectFilesystem,
        workspaceName,
        outputDirectory);

    ImmutableSet<TargetNode<?>> orderedTargetNodes;
    if (workspaceArguments.srcTarget.isPresent()) {
      orderedTargetNodes = AppleBuildRules.getSchemeBuildableTargetNodes(
          projectGraph,
          Preconditions.checkNotNull(
              projectGraph.get(
                  workspaceArguments.srcTarget.get().getBuildTarget())));
    } else {
      orderedTargetNodes = ImmutableSet.of();
    }

    ImmutableSet<TargetNode<AppleTestDescription.Arg>> selectedTests = getOrderedTestNodes(
        projectGraph,
        orderedTargetNodes,
        extraTestBundleTargetNodes);
    ImmutableList<TargetNode<?>> buildForTestNodes =
        TopologicalSort.sort(
            projectGraph,
            Predicates.in(getTransitiveDepsAndInputs(selectedTests, orderedTargetNodes)));

    GroupedTestResults groupedTestResults = groupTests(selectedTests);
    Iterable<PBXTarget> synthesizedCombinedTestTargets = ImmutableList.of();

    ImmutableSet<BuildTarget> targetsInRequiredProjects = FluentIterable
        .from(orderedTargetNodes)
        .append(buildForTestNodes)
        .transform(HasBuildTarget.TO_TARGET)
        .toSet();
    ImmutableMap.Builder<BuildTarget, PBXTarget> buildTargetToPbxTargetMapBuilder =
        ImmutableMap.builder();
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder =
        ImmutableMap.builder();

    if (combinedProject) {
      LOG.debug("Generating a combined project");
      ProjectGenerator generator = new ProjectGenerator(
          projectGraph,
          targetsInRequiredProjects,
          projectFilesystem,
          outputDirectory,
          workspaceName,
          buildFileName,
          projectGeneratorOptions,
          outputPathOfNode)
          .setAdditionalCombinedTestTargets(groupedTestResults.groupedTests)
          .setTestsToGenerateAsStaticLibraries(groupableTests);
      combinedProjectGenerator = Optional.of(generator);
      generator.createXcodeProjects();

      workspaceGenerator.addFilePath(generator.getProjectPath(), Optional.<Path>absent());
      requiredBuildTargetsBuilder.addAll(generator.getRequiredBuildTargets());

      buildTargetToPbxTargetMapBuilder.putAll(generator.getBuildTargetToGeneratedTargetMap());
      for (PBXTarget target : generator.getBuildTargetToGeneratedTargetMap().values()) {
        targetToProjectPathMapBuilder.put(target, generator.getProjectPath());
      }
      synthesizedCombinedTestTargets = generator.getBuildableCombinedTestTargets();
      for (PBXTarget target : synthesizedCombinedTestTargets) {
        targetToProjectPathMapBuilder.put(target, generator.getProjectPath());
      }
    } else {
      ImmutableMultimap.Builder<Path, BuildTarget> projectDirectoryToBuildTargetsBuilder =
          ImmutableMultimap.builder();
      for (TargetNode<?> targetNode : projectGraph.getNodes()) {
        BuildTarget buildTarget = targetNode.getBuildTarget();
        projectDirectoryToBuildTargetsBuilder.put(buildTarget.getBasePath(), buildTarget);
      }
      ImmutableMultimap<Path, BuildTarget> projectDirectoryToBuildTargets =
          projectDirectoryToBuildTargetsBuilder.build();
      for (Path projectDirectory : projectDirectoryToBuildTargets.keySet()) {
        ImmutableSet<BuildTarget> rules = filterRulesForProjectDirectory(
            projectGraph,
            ImmutableSet.copyOf(projectDirectoryToBuildTargets.get(projectDirectory)));
        if (Sets.intersection(targetsInRequiredProjects, rules).isEmpty()) {
          continue;
        }

        ProjectGenerator generator = projectGenerators.get(projectDirectory);
        if (generator == null) {
          LOG.debug("Generating project for directory %s with targets %s", projectDirectory, rules);
          String projectName;
          if (projectDirectory.getNameCount() == 0) {
            // If we're generating a project in the root directory, use a generic name.
            projectName = "Project";
          } else {
            // Otherwise, name the project the same thing as the directory we're in.
            projectName = projectDirectory.getFileName().toString();
          }
          generator = new ProjectGenerator(
              projectGraph,
              rules,
              projectFilesystem,
              projectDirectory,
              projectName,
              buildFileName,
              projectGeneratorOptions,
              outputPathOfNode)
              .setTestsToGenerateAsStaticLibraries(groupableTests);

          generator.createXcodeProjects();
          requiredBuildTargetsBuilder.addAll(generator.getRequiredBuildTargets());
          projectGenerators.put(projectDirectory, generator);
        } else {
          LOG.debug("Already generated project for target %s, skipping", projectDirectory);
        }

        workspaceGenerator.addFilePath(generator.getProjectPath());

        buildTargetToPbxTargetMapBuilder.putAll(generator.getBuildTargetToGeneratedTargetMap());
        for (PBXTarget target : generator.getBuildTargetToGeneratedTargetMap().values()) {
          targetToProjectPathMapBuilder.put(target, generator.getProjectPath());
        }
      }

      if (!groupedTestResults.groupedTests.isEmpty()) {
        ProjectGenerator combinedTestsProjectGenerator = new ProjectGenerator(
            projectGraph,
            ImmutableSortedSet.<BuildTarget>of(),
            projectFilesystem,
            BuildTargets.getGenPath(workspaceBuildTarget, "%s-CombinedTestBundles"),
            "_CombinedTestBundles",
            buildFileName,
            projectGeneratorOptions,
            outputPathOfNode);
        combinedTestsProjectGenerator
            .setAdditionalCombinedTestTargets(groupedTestResults.groupedTests)
            .createXcodeProjects();
        workspaceGenerator.addFilePath(combinedTestsProjectGenerator.getProjectPath());
        requiredBuildTargetsBuilder.addAll(combinedTestsProjectGenerator.getRequiredBuildTargets());
        for (PBXTarget target :
            combinedTestsProjectGenerator.getBuildTargetToGeneratedTargetMap().values()) {
          targetToProjectPathMapBuilder.put(target, combinedTestsProjectGenerator.getProjectPath());
        }
        synthesizedCombinedTestTargets =
            combinedTestsProjectGenerator.getBuildableCombinedTestTargets();
        for (PBXTarget target : synthesizedCombinedTestTargets) {
          targetToProjectPathMapBuilder.put(target, combinedTestsProjectGenerator.getProjectPath());
        }
        this.combinedTestsProjectGenerator = Optional.of(combinedTestsProjectGenerator);
      }
    }

    Path workspacePath = workspaceGenerator.writeWorkspace();

    final Map<BuildTarget, PBXTarget> buildTargetToTarget =
        buildTargetToPbxTargetMapBuilder.build();
    Function<TargetNode<?>, PBXTarget> targetNodeToPBXTargetTransformer =
        new Function<TargetNode<?>, PBXTarget>() {
          @Override
          public PBXTarget apply(TargetNode<?> input) {
            return Preconditions.checkNotNull(buildTargetToTarget.get(input.getBuildTarget()));
          }
        };

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        workspaceArguments.srcTarget.transform(
            Functions.forMap(buildTargetToTarget)),
        Iterables.transform(orderedTargetNodes, targetNodeToPBXTargetTransformer),
        FluentIterable
            .from(buildForTestNodes)
            .transform(targetNodeToPBXTargetTransformer)
            .append(synthesizedCombinedTestTargets),
        FluentIterable
            .from(groupedTestResults.ungroupedTests)
            .transform(targetNodeToPBXTargetTransformer)
            .append(synthesizedCombinedTestTargets),
        workspaceName,
        outputDirectory.resolve(workspaceName + ".xcworkspace"),
        XcodeWorkspaceConfigDescription.getActionConfigNamesFromArg(
            workspaceArguments),
        targetToProjectPathMapBuilder.build());
    schemeGenerator.writeScheme();
    this.schemeGenerator = Optional.of(schemeGenerator);

    return workspacePath;
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
   * @param orderedTargetNodes target nodes for which to fetch tests for
   * @param extraTestBundleTargets extra tests to include
   *
   * @return test targets that should be run.
   */
  private ImmutableSet<TargetNode<AppleTestDescription.Arg>> getOrderedTestNodes(
      TargetGraph targetGraph,
      ImmutableSet<TargetNode<?>> orderedTargetNodes,
      ImmutableSet<TargetNode<AppleTestDescription.Arg>> extraTestBundleTargets) {
    LOG.debug("Getting ordered test target nodes for %s", orderedTargetNodes);
    ImmutableSet.Builder<TargetNode<AppleTestDescription.Arg>> testsBuilder =
        ImmutableSet.builder();
    if (projectGeneratorOptions.contains(ProjectGenerator.Option.INCLUDE_TESTS)) {
      for (TargetNode<?> node : orderedTargetNodes) {
        if (!(node.getConstructorArg() instanceof HasTests)) {
          continue;
        }
        for (BuildTarget explicitTestTarget : ((HasTests) node.getConstructorArg()).getTests()) {
          TargetNode<?> explicitTestNode = targetGraph.get(explicitTestTarget);
          if (explicitTestNode != null) {
            Optional<TargetNode<AppleTestDescription.Arg>> castedNode =
                explicitTestNode.castArg(AppleTestDescription.Arg.class);
            if (castedNode.isPresent()) {
              testsBuilder.add(castedNode.get());
            } else {
              throw new HumanReadableException(
                  "Test target specified in '%s' is not a test: '%s'",
                  node.getBuildTarget(),
                  explicitTestTarget);
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
    for (TargetNode<AppleTestDescription.Arg> extraTestTarget : extraTestBundleTargets) {
      testsBuilder.add(extraTestTarget);
    }
    return testsBuilder.build();
  }

  /**
   * Find transitive dependencies of inputs for building.
   *
   * @param nodes Nodes to fetch dependencies for.
   * @param excludes Nodes to exclude from dependencies list.
   * @return targets and their dependencies that should be build.
   */
  private ImmutableSet<TargetNode<?>> getTransitiveDepsAndInputs(
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
                    Optional.<ImmutableSet<BuildRuleType>>absent());
              }
            })
        .append(nodes)
        .filter(
            new Predicate<TargetNode<?>>() {
              @Override
              public boolean apply(TargetNode<?> input) {
                return !excludes.contains(input) &&
                    AppleBuildRules.isXcodeTargetBuildRuleType(input.getType());
              }
            })
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

  private GroupedTestResults groupTests(ImmutableSet<TargetNode<AppleTestDescription.Arg>> tests) {
    // Put tests in groups.
    ImmutableMultimap.Builder<AppleTestBundleParamsKey, TargetNode<AppleTestDescription.Arg>>
        groupsBuilder = ImmutableMultimap.builder();
    ImmutableSet.Builder<TargetNode<AppleTestDescription.Arg>> ungroupedTestsBuilder =
        ImmutableSet.builder();
    for (TargetNode<AppleTestDescription.Arg> test : tests) {
      if (groupableTests.contains(test)) {
        Preconditions.checkState(
            test.getConstructorArg().canGroup(),
            "Groupable test should actually be groupable.");
        groupsBuilder.put(
            AppleTestBundleParamsKey.fromAppleTestDescriptionArg(test.getConstructorArg()),
            test);
      } else {
        ungroupedTestsBuilder.add(test);
      }
    }
    return new GroupedTestResults(groupsBuilder.build(), ungroupedTestsBuilder.build());
  }

  private static class GroupedTestResults {
    public final ImmutableMultimap<AppleTestBundleParamsKey, TargetNode<AppleTestDescription.Arg>>
        groupedTests;
    public final ImmutableSet<TargetNode<AppleTestDescription.Arg>> ungroupedTests;

    protected GroupedTestResults(
        ImmutableMultimap<AppleTestBundleParamsKey, TargetNode<AppleTestDescription.Arg>>
            groupedTests,
        ImmutableSet<TargetNode<AppleTestDescription.Arg>> ungroupedTests) {
      this.groupedTests = groupedTests;
      this.ungroupedTests = ungroupedTests;
    }
  }
}
