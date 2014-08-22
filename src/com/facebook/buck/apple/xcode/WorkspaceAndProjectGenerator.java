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

import com.dd.plist.NSDictionary;
import com.facebook.buck.apple.AppleTest;
import com.facebook.buck.apple.AppleBuildRules;
import com.facebook.buck.apple.XcodeNative;
import com.facebook.buck.apple.XcodeNativeDescription;
import com.facebook.buck.apple.XcodeProjectConfig;
import com.facebook.buck.apple.XcodeWorkspaceConfig;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

public class WorkspaceAndProjectGenerator {
  private static final Logger LOG = Logger.get(WorkspaceAndProjectGenerator.class);

  private final ProjectFilesystem projectFilesystem;
  private final PartialGraph mainTargetGraph;
  private final Optional<PartialGraph> testTargetGraph;
  private final PartialGraph projectTargetGraph;
  private final ExecutionContext executionContext;
  private final BuildTarget workspaceConfigTarget;
  private final ImmutableSet<ProjectGenerator.Option> projectGeneratorOptions;

  public WorkspaceAndProjectGenerator(
      ProjectFilesystem projectFilesystem,
      PartialGraph mainTargetGraph,
      Optional<PartialGraph> testTargetGraph,
      PartialGraph projectTargetGraph,
      ExecutionContext executionContext,
      BuildTarget workspaceConfigTarget,
      Set<ProjectGenerator.Option> projectGeneratorOptions) {
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.mainTargetGraph = Preconditions.checkNotNull(mainTargetGraph);
    this.testTargetGraph = Preconditions.checkNotNull(testTargetGraph);
    this.projectTargetGraph = Preconditions.checkNotNull(projectTargetGraph);
    this.executionContext = Preconditions.checkNotNull(executionContext);
    this.workspaceConfigTarget = workspaceConfigTarget;
    this.projectGeneratorOptions = ImmutableSet.<ProjectGenerator.Option>builder()
      .addAll(projectGeneratorOptions)
      .addAll(ProjectGenerator.SEPARATED_PROJECT_OPTIONS)
      .build();
  }

