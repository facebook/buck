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

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

public class JsLibrary extends AbstractBuildRule {

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> libraryDependencies;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> sources;

  @AddToRuleKey private final WorkerTool worker;

  protected JsLibrary(
      BuildRuleParams params,
      ImmutableSortedSet<SourcePath> sources,
      ImmutableSortedSet<SourcePath> libraryDependencies,
      WorkerTool worker) {
    super(params);
    this.libraryDependencies = libraryDependencies;
    this.sources = sources;
    this.worker = worker;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    final SourcePathResolver sourcePathResolver = context.getSourcePathResolver();
    buildableContext.recordArtifact(sourcePathResolver.getRelativePath(getSourcePathToOutput()));

    final Path outputPath = sourcePathResolver.getAbsolutePath(getSourcePathToOutput());
    final String jobArgs =
        String.format(
            "library %s %s %s --root %s --out %s %s",
            JsFlavors.OPTIMIZATION_DOMAIN.getValue(getBuildTarget().getFlavors()).orElse(""),
            JsFlavors.PLATFORM_DOMAIN.getValue(getBuildTarget().getFlavors()).orElse(""),
            JsUtil.resolveMapJoin(libraryDependencies, sourcePathResolver, p -> "--lib " + p),
            getProjectFilesystem().getRootPath(),
            outputPath,
            JsUtil.resolveMapJoin(sources, sourcePathResolver, Path::toString));
    return ImmutableList.of(
        RmStep.of(getProjectFilesystem(), outputPath),
        JsUtil.workerShellStep(
            worker, jobArgs, getBuildTarget(), sourcePathResolver, getProjectFilesystem()));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(
        getBuildTarget(),
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.json"));
  }

  public ImmutableSortedSet<SourcePath> getLibraryDependencies() {
    return libraryDependencies;
  }
}
