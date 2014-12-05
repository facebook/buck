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

import com.facebook.buck.apple.XcodeProjectConfigDescription;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
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
  private final TargetGraph targetGraph;
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
      TargetGraph targetGraph,
      ExecutionContext executionContext,
      ImmutableSet<BuildTarget> projectConfigTargets,
      ImmutableSet<ProjectGenerator.Option> projectGeneratorOptions) {
    this.projectFilesystem = projectFilesystem;
    this.targetGraph = targetGraph;
    this.executionContext = executionContext;
    this.projectConfigTargets = projectConfigTargets;
    this.projectGenerators = null;
    this.projectGeneratorOptions = ImmutableSet.<ProjectGenerator.Option>builder()
      .addAll(projectGeneratorOptions)
      .addAll(ProjectGenerator.SEPARATED_PROJECT_OPTIONS)
      .build();

    for (BuildTarget target : projectConfigTargets) {
      TargetNode<?> node = this.targetGraph.get(target);
      if (node == null) {
        throw new HumanReadableException(
            "target not found: " + target.toString());
      }
      if (!node.getType().equals(XcodeProjectConfigDescription.TYPE)) {
        throw new HumanReadableException(
            "expected only 'xcode_project_config' rules, got a '" +
                node.getType().toString() +
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
      TargetNode<?> node = Preconditions.checkNotNull(targetGraph.get(target));
      XcodeProjectConfigDescription.Arg arg =
          (XcodeProjectConfigDescription.Arg) node.getConstructorArg();

      ImmutableSet.Builder<BuildTarget> initialTargetsBuilder = ImmutableSet.builder();
      for (TargetNode<?> memberNode : targetGraph.getAll(arg.rules)) {
        initialTargetsBuilder.add(memberNode.getBuildTarget());
      }
      ProjectGenerator generator = new ProjectGenerator(
          targetGraph,
          initialTargetsBuilder.build(),
          projectFilesystem,
          executionContext,
          target.getBasePath(),
          arg.projectName,
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