  public Path generateWorkspaceAndDependentProjects(
        Map<BuildRule, ProjectGenerator> projectGenerators)
      throws IOException {
    BuildRule workspaceTargetRule =
        mainTargetGraph.getActionGraph().findBuildRuleByTarget(workspaceConfigTarget);

    if (!(workspaceTargetRule instanceof XcodeWorkspaceConfig)) {
      throw new HumanReadableException("%s must be a xcode_workspace_config",
          workspaceTargetRule.getFullyQualifiedName());
    }

    LOG.debug("Generating workspace for config target %s", workspaceConfigTarget);
    XcodeWorkspaceConfig workspaceBuildable =
        (XcodeWorkspaceConfig) workspaceTargetRule;

    String workspaceName = workspaceBuildable.getSrcTarget().getBuildTarget().getShortName();

    Path outputDirectory = workspaceBuildable.getBuildTarget().getBasePath();

    WorkspaceGenerator workspaceGenerator = new WorkspaceGenerator(
        projectFilesystem,
        workspaceName,
        outputDirectory);

    ImmutableSet<BuildRule> orderedBuildRules =
      AppleBuildRules.getSchemeBuildableRules(workspaceBuildable.getSrcTarget());
    ImmutableSet.Builder<BuildRule> orderedTestBuildRulesBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<BuildRule> orderedTestBundleRulesBuilder = ImmutableSet.builder();

    getOrderedTestRules(
        projectTargetGraph.getActionGraph(),
        testTargetGraph,
        orderedBuildRules,
        orderedTestBuildRulesBuilder,
        orderedTestBundleRulesBuilder);

    ImmutableSet<BuildRule> orderedTestBuildRules = orderedTestBuildRulesBuilder.build();

    Multimap<Path, BuildRule> buildRulesByTargetBasePath =
      BuildRules.buildRulesByTargetBasePath(
          Iterables.concat(orderedBuildRules, orderedTestBuildRules));

    ImmutableMap.Builder<BuildRule, PBXTarget> buildRuleToTargetMapBuilder =
      ImmutableMap.builder();
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder =
      ImmutableMap.builder();

    for (Path basePath : buildRulesByTargetBasePath.keySet()) {
      // From each target we find that package's xcode_project_config rule and generate it if
      // it hasn't already been generated.
      Optional<BuildRule> xcodeProjectConfigRule = Optional.fromNullable(Iterables.getOnlyElement(
              projectTargetGraph.getActionGraph().getBuildRulesOfBuildableTypeInBasePath(
                  XcodeProjectConfig.class, basePath), null));

      ProjectGenerator generator;
      if (xcodeProjectConfigRule.isPresent()) {
        XcodeProjectConfig xcodeProjectConfig = (XcodeProjectConfig) xcodeProjectConfigRule.get();

        ImmutableSet.Builder<BuildTarget> initialTargetsBuilder = ImmutableSet.builder();
        for (BuildRule memberRule : xcodeProjectConfig.getRules()) {
          initialTargetsBuilder.add(memberRule.getBuildTarget());
        }
        Set<BuildTarget> initialTargets = initialTargetsBuilder.build();

        generator = projectGenerators.get(xcodeProjectConfig);
        if (generator == null) {
          LOG.debug("Generating project for rule %s", xcodeProjectConfig);
          generator = new ProjectGenerator(
              projectTargetGraph.getActionGraph().getNodes(),
              initialTargets,
              projectFilesystem,
              executionContext,
              basePath,
              xcodeProjectConfig.getProjectName(),
              projectGeneratorOptions);
          generator.createXcodeProjects();
          projectGenerators.put(xcodeProjectConfig, generator);
        } else {
          LOG.debug("Already generated project for rule %s, skipping", xcodeProjectConfig);
        }

        workspaceGenerator.addFilePath(generator.getProjectPath());

        buildRuleToTargetMapBuilder.putAll(generator.getBuildRuleToGeneratedTargetMap());
        for (PBXTarget target : generator.getBuildRuleToGeneratedTargetMap().values()) {
          targetToProjectPathMapBuilder.put(target, generator.getProjectPath());
        }
      }

      Set<BuildRule> xcodeNativeProjectRules = Sets.newHashSet(Collections2.filter(
          buildRulesByTargetBasePath.get(basePath), new Predicate<BuildRule>() {
            @Override
            public boolean apply(BuildRule rule) {
              return rule.getType() == XcodeNativeDescription.TYPE;
            }
          }));

      for (BuildRule rule : xcodeNativeProjectRules) {
        XcodeNative buildable = (XcodeNative) rule;
        Path projectPath = buildable.getProjectContainerPath().resolve();
        Path pbxprojectPath = projectPath.resolve("project.pbxproj");
        String targetName = buildable.getTargetName();

        workspaceGenerator.addFilePath(projectPath);

        ImmutableMap.Builder<String, String> targetNameToGIDMapBuilder = ImmutableMap.builder();
        ImmutableMap.Builder<String, String> targetNameToFileNameBuilder = ImmutableMap.builder();
        try (InputStream projectInputStream =
            projectFilesystem.newFileInputStream(pbxprojectPath)) {
          NSDictionary projectObjects =
              ProjectParser.extractObjectsFromXcodeProject(projectInputStream);
          ProjectParser.extractTargetNameToGIDAndFileNameMaps(
              projectObjects,
              targetNameToGIDMapBuilder,
              targetNameToFileNameBuilder);
          Map<String, String> targetNameToGIDMap = targetNameToGIDMapBuilder.build();
          String targetGid = targetNameToGIDMap.get(targetName);

          Map<String, String> targetNameToFileNameMap = targetNameToFileNameBuilder.build();
          String targetFileName = targetNameToFileNameMap.get(targetName);

          if (targetGid == null || targetFileName == null) {
            LOG.error(
                "Looked up target %s, could not find GID (%s) or filename (%s)",
                targetName,
                targetGid,
                targetFileName);
            throw new HumanReadableException(
                "xcode_native target %s not found in Xcode project %s",
                targetName,
                pbxprojectPath);
          }

          PBXTarget fakeTarget = new PBXNativeTarget(targetName);
          fakeTarget.setGlobalID(targetGid);
          PBXFileReference fakeProductReference = new PBXFileReference(
              targetFileName,
              targetFileName,
              PBXFileReference.SourceTree.BUILT_PRODUCTS_DIR);
          fakeTarget.setProductReference(fakeProductReference);
          buildRuleToTargetMapBuilder.put(rule, fakeTarget);
          targetToProjectPathMapBuilder.put(fakeTarget, projectPath);
        }
      }
    }
    Path workspacePath = workspaceGenerator.writeWorkspace();

    ImmutableSet<BuildRule> orderedTestBundleRules = orderedTestBundleRulesBuilder.build();
    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        workspaceBuildable.getSrcTarget(),
        orderedBuildRules,
        orderedTestBuildRules,
        orderedTestBundleRules,
        workspaceName,
        outputDirectory.resolve(workspaceName + ".xcworkspace"),
        workspaceBuildable.getActionConfigNames(),
        buildRuleToTargetMapBuilder.build(),
        targetToProjectPathMapBuilder.build());
    schemeGenerator.writeScheme();

