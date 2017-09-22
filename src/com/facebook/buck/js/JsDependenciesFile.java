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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    final SourcePathResolver sourcePathResolver = context.getSourcePathResolver();

    SourcePath outputFile = getSourcePathToOutput();
    String jobArgs =
        Stream.concat(
                Stream.of(
                    "dependencies",
                    JsFlavors.bundleJobArgs(getBuildTarget().getFlavors()),
                    JsUtil.resolveMapJoin(libraries, sourcePathResolver, p -> "--lib " + p),
                    String.format(
                        "--root %s --out %s",
                        getProjectFilesystem().getRootPath(),
                        sourcePathResolver.getAbsolutePath(outputFile))),
                entryPoints.stream())
            .filter(s -> !s.isEmpty())
            .collect(Collectors.joining(" "));

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

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(
        getBuildTarget(),
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.deps"));
  }
}
