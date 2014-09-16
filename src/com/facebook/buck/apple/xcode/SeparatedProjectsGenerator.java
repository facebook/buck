/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.apple.XcodeProjectConfig;
import com.facebook.buck.apple.XcodeProjectConfigDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.Path;

import javax.annotation.Nullable;

/**
 * Generate separate xcode projects based on the given xcode_project_config rules
 */
public class SeparatedProjectsGenerator {
  private final ProjectFilesystem projectFilesystem;
  private final ActionGraph actionGraph;
  private final ExecutionContext executionContext;
  private final ImmutableSet<BuildTarget> projectConfigTargets;
  private final ImmutableSet<ProjectGenerator.Option> projectGeneratorOptions;

  /**
   * Project generators used to generate the projects, useful for testing to retrieve pre-serialized
   * structures.
   */
  @Nullable
  private ImmutableMap<BuildTarget, ProjectGenerator> projectGenerators;

  public SeparatedProjectsGenerator(
      ProjectFilesystem projectFilesystem,
      ActionGraph actionGraph,
      ExecutionContext executionContext,
      ImmutableSet<BuildTarget> projectConfigTargets,
      ImmutableSet<ProjectGenerator.Option> projectGeneratorOptions) {
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.actionGraph = Preconditions.checkNotNull(actionGraph);
    this.executionContext = Preconditions.checkNotNull(executionContext);
    this.projectConfigTargets = Preconditions.checkNotNull(projectConfigTargets);
    this.projectGenerators = null;
    this.projectGeneratorOptions = ImmutableSet.<ProjectGenerator.Option>builder()
      .addAll(projectGeneratorOptions)
      .addAll(ProjectGenerator.SEPARATED_PROJECT_OPTIONS)
      .build();

    for (BuildTarget target : projectConfigTargets) {
      BuildRule rule = this.actionGraph.findBuildRuleByTarget(target);
      if (rule == null) {
        throw new HumanReadableException(
            "target not found: " + target.toString());
      }
      if (!rule.getType().equals(XcodeProjectConfigDescription.TYPE)) {
        throw new HumanReadableException(
            "expected only 'xcode_project_config' rules, got a '" +
                rule.getType().toString() +
                "' rule: " +
                target.toString());
      }
    }
  }

  public ImmutableSet<Path> generateProjects() throws IOException {
    ImmutableSet.Builder<Path> generatedProjectPathsBuilder = ImmutableSet.builder();
    ImmutableMap.Builder<BuildTarget, ProjectGenerator> projectGeneratorsBuilder =
        ImmutableMap.builder();
    for (BuildTarget target : projectConfigTargets) {
      BuildRule rule = actionGraph.findBuildRuleByTarget(target);
      XcodeProjectConfig buildable =
          (XcodeProjectConfig) Preconditions.checkNotNull(rule);

      ImmutableSet.Builder<BuildTarget> initialTargetsBuilder = ImmutableSet.builder();
      for (BuildRule memberRule : buildable.getRules()) {
        initialTargetsBuilder.add(memberRule.getBuildTarget());
      }
      ProjectGenerator generator = new ProjectGenerator(
          actionGraph.getNodes(),
          initialTargetsBuilder.build(),
          projectFilesystem,
          executionContext,
          target.getBasePath(),
          buildable.getProjectName(),
          projectGeneratorOptions);
      generator.createXcodeProjects();
      projectGeneratorsBuilder.put(target, generator);
      generatedProjectPathsBuilder.add(generator.getProjectPath());
    }
    projectGenerators = projectGeneratorsBuilder.build();
    return generatedProjectPathsBuilder.build();
  }

  @VisibleForTesting
  @Nullable
  ImmutableMap<BuildTarget, ProjectGenerator> getProjectGenerators() {
    return projectGenerators;
  }
}