    return workspacePath;
  }

  private static final void getOrderedTestRules(
      ActionGraph actionGraph,
      Optional<PartialGraph> testTargetGraph,
      final ImmutableSet<BuildRule> orderedBuildRules,
      final ImmutableSet.Builder<BuildRule> orderedTestBuildRulesBuilder,
      final ImmutableSet.Builder<BuildRule> orderedTestBundleRulesBuilder) {
    ImmutableSet.Builder<BuildRule> xcodeTargetTestRulesBuilder = ImmutableSet.builder();
    if (testTargetGraph.isPresent()) {
      xcodeTargetTestRulesBuilder.addAll(
          TopologicalSort.sort(
              testTargetGraph.get().getActionGraph(),
              new Predicate<BuildRule>() {
                @Override
                public boolean apply(@Nullable BuildRule input) {
                  return AppleBuildRules.isXcodeTargetTestBuildRule(input);
                }
              }));
    }
    final ImmutableSet<BuildRule> xcodeTargetTestRules = xcodeTargetTestRulesBuilder.build();

    for (BuildRule buildRule : xcodeTargetTestRules) {
      AppleTest testRule = (AppleTest) buildRule;
      orderedTestBundleRulesBuilder.add(testRule.getTestBundle());
    }

    ImmutableSet.Builder<BuildRule> recursiveTestRulesBuilder = ImmutableSet.builder();
    for (BuildRule testRule : xcodeTargetTestRules) {
      Iterable<BuildRule> testRulesIterable = Iterables.concat(
          AppleBuildRules.getRecursiveRuleDependenciesOfTypes(
              AppleBuildRules.RecursiveRuleDependenciesMode.BUILDING,
              testRule,
              Optional.<ImmutableSet<BuildRuleType>>absent()),
          ImmutableSet.of(testRule));

      recursiveTestRulesBuilder.addAll(testRulesIterable);
    }

    final Set<BuildRule> includedTestRules =
        Sets.difference(recursiveTestRulesBuilder.build(), orderedBuildRules);

    orderedTestBuildRulesBuilder.addAll(TopologicalSort.sort(
        actionGraph,
        new Predicate<BuildRule>() {
          @Override
          public boolean apply(@Nullable BuildRule input) {
            if (!includedTestRules.contains(input) ||
                !AppleBuildRules.isXcodeTargetBuildRuleType(input.getType())) {
              return false;
            }

            return true;
          }
        }));
  }
}
