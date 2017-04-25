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

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JsBundle extends AbstractBuildRule implements JsBundleOutputs {

  @AddToRuleKey private final String bundleName;

  @AddToRuleKey private final ImmutableSet<String> entryPoints;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> libraries;

  @AddToRuleKey private final WorkerTool worker;

  protected JsBundle(
      BuildRuleParams params,
      ImmutableSortedSet<SourcePath> libraries,
      ImmutableSet<String> entryPoints,
      String bundleName,
      WorkerTool worker) {
    super(params);
    this.bundleName = bundleName;
    this.entryPoints = entryPoints;
    this.libraries = libraries;
    this.worker = worker;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    final SourcePathResolver sourcePathResolver = context.getSourcePathResolver();
    final SourcePath jsOutputDir = getSourcePathToOutput();
    final SourcePath sourceMapFile = getSourcePathToSourceMap();
    final SourcePath resourcesDir = getSourcePathToResources();

    this.getProjectFilesystem().getRootPath();

    String jobArgs =
        Stream.concat(
                Stream.of(
                    "bundle",
                    JsFlavors.bundleJobArgs(getBuildTarget().getFlavors()),
                    JsUtil.resolveMapJoin(libraries, sourcePathResolver, p -> "--lib " + p),
                    String.format(
                        "--root %s --sourcemap %s --assets %s --out %s/%s",
                        getProjectFilesystem().getRootPath(),
                        sourcePathResolver.getAbsolutePath(sourceMapFile),
                        sourcePathResolver.getAbsolutePath(resourcesDir),
                        sourcePathResolver.getAbsolutePath(jsOutputDir),
                        bundleName)),
                entryPoints.stream())
            .filter(s -> !s.isEmpty())
            .collect(Collectors.joining(" "));

    buildableContext.recordArtifact(sourcePathResolver.getRelativePath(jsOutputDir));
    buildableContext.recordArtifact(sourcePathResolver.getRelativePath(sourceMapFile));
    buildableContext.recordArtifact(sourcePathResolver.getRelativePath(resourcesDir));

    return ImmutableList.<Step>builder()
        .addAll(
            MakeCleanDirectoryStep.of(
                getProjectFilesystem(),
                sourcePathResolver.getRelativePath(
                    JsUtil.relativeToOutputRoot(getBuildTarget(), getProjectFilesystem(), ""))))
        .add(
            MkdirStep.of(getProjectFilesystem(), sourcePathResolver.getAbsolutePath(jsOutputDir)),
            MkdirStep.of(
                getProjectFilesystem(),
                sourcePathResolver.getAbsolutePath(sourceMapFile).getParent()),
            MkdirStep.of(getProjectFilesystem(), sourcePathResolver.getAbsolutePath(resourcesDir)),
            JsUtil.workerShellStep(
                worker, jobArgs, getBuildTarget(), sourcePathResolver, getProjectFilesystem()))
        .build();
  }

  @Override
  public String getBundleName() {
    return bundleName;
  }
}
