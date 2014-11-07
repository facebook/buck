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
import com.facebook.buck.apple.AppleTest;
import com.facebook.buck.apple.XcodeProjectConfig;
import com.facebook.buck.apple.XcodeWorkspaceConfig;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
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
  private final XcodeWorkspaceConfig workspaceBuildable;
  private final ImmutableSet<ProjectGenerator.Option> projectGeneratorOptions;
  private final ImmutableMultimap<BuildRule, AppleTest> sourceRuleToTestRules;
  private final ImmutableSet<BuildRule> extraTestBundleRules;
  private final boolean combinedProject;
  private Optional<ProjectGenerator> combinedProjectGenerator;

  public WorkspaceAndProjectGenerator(
      ProjectFilesystem projectFilesystem,
      TargetGraph projectGraph,
      ExecutionContext executionContext,
      XcodeWorkspaceConfig workspaceBuildable,
      Set<ProjectGenerator.Option> projectGeneratorOptions,
      Multimap<BuildRule, AppleTest> sourceRuleToTestRules,
      Iterable<BuildRule> extraTestBundleRules,
      boolean combinedProject) {
    this.projectFilesystem = projectFilesystem;
    this.projectGraph = projectGraph;
    this.executionContext = executionContext;
    this.workspaceBuildable = workspaceBuildable;
    this.projectGeneratorOptions = ImmutableSet.copyOf(projectGeneratorOptions);
    this.sourceRuleToTestRules = ImmutableMultimap.copyOf(sourceRuleToTestRules);
    this.extraTestBundleRules = ImmutableSet.copyOf(extraTestBundleRules);
    this.combinedProject = combinedProject;
    this.combinedProjectGenerator = Optional.absent();
  }

  @VisibleForTesting
  Optional<ProjectGenerator> getCombinedProjectGenerator() {
    return combinedProjectGenerator;
  }

  public Path generateWorkspaceAndDependentProjects(
        Map<BuildRule, ProjectGenerator> projectGenerators)
      throws IOException {
    LOG.debug("Generating workspace for rule %s", workspaceBuildable);

    String workspaceName;
    Path outputDirectory;

    if (combinedProject) {
      workspaceName = "GeneratedProject";
      outputDirectory = Paths.get("_gen");
    } else {
      workspaceName = workspaceBuildable.getWorkspaceName();
      outputDirectory = workspaceBuildable.getBuildTarget().getBasePath();
    }

    WorkspaceGenerator workspaceGenerator = new WorkspaceGenerator(
        projectFilesystem,
        workspaceName,
        outputDirectory);

    ImmutableSet<BuildRule> orderedBuildRules;
    if (workspaceBuildable.getSrcTarget().isPresent()) {
      final ActionGraph actionGraph = projectGraph.getActionGraph(
          executionContext.getBuckEventBus());
      orderedBuildRules = FluentIterable
          .from(AppleBuildRules.getSchemeBuildableTargetNodes(
                  projectGraph,
                  projectGraph.get(workspaceBuildable.getSrcTarget().get().getBuildTarget())))
          .transform(
              new Function<TargetNode<?>, BuildRule>() {
                @Override
                public BuildRule apply(TargetNode<?> input) {
                  return Preconditions.checkNotNull(
                      actionGraph.findBuildRuleByTarget(input.getBuildTarget()));
                }
              })
          .toSet();
    } else {
      orderedBuildRules = ImmutableSet.of();
    }

    ImmutableSet<BuildRule> orderedTestBuildRules;
    ImmutableSet<BuildRule> orderedTestBundleRules;
    {
      ImmutableSet.Builder<BuildRule> orderedTestBuildRulesBuilder = ImmutableSet.builder();
      ImmutableSet.Builder<BuildRule> orderedTestBundleRulesBuilder = ImmutableSet.builder();

      getOrderedTestRules(
          projectGraph.getActionGraph(executionContext.getBuckEventBus()),
          sourceRuleToTestRules,
          orderedBuildRules,
          extraTestBundleRules,
          orderedTestBuildRulesBuilder,
          orderedTestBundleRulesBuilder);

      orderedTestBuildRules = orderedTestBuildRulesBuilder.build();
      orderedTestBundleRules = orderedTestBundleRulesBuilder.build();
    }

    Sets.SetView<BuildRule> rulesInRequiredProjects =
        Sets.union(orderedBuildRules, orderedTestBuildRules);
    ImmutableMap.Builder<BuildTarget, PBXTarget> buildTargetToPbxTargetMapBuilder =
        ImmutableMap.builder();
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder =
        ImmutableMap.builder();

    if (combinedProject) {
      ImmutableSet.Builder<BuildTarget> initialTargetsBuilder = ImmutableSet.builder();
      for (XcodeProjectConfig xcodeProjectConfig : Iterables.filter(
          projectGraph.getActionGraph(executionContext.getBuckEventBus()).getNodes(),
          XcodeProjectConfig.class)) {
        if (Sets.intersection(rulesInRequiredProjects, xcodeProjectConfig.getRules()).isEmpty()) {
          continue;
        }
        initialTargetsBuilder.addAll(
            Iterables.transform(
                xcodeProjectConfig.getRules(),
                HasBuildTarget.TO_TARGET));
      }

      LOG.debug("Generating a combined project");
      ProjectGenerator generator = new ProjectGenerator(
          projectGraph,
          initialTargetsBuilder.build(),
          projectFilesystem,
          executionContext,
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
      for (XcodeProjectConfig xcodeProjectConfig : Iterables.filter(
          projectGraph.getActionGraph(executionContext.getBuckEventBus()).getNodes(),
          XcodeProjectConfig.class)) {
        if (Sets.intersection(rulesInRequiredProjects, xcodeProjectConfig.getRules()).isEmpty()) {
          continue;
        }

        ImmutableSet.Builder<BuildTarget> initialTargetsBuilder = ImmutableSet.builder();
        for (BuildRule memberRule : xcodeProjectConfig.getRules()) {
          initialTargetsBuilder.add(memberRule.getBuildTarget());
        }
        Set<BuildTarget> initialTargets = initialTargetsBuilder.build();

        ProjectGenerator generator = projectGenerators.get(xcodeProjectConfig);
        if (generator == null) {
          LOG.debug("Generating project for rule %s", xcodeProjectConfig);
          generator = new ProjectGenerator(
              projectGraph,
              initialTargets,
              projectFilesystem,
              executionContext,
              xcodeProjectConfig.getBuildTarget().getBasePath(),
              xcodeProjectConfig.getProjectName(),
              projectGeneratorOptions);
          generator.createXcodeProjects();
          projectGenerators.put(xcodeProjectConfig, generator);
        } else {
          LOG.debug("Already generated project for rule %s, skipping", xcodeProjectConfig);
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
        workspaceBuildable.getSrcTarget().transform(HasBuildTarget.TO_TARGET),
        Iterables.transform(
            orderedBuildRules,
            HasBuildTarget.TO_TARGET),
        Iterables.transform(
            orderedTestBuildRules,
            HasBuildTarget.TO_TARGET),
        Iterables.transform(
            orderedTestBundleRules,
            HasBuildTarget.TO_TARGET),
        workspaceName,
        outputDirectory.resolve(workspaceName + ".xcworkspace"),
        workspaceBuildable.getActionConfigNames(),
        buildTargetToPbxTargetMapBuilder.build(),
        targetToProjectPathMapBuilder.build());
    schemeGenerator.writeScheme();

    return workspacePath;
  }

  private void getOrderedTestRules(
      ActionGraph actionGraph,
      ImmutableMultimap<BuildRule, AppleTest> sourceRuleToTestRules,
      ImmutableSet<BuildRule> orderedBuildRules,
      ImmutableSet<BuildRule> extraTestBundleRules,
      ImmutableSet.Builder<BuildRule> orderedTestBuildRulesBuilder,
      ImmutableSet.Builder<BuildRule> orderedTestBundleRulesBuilder) {
    LOG.debug("Getting ordered test rules, build rules %s", orderedBuildRules);
    final ImmutableSet.Builder<BuildRule> recursiveTestRulesBuilder = ImmutableSet.builder();
    if (!sourceRuleToTestRules.isEmpty()) {
      for (BuildRule rule : orderedBuildRules) {
        LOG.verbose("Checking if rule %s has any tests covering it..", rule);
        for (AppleTest testRule : sourceRuleToTestRules.get(rule)) {
          addTestRuleAndDependencies(
              testRule.getTestBundle(),
              recursiveTestRulesBuilder,
              orderedTestBundleRulesBuilder);
        }
      }
    }

    for (BuildRule testBundleRule : extraTestBundleRules) {
      if (!AppleBuildRules.isXcodeTargetTestBundleBuildRule(testBundleRule)) {
        throw new HumanReadableException(
            "Test rule %s must be apple_bundle with a test extension!",
            testBundleRule);
      }
      addTestRuleAndDependencies(
          testBundleRule,
          recursiveTestRulesBuilder,
          orderedTestBundleRulesBuilder);
    }

    final Set<BuildRule> includedTestRules =
        Sets.difference(recursiveTestRulesBuilder.build(), orderedBuildRules);

    orderedTestBuildRulesBuilder.addAll(TopologicalSort.sort(
        actionGraph,
        new Predicate<BuildRule>() {
          @Override
          public boolean apply(BuildRule input) {
            if (!includedTestRules.contains(input) ||
                !AppleBuildRules.isXcodeTargetBuildRuleType(input.getType())) {
              return false;
            }

            return true;
          }
        }));
  }

  private void addTestRuleAndDependencies(
      BuildRule testBundleRule,
      ImmutableSet.Builder<BuildRule> recursiveTestRulesBuilder,
      ImmutableSet.Builder<BuildRule> orderedTestBundleRulesBuilder) {
    final ActionGraph actionGraph = projectGraph.getActionGraph(executionContext.getBuckEventBus());
    Iterable<BuildRule> testBundleRuleDependencies = FluentIterable
        .from(AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                projectGraph,
                AppleBuildRules.RecursiveDependenciesMode.BUILDING,
                projectGraph.get(testBundleRule.getBuildTarget()),
                Optional.<ImmutableSet<BuildRuleType>>absent()))
        .transform(
            new Function<TargetNode<?>, BuildRule>() {
              @Override
              public BuildRule apply(TargetNode<?> input) {
                return Preconditions.checkNotNull(
                    actionGraph.findBuildRuleByTarget(input.getBuildTarget()));
              }
            })
        .toSet();
    recursiveTestRulesBuilder.addAll(testBundleRuleDependencies);
    recursiveTestRulesBuilder.add(testBundleRule);
    orderedTestBundleRulesBuilder.add(testBundleRule);
  }
}
