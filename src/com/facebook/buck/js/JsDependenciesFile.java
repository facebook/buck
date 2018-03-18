/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.js;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.json.JsonBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

public class JsDependenciesFile extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  @AddToRuleKey private final ImmutableSet<String> entryPoints;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> libraries;

  @AddToRuleKey private final WorkerTool worker;

  protected JsDependenciesFile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      ImmutableSortedSet<SourcePath> libraries,
      ImmutableSet<String> entryPoints,
      WorkerTool worker) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.entryPoints = entryPoints;
    this.libraries = libraries;
    this.worker = worker;
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    SourcePathResolver sourcePathResolver = context.getSourcePathResolver();

    SourcePath outputFile = getSourcePathToOutput();
    String jobArgs = getJobArgs(sourcePathResolver, outputFile);

    buildableContext.recordArtifact(sourcePathResolver.getRelativePath(outputFile));

    return ImmutableList.<Step>builder()
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    sourcePathResolver.getRelativePath(outputFile).getParent())),
            JsUtil.workerShellStep(
                worker, jobArgs, getBuildTarget(), sourcePathResolver, getProjectFilesystem()))
        .build();
  }

  private String getJobArgs(SourcePathResolver sourcePathResolver, SourcePath outputFilePath) {

    ImmutableSortedSet<Flavor> flavors = getBuildTarget().getFlavors();

    return JsonBuilder.object()
        .addString("outputFilePath", sourcePathResolver.getAbsolutePath(outputFilePath).toString())
        .addString("command", "dependencies")
        .addArray("entryPoints", entryPoints.stream().collect(JsonBuilder.toArrayOfStrings()))
        .addArray(
            "libraries",
            libraries
                .stream()
                .map(sourcePathResolver::getAbsolutePath)
                .map(Path::toString)
                .collect(JsonBuilder.toArrayOfStrings()))
        .addString("platform", JsUtil.getPlatformString(flavors))
        .addBoolean("release", flavors.contains(JsFlavors.RELEASE))
        .addString("rootPath", getProjectFilesystem().getRootPath().toString())
        .toString();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(
        getBuildTarget(),
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.deps"));
  }
}
