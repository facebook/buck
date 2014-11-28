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

package com.facebook.buck.apple.xcode;

import com.facebook.buck.apple.AppleBuildRules;
import com.facebook.buck.apple.AppleTestDescription;
import com.facebook.buck.apple.XcodeProjectConfigDescription;
import com.facebook.buck.apple.XcodeWorkspaceConfigDescription;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

public class WorkspaceAndProjectGenerator {
  private static final Logger LOG = Logger.get(WorkspaceAndProjectGenerator.class);

  private final ProjectFilesystem projectFilesystem;
  private final TargetGraph projectGraph;
  private final ExecutionContext executionContext;
  private final BuildRuleResolver buildRuleResolver;
  private final SourcePathResolver sourcePathResolver;
  private final TargetNode<XcodeWorkspaceConfigDescription.Arg> workspaceTargetNode;
  private final ImmutableSet<ProjectGenerator.Option> projectGeneratorOptions;
  private final ImmutableMultimap<BuildTarget, TargetNode<?>> sourceTargetToTestNodes;
  private final ImmutableSet<TargetNode<?>> extraTestBundleTargetNodes;
  private final boolean combinedProject;
  private Optional<ProjectGenerator> combinedProjectGenerator;

  public WorkspaceAndProjectGenerator(
      ProjectFilesystem projectFilesystem,
      TargetGraph projectGraph,
      ExecutionContext executionContext,
      BuildRuleResolver buildRuleResolver,
      SourcePathResolver sourcePathResolver,
      TargetNode<XcodeWorkspaceConfigDescription.Arg> workspaceTargetNode,
      Set<ProjectGenerator.Option> projectGeneratorOptions,
      Multimap<BuildTarget, TargetNode<?>> sourceTargetToTestNodes,
      boolean combinedProject) {
    this.projectFilesystem = projectFilesystem;
    this.projectGraph = projectGraph;
    this.executionContext = executionContext;
    this.buildRuleResolver = buildRuleResolver;
    this.sourcePathResolver = sourcePathResolver;
    this.workspaceTargetNode = workspaceTargetNode;
    this.projectGeneratorOptions = ImmutableSet.copyOf(projectGeneratorOptions);
    this.sourceTargetToTestNodes = ImmutableMultimap.copyOf(sourceTargetToTestNodes);
    this.combinedProject = combinedProject;
    this.combinedProjectGenerator = Optional.absent();
    extraTestBundleTargetNodes = ImmutableSet.copyOf(
        projectGraph.getAll(
            workspaceTargetNode.getConstructorArg().extraTests.get()));
  }

  @VisibleForTesting
  Optional<ProjectGenerator> getCombinedProjectGenerator() {
    return combinedProjectGenerator;
  }

