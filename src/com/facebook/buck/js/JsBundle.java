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
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JsBundle extends AbstractBuildRule {

  @AddToRuleKey
  private final String bundleName;

  @AddToRuleKey
  private final ImmutableSet<String> entryPoints;

  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> libraries;

  @AddToRuleKey
  private final WorkerTool worker;

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
      BuildContext context,
      BuildableContext buildableContext) {
    final SourcePathResolver sourcePathResolver = context.getSourcePathResolver();
    final SourcePath jsOutputDir = getSourcePathToOutput();
    final SourcePath sourceMapFile = getSourcePathToSourceMap();
    final SourcePath resourcesDir = getSourcePathToResources();

    String jobArgs = Stream.concat(
        Stream.of(
            "bundle",
            JsFlavors.bundleJobArgs(getBuildTarget().getFlavors()),
            JsUtil.resolveMapJoin(libraries, sourcePathResolver, p -> "--lib " + p),
            String.format(
                "--sourcemap %s --assets %s --out %s/%s",
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

    return ImmutableList.of(
        RmStep.of(getProjectFilesystem(), sourcePathResolver.getAbsolutePath(getOutputRoot())),
        new MkdirStep(
            getProjectFilesystem(),
            sourcePathResolver.getAbsolutePath(jsOutputDir)),
        new MkdirStep(
            getProjectFilesystem(),
            sourcePathResolver.getAbsolutePath(sourceMapFile).getParent()),
        new MkdirStep(
            getProjectFilesystem(),
            sourcePathResolver.getAbsolutePath(resourcesDir)),
        JsUtil.workerShellStep(
            worker,
            jobArgs,
            getBuildTarget(),
            sourcePathResolver,
            getProjectFilesystem()));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePathRelativeToOutputRoot("%s/js");
  }

  public SourcePath getSourcePathToSourceMap() {
    return getSourcePathRelativeToOutputRoot(
        String.format("%%s/map/%s.map", bundleName.replace("%", "%%")));
  }

  public SourcePath getSourcePathToResources() {
    return getSourcePathRelativeToOutputRoot("%s/res");
  }

  private SourcePath getOutputRoot() {
    return getSourcePathRelativeToOutputRoot("%s");
  }

  private ExplicitBuildTargetSourcePath getSourcePathRelativeToOutputRoot(String format) {
    return new ExplicitBuildTargetSourcePath(
        getBuildTarget(),
        BuildTargets.getGenPath(
            getProjectFilesystem(),
            getBuildTarget(),
            format));
  }
}
