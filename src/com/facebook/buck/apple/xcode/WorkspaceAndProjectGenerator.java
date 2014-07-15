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
import com.facebook.buck.apple.XcodeNative;
import com.facebook.buck.apple.XcodeNativeDescription;
import com.facebook.buck.apple.XcodeProjectConfig;
import com.facebook.buck.apple.XcodeWorkspaceConfig;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class WorkspaceAndProjectGenerator {
  private static final Logger LOG = Logger.get(WorkspaceAndProjectGenerator.class);
  private static final String DEPENDENCIES_GROUP = "Dependencies";

  private final ProjectFilesystem projectFilesystem;
  private final PartialGraph partialGraph;
  private final ExecutionContext executionContext;
  private final ImmutableSet<BuildTarget> workspaceConfigTargets;
  private final ImmutableSet<ProjectGenerator.Option> projectGeneratorOptions;

  public WorkspaceAndProjectGenerator(
      ProjectFilesystem projectFilesystem,
      PartialGraph partialGraph,
      ExecutionContext executionContext,
      Set<BuildTarget> workspaceConfigTargets,
      Set<ProjectGenerator.Option> projectGeneratorOptions) {
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.partialGraph = Preconditions.checkNotNull(partialGraph);
    this.executionContext = Preconditions.checkNotNull(executionContext);
    this.workspaceConfigTargets = ImmutableSet.copyOf(workspaceConfigTargets);
    this.projectGeneratorOptions = ImmutableSet.<ProjectGenerator.Option>builder()
      .addAll(projectGeneratorOptions)
      .addAll(ProjectGenerator.SEPARATED_PROJECT_OPTIONS)
      .build();
  }

  public ImmutableSet<Path> generateWorkspacesAndDependentProjects() throws IOException {
    LOG.debug("Generating workspaces for config targets %s", workspaceConfigTargets);
    ImmutableSet.Builder<Path> pathsBuilder = ImmutableSet.builder();
    Map<BuildRule, ProjectGenerator> projectGenerators = new HashMap<>();
    for (BuildTarget workspaceConfigTarget : workspaceConfigTargets) {
      pathsBuilder.add(
          generateWorkspaceAndDependentProjects(workspaceConfigTarget, projectGenerators));
    }
    return pathsBuilder.build();
  }

  private Path generateWorkspaceAndDependentProjects(
        BuildTarget workspaceConfigTarget,
        Map<BuildRule, ProjectGenerator> projectGenerators)
      throws IOException {
    BuildRule workspaceTargetRule =
        partialGraph.getActionGraph().findBuildRuleByTarget(workspaceConfigTarget);

    if (!(workspaceTargetRule instanceof XcodeWorkspaceConfig)) {
      throw new HumanReadableException("%s must be a xcode_workspace_config",
          workspaceTargetRule.getFullyQualifiedName());
    }

    LOG.debug("Generating workspace for config target %s", workspaceConfigTarget);
    XcodeWorkspaceConfig workspaceBuildable =
        (XcodeWorkspaceConfig) workspaceTargetRule;
    BuildRule actualTargetRule = workspaceBuildable.getSrcTarget();

    String workspaceName = workspaceBuildable.getSrcTarget().getBuildTarget().getShortName();

    Path outputDirectory = Paths.get(workspaceBuildable.getBuildTarget().getBasePath());

    WorkspaceGenerator workspaceGenerator = new WorkspaceGenerator(
        projectFilesystem,
        workspaceName,
        outputDirectory);

    Iterable<BuildRule> allRules = Iterables.concat(
        RuleDependencyFinder.getAllRules(
            partialGraph,
            ImmutableList.of(workspaceBuildable.getSrcTarget().getBuildTarget())),
        ImmutableList.of(workspaceTargetRule));

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        partialGraph,
        actualTargetRule,
        ImmutableSet.copyOf(allRules),
        workspaceName,
        outputDirectory.resolve(workspaceName + ".xcworkspace"),
        workspaceBuildable.getActionConfigNames());

    Multimap<String, BuildRule> buildRulesByTargetBasePath =
        BuildRules.buildRulesByTargetBasePath(allRules);

    for (String basePath : buildRulesByTargetBasePath.keySet()) {
      // From each target we find that package's xcode_project_config rule and generate it if
      // it hasn't already been generated.
      Optional<BuildRule> xcodeProjectConfigRule = Optional.fromNullable(Iterables.getOnlyElement(
          partialGraph.getActionGraph().getBuildRulesOfBuildableTypeInBasePath(
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
            partialGraph,
            initialTargets,
            projectFilesystem,
            executionContext,
            projectFilesystem.getPathForRelativePath(Paths.get(basePath)),
            xcodeProjectConfig.getProjectName(),
            projectGeneratorOptions);
          generator.createXcodeProjects();
          projectGenerators.put(xcodeProjectConfig, generator);
        } else {
          LOG.debug("Already generated project for rule %s, skipping", xcodeProjectConfig);
        }

        String workspaceGroup = initialTargets.contains(actualTargetRule.getBuildTarget()) ?
            "" : DEPENDENCIES_GROUP;
        workspaceGenerator.addFilePath(workspaceGroup, generator.getProjectPath());

        schemeGenerator.addRuleToTargetMap(generator.getBuildRuleToGeneratedTargetMap());
        for (PBXTarget target : generator.getBuildRuleToGeneratedTargetMap().values()) {
          schemeGenerator.addTargetToProjectPathMap(target, generator.getProjectPath());
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
        String targetName = rule.getBuildTarget().getShortName();

        workspaceGenerator.addFilePath(DEPENDENCIES_GROUP,
            projectFilesystem.getPathForRelativePath(projectPath));

        ImmutableMap.Builder<String, String> targetNameToGIDMapBuilder = ImmutableMap.builder();
        try (InputStream projectInputStream =
            projectFilesystem.newFileInputStream(pbxprojectPath)) {
          NSDictionary projectObjects =
              ProjectParser.extractObjectsFromXcodeProject(projectInputStream);
          ProjectParser.extractTargetNameToGIDMap(
              projectObjects,
              targetNameToGIDMapBuilder);
          Map<String, String> targetNameToGIDMap = targetNameToGIDMapBuilder.build();
          String targetGid = targetNameToGIDMap.get(targetName);

          PBXTarget fakeTarget = new PBXNativeTarget(targetName);
          fakeTarget.setGlobalID(targetGid);
          schemeGenerator.addRuleToTargetMap(ImmutableMap.of(rule, fakeTarget));
          schemeGenerator.addTargetToProjectPathMap(fakeTarget,
              projectFilesystem.getPathForRelativePath(projectPath));
        }
      }
    }
    Path workspacePath = workspaceGenerator.writeWorkspace();
    schemeGenerator.writeScheme();

    return workspacePath;
  }
}