  public Path generateWorkspaceAndDependentProjects(
        Map<TargetNode<?>, ProjectGenerator> projectGenerators)
      throws IOException {
    LOG.debug("Generating workspace for target %s", workspaceTargetNode);

    String workspaceName;
    Path outputDirectory;

    if (combinedProject) {
      workspaceName = "GeneratedProject";
      outputDirectory = Paths.get("_gen");
    } else {
      workspaceName = XcodeWorkspaceConfigDescription.getWorkspaceNameFromArg(
          workspaceTargetNode.getConstructorArg());
      outputDirectory = workspaceTargetNode.getBuildTarget().getBasePath();
    }

    WorkspaceGenerator workspaceGenerator = new WorkspaceGenerator(
        projectFilesystem,
        workspaceName,
        outputDirectory);

    ImmutableSet<TargetNode<?>> orderedTargetNodes;
    if (workspaceTargetNode.getConstructorArg().srcTarget.isPresent()) {
      orderedTargetNodes = AppleBuildRules.getSchemeBuildableTargetNodes(
          projectGraph,
          projectGraph.get(
              workspaceTargetNode.getConstructorArg().srcTarget.get().getBuildTarget()));
    } else {
      orderedTargetNodes = ImmutableSet.of();
    }

    ImmutableSet<TargetNode<?>> orderedTestTargetNodes;
    ImmutableSet<TargetNode<?>> orderedTestBundleTargetNodes;
    {
      ImmutableSet.Builder<TargetNode<?>> orderedTestTargetNodesBuilder =
          ImmutableSet.builder();
      ImmutableSet.Builder<TargetNode<?>> orderedTestBundleTargetNodesBuilder =
          ImmutableSet.builder();

      getOrderedTestNodes(
          projectGraph,
          sourceTargetToTestNodes,
          orderedTargetNodes,
          extraTestBundleTargetNodes,
          orderedTestTargetNodesBuilder,
          orderedTestBundleTargetNodesBuilder);

      orderedTestTargetNodes = orderedTestTargetNodesBuilder.build();
      orderedTestBundleTargetNodes = orderedTestBundleTargetNodesBuilder.build();
    }

    ImmutableSet<BuildTarget> targetsInRequiredProjects = FluentIterable
        .from(Sets.union(orderedTargetNodes, orderedTestTargetNodes))
        .transform(HasBuildTarget.TO_TARGET)
        .toSet();
    ImmutableMap.Builder<BuildTarget, PBXTarget> buildTargetToPbxTargetMapBuilder =
        ImmutableMap.builder();
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder =
        ImmutableMap.builder();

    if (combinedProject) {
      ImmutableSet.Builder<BuildTarget> initialTargetsBuilder = ImmutableSet.builder();
      for (TargetNode<?> targetNode : projectGraph.getNodes()) {
        if (targetNode.getType() != XcodeProjectConfigDescription.TYPE) {
          continue;
        }
        XcodeProjectConfigDescription.Arg projectArg =
            (XcodeProjectConfigDescription.Arg) targetNode.getConstructorArg();
        if (Sets.intersection(targetsInRequiredProjects, projectArg.rules).isEmpty()) {
          continue;
        }
        initialTargetsBuilder.addAll(projectArg.rules);
      }

      LOG.debug("Generating a combined project");
      ProjectGenerator generator = new ProjectGenerator(
          projectGraph,
          initialTargetsBuilder.build(),
          projectFilesystem,
          executionContext,
          buildRuleResolver,
          sourcePathResolver,
          outputDirectory,
          "GeneratedProject",
          projectGeneratorOptions);
      combinedProjectGenerator = Optional.of(generator);
      generator.createXcodeProjects();

      workspaceGenerator.addFilePath(generator.getProjectPath());

      buildTargetToPbxTargetMapBuilder.putAll(generator.getBuildTargetToGeneratedTargetMap());
      for (PBXTarget target : generator.getBuildTargetToGeneratedTargetMap().values()) {
        targetToProjectPathMapBuilder.put(target, generator.getProjectPath());
      }
    } else {
      for (TargetNode<?> targetNode : projectGraph.getNodes()) {
        if (targetNode.getType() != XcodeProjectConfigDescription.TYPE) {
          continue;
        }
        XcodeProjectConfigDescription.Arg projectArg =
            (XcodeProjectConfigDescription.Arg) targetNode.getConstructorArg();
        if (Sets.intersection(targetsInRequiredProjects, projectArg.rules).isEmpty()) {
          continue;
        }

        ProjectGenerator generator = projectGenerators.get(targetNode);
        if (generator == null) {
          LOG.debug("Generating project for target %s", targetNode);
          generator = new ProjectGenerator(
              projectGraph,
              projectArg.rules,
              projectFilesystem,
              executionContext,
              buildRuleResolver,
              sourcePathResolver,
              targetNode.getBuildTarget().getBasePath(),
              projectArg.projectName,
              projectGeneratorOptions);
          generator.createXcodeProjects();
          projectGenerators.put(targetNode, generator);
        } else {
          LOG.debug("Already generated project for target %s, skipping", targetNode);
        }

        workspaceGenerator.addFilePath(generator.getProjectPath());

        buildTargetToPbxTargetMapBuilder.putAll(generator.getBuildTargetToGeneratedTargetMap());
        for (PBXTarget target : generator.getBuildTargetToGeneratedTargetMap().values()) {
          targetToProjectPathMapBuilder.put(target, generator.getProjectPath());
        }
      }
    }

    Path workspacePath = workspaceGenerator.writeWorkspace();

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        workspaceTargetNode.getConstructorArg().srcTarget,
        Iterables.transform(
            orderedTargetNodes,
            HasBuildTarget.TO_TARGET),
        Iterables.transform(
            orderedTestTargetNodes,
            HasBuildTarget.TO_TARGET),
        Iterables.transform(
            orderedTestBundleTargetNodes,
            HasBuildTarget.TO_TARGET),
        workspaceName,
        outputDirectory.resolve(workspaceName + ".xcworkspace"),
        XcodeWorkspaceConfigDescription.getActionConfigNamesFromArg(
            workspaceTargetNode.getConstructorArg()),
        buildTargetToPbxTargetMapBuilder.build(),
        targetToProjectPathMapBuilder.build());
    schemeGenerator.writeScheme();

