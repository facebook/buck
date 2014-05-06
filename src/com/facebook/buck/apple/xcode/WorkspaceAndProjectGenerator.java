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
import com.facebook.buck.apple.XcodeNative;
import com.facebook.buck.apple.XcodeNativeDescription;
import com.facebook.buck.apple.XcodeWorkspaceConfig;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

public class WorkspaceAndProjectGenerator {
  static final String DEPENDENCIES_GROUP = "Dependencies";

  private final ProjectFilesystem projectFilesystem;
  private final Path outputDirectory;
  private final PartialGraph partialGraph;
  private final ExecutionContext executionContext;
  private final BuildTarget workspaceConfigTarget;

  public WorkspaceAndProjectGenerator(
      ProjectFilesystem projectFilesystem,
      Path outputDirectory,
      PartialGraph partialGraph,
      ExecutionContext executionContext,
      BuildTarget workspaceConfigTarget) {
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.outputDirectory = Preconditions.checkNotNull(outputDirectory);
    this.partialGraph = Preconditions.checkNotNull(partialGraph);
    this.executionContext = Preconditions.checkNotNull(executionContext);
    this.workspaceConfigTarget = Preconditions.checkNotNull(workspaceConfigTarget);
  }

  public Path generateWorkspaceAndDependentProjects() throws IOException {
    BuildRule workspaceTargetRule =
        partialGraph.getDependencyGraph().findBuildRuleByTarget(workspaceConfigTarget);

    if (!(workspaceTargetRule.getBuildable() instanceof XcodeWorkspaceConfig)) {
      throw new HumanReadableException("%s must be a xcode_workspace_config",
          workspaceTargetRule.getFullyQualifiedName());
    }

    XcodeWorkspaceConfig workspaceBuildable =
        (XcodeWorkspaceConfig) workspaceTargetRule.getBuildable();
    BuildRule actualTargetRule = workspaceBuildable.getSrcTarget();

    String workspaceName = workspaceBuildable.getSrcTarget().getBuildTarget().getShortName();

    WorkspaceGenerator workspaceGenerator = new WorkspaceGenerator(
        projectFilesystem,
        workspaceName,
        outputDirectory);

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        partialGraph,
        workspaceName,
        outputDirectory.resolve(workspaceName + ".xcworkspace"));

    Iterable<BuildRule> allRules = Iterables.concat(
        RuleDependencyFinder.getAllRules(
            partialGraph,
            ImmutableList.of(workspaceBuildable.getSrcTarget().getBuildTarget())),
        ImmutableList.of(workspaceTargetRule));

    Multimap<String, BuildRule> buildRulesByTargetBasePath =
        BuildRules.buildRulesByTargetBasePath(allRules);

    for (String basePath : buildRulesByTargetBasePath.keySet()) {
      Set<BuildTarget> nativeTargets = FluentIterable.from(buildRulesByTargetBasePath.get(basePath))
          .filter(new Predicate<BuildRule>() {
              @Override
              public boolean apply(BuildRule rule) {
                return AppleBuildRules.isXcodeTargetBuildRuleType(rule.getType());
              }
            })
          .transform(new Function<BuildRule, BuildTarget>() {
              @Override
              public BuildTarget apply(BuildRule rule) {
                return rule.getBuildTarget();
              }
            })
          .toSet();

      Set<BuildRule> xcodeNativeProjectRules = Sets.newHashSet(Collections2.filter(
          buildRulesByTargetBasePath.get(basePath), new Predicate<BuildRule>() {
            @Override
            public boolean apply(BuildRule rule) {
              return rule.getType() == XcodeNativeDescription.TYPE;
            }
          }));

      boolean shouldGenerateProject = !nativeTargets.isEmpty();

      if (shouldGenerateProject) {
        ProjectGenerator generator = new ProjectGenerator(
            partialGraph,
            nativeTargets,
            projectFilesystem,
            executionContext,
            projectFilesystem.resolve(Paths.get(basePath)),
            Paths.get(basePath).getFileName().toString(),
            ProjectGenerator.SEPARATED_PROJECT_OPTIONS);
        generator.createXcodeProjects();

        String workspaceGroup = nativeTargets.contains(actualTargetRule) ? "" : DEPENDENCIES_GROUP;
        workspaceGenerator.addFilePath(workspaceGroup, generator.getProjectPath());

        schemeGenerator.addRuleToTargetMap(generator.getBuildRuleToGeneratedTargetMap());
        for (PBXTarget target : generator.getBuildRuleToGeneratedTargetMap().values()) {
          schemeGenerator.addTargetToProjectPathMap(target, generator.getProjectPath());
        }
      }

      for (BuildRule rule : xcodeNativeProjectRules) {
        XcodeNative buildable = (XcodeNative) rule.getBuildable();
        Path projectPath = buildable.getProjectContainerPath().resolve();
        Path pbxprojectPath = projectPath.resolve("project.pbxproj");
        String targetName = rule.getBuildTarget().getShortName();

        workspaceGenerator.addFilePath(DEPENDENCIES_GROUP,
            outputDirectory.resolveSibling(projectPath));

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
          schemeGenerator.addTargetToProjectPathMap(fakeTarget, pbxprojectPath);
        }
      }
    }
    Path workspacePath = workspaceGenerator.writeWorkspace();
    schemeGenerator.writeScheme();

    return workspacePath;
  }
}
