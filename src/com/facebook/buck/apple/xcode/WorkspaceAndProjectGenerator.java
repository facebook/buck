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
import com.facebook.buck.apple.AppleBuildRules;
import com.facebook.buck.apple.AppleTest;
import com.facebook.buck.apple.XcodeNative;
import com.facebook.buck.apple.XcodeProjectConfig;
import com.facebook.buck.apple.XcodeWorkspaceConfig;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
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
  private final ActionGraph projectGraph;
  private final ExecutionContext executionContext;
  private final XcodeWorkspaceConfig workspaceBuildable;
  private final ImmutableSet<ProjectGenerator.Option> projectGeneratorOptions;
  private final ImmutableMultimap<BuildRule, AppleTest> sourceRuleToTestRules;
  private final ImmutableSet<BuildRule> extraTestBundleRules;

  public WorkspaceAndProjectGenerator(
      ProjectFilesystem projectFilesystem,
      ActionGraph projectGraph,
      ExecutionContext executionContext,
      XcodeWorkspaceConfig workspaceBuildable,
      Set<ProjectGenerator.Option> projectGeneratorOptions,
      Multimap<BuildRule, AppleTest> sourceRuleToTestRules,
      Iterable<BuildRule> extraTestBundleRules) {
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.projectGraph = Preconditions.checkNotNull(projectGraph);
    this.executionContext = Preconditions.checkNotNull(executionContext);
    this.workspaceBuildable = Preconditions.checkNotNull(workspaceBuildable);
    this.projectGeneratorOptions = ImmutableSet.<ProjectGenerator.Option>builder()
      .addAll(projectGeneratorOptions)
      .addAll(ProjectGenerator.SEPARATED_PROJECT_OPTIONS)
      .build();
    this.sourceRuleToTestRules = ImmutableMultimap.copyOf(sourceRuleToTestRules);
    this.extraTestBundleRules = ImmutableSet.copyOf(extraTestBundleRules);
  }

  public Path generateWorkspaceAndDependentProjects(
        Map<BuildRule, ProjectGenerator> projectGenerators)
      throws IOException {
    LOG.debug("Generating workspace for rule %s", workspaceBuildable);

    String workspaceName = workspaceBuildable.getWorkspaceName();

    Path outputDirectory = workspaceBuildable.getBuildTarget().getBasePath();

    WorkspaceGenerator workspaceGenerator = new WorkspaceGenerator(
        projectFilesystem,
        workspaceName,
        outputDirectory);

    ImmutableSet<BuildRule> orderedBuildRules;
    if (workspaceBuildable.getSrcTarget().isPresent()) {
      orderedBuildRules = AppleBuildRules.getSchemeBuildableRules(
          workspaceBuildable.getSrcTarget().get());
    } else {
      orderedBuildRules = ImmutableSet.of();
    }

    ImmutableSet<BuildRule> orderedTestBuildRules;
    ImmutableSet<BuildRule> orderedTestBundleRules;
    {
      ImmutableSet.Builder<BuildRule> orderedTestBuildRulesBuilder = ImmutableSet.builder();
      ImmutableSet.Builder<BuildRule> orderedTestBundleRulesBuilder = ImmutableSet.builder();

      getOrderedTestRules(
          projectGraph,
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
    ImmutableMap.Builder<BuildRule, PBXTarget> buildRuleToTargetMapBuilder =
        ImmutableMap.builder();
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder =
        ImmutableMap.builder();

    for (XcodeProjectConfig xcodeProjectConfig : Iterables.filter(
        projectGraph.getNodes(),
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
            projectGraph.getNodes(),
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

      buildRuleToTargetMapBuilder.putAll(generator.getBuildRuleToGeneratedTargetMap());
      for (PBXTarget target : generator.getBuildRuleToGeneratedTargetMap().values()) {
        targetToProjectPathMapBuilder.put(target, generator.getProjectPath());
      }
    }

    for (XcodeNative buildable : Iterables.filter(
        projectGraph.getNodes(),
        XcodeNative.class)) {
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

        PBXTarget fakeTarget =
            new PBXNativeTarget(targetName, PBXTarget.ProductType.STATIC_LIBRARY);
        fakeTarget.setGlobalID(targetGid);
        PBXFileReference fakeProductReference = new PBXFileReference(
            targetFileName,
            targetFileName,
            PBXFileReference.SourceTree.BUILT_PRODUCTS_DIR);
        fakeTarget.setProductReference(fakeProductReference);
        buildRuleToTargetMapBuilder.put(buildable, fakeTarget);
        targetToProjectPathMapBuilder.put(fakeTarget, projectPath);
      }
    }

    Path workspacePath = workspaceGenerator.writeWorkspace();

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
          public boolean apply(@Nullable BuildRule input) {
            if (!includedTestRules.contains(input) ||
                !AppleBuildRules.isXcodeTargetBuildRuleType(input.getType())) {
              return false;
            }

            return true;
          }
        }));
  }

  private static final void addTestRuleAndDependencies(
      BuildRule testBundleRule,
      ImmutableSet.Builder<BuildRule> recursiveTestRulesBuilder,
      ImmutableSet.Builder<BuildRule> orderedTestBundleRulesBuilder) {
    Iterable<BuildRule> testBundleRuleDependencies =
      AppleBuildRules.getRecursiveRuleDependenciesOfTypes(
          AppleBuildRules.RecursiveRuleDependenciesMode.BUILDING,
          testBundleRule,
          Optional.<ImmutableSet<BuildRuleType>>absent());
    recursiveTestRulesBuilder.addAll(testBundleRuleDependencies);
    recursiveTestRulesBuilder.add(testBundleRule);
    orderedTestBundleRulesBuilder.add(testBundleRule);
  }
}