    return workspacePath;
  }

  private void getOrderedTestNodes(
      TargetGraph targetGraph,
      ImmutableMultimap<BuildTarget, TargetNode<?>> sourceTargetToTestNodes,
      ImmutableSet<TargetNode<?>> orderedTargetNodes,
      ImmutableSet<TargetNode<?>> extraTestBundleTargets,
      ImmutableSet.Builder<TargetNode<?>> orderedTestTargetNodeBuilder,
      ImmutableSet.Builder<TargetNode<?>> orderedTestBundleTargetNodeBuilder) {
    LOG.debug("Getting ordered test target nodes for %s", orderedTargetNodes);
    final ImmutableSet.Builder<TargetNode<?>> recursiveTestTargetNodesBuilder =
        ImmutableSet.builder();
    if (!sourceTargetToTestNodes.isEmpty()) {
      for (TargetNode<?> node : orderedTargetNodes) {
        LOG.verbose("Checking if target %s has any tests covering it..", node);
        for (TargetNode<?> testNode : sourceTargetToTestNodes.get(node.getBuildTarget())) {
          AppleTestDescription.Arg testConstructorArg =
              (AppleTestDescription.Arg) testNode.getConstructorArg();
          addTestNodeAndDependencies(
              Preconditions.checkNotNull(targetGraph.get(testConstructorArg.testBundle)),
              recursiveTestTargetNodesBuilder,
              orderedTestBundleTargetNodeBuilder);
        }
      }
    }

    for (TargetNode<?> testBundleTarget : extraTestBundleTargets) {
      if (!AppleBuildRules.isXcodeTargetTestBundleTargetNode(testBundleTarget)) {
        throw new HumanReadableException(
            "Test target %s must be apple_bundle with a test extension!",
            testBundleTarget);
      }
      addTestNodeAndDependencies(
          testBundleTarget,
          recursiveTestTargetNodesBuilder,
          orderedTestBundleTargetNodeBuilder);
    }

    final Set<TargetNode<?>> includedTestNodes =
        Sets.difference(recursiveTestTargetNodesBuilder.build(), orderedTargetNodes);

    orderedTestTargetNodeBuilder.addAll(
        TopologicalSort.sort(
            targetGraph,
            new Predicate<TargetNode<?>>() {
              @Override
              public boolean apply(TargetNode<?> input) {
                return includedTestNodes.contains(input) &&
                    AppleBuildRules.isXcodeTargetBuildRuleType(input.getType());
              }
            }));
  }

  private void addTestNodeAndDependencies(
      TargetNode<?> testBundleBuildTarget,
      ImmutableSet.Builder<TargetNode<?>> recursiveTestTargetNodesBuilder,
      ImmutableSet.Builder<TargetNode<?>> orderedTestBundleTargetNodesBuilder) {
    Iterable<TargetNode<?>> testBundleTargetDependencies =
        AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
            projectGraph,
            AppleBuildRules.RecursiveDependenciesMode.BUILDING,
            projectGraph.get(testBundleBuildTarget.getBuildTarget()),
            Optional.<ImmutableSet<BuildRuleType>>absent());
    recursiveTestTargetNodesBuilder.addAll(testBundleTargetDependencies);
    recursiveTestTargetNodesBuilder.add(testBundleBuildTarget);
    orderedTestBundleTargetNodesBuilder.add(testBundleBuildTarget);
  }
}
