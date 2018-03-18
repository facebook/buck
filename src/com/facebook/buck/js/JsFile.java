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
import com.facebook.buck.io.file.MorePaths;
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
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.json.JsonBuilder;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

public abstract class JsFile extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  @AddToRuleKey private final Optional<String> extraArgs;

  @AddToRuleKey private final Optional<Arg> extraJson;

  @AddToRuleKey private final WorkerTool worker;

  public JsFile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Optional<Arg> extraJson,
      Optional<String> extraArgs,
      WorkerTool worker) {
    super(buildTarget, projectFilesystem, params);
    this.extraJson = extraJson;
    this.extraArgs = extraArgs;
    this.worker = worker;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(
        getBuildTarget(),
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.jsfile"));
  }

  public Optional<Arg> getExtraJson() {
    return extraJson;
  }

  public Optional<String> getExtraArgs() {
    return extraArgs;
  }

  ImmutableList<Step> getBuildSteps(BuildContext context, String jobArgs, Path outputPath) {
    return ImmutableList.of(
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), outputPath)),
        JsUtil.workerShellStep(
            worker,
            jobArgs,
            getBuildTarget(),
            context.getSourcePathResolver(),
            getProjectFilesystem()));
  }

  @Override
  public boolean isCacheable() {
    return false;
  }

  static class JsFileDev extends JsFile {
    @AddToRuleKey private final SourcePath src;

    @AddToRuleKey private final Optional<String> subPath;

    @AddToRuleKey private final Optional<String> virtualPath;

    JsFileDev(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams params,
        SourcePath src,
        Optional<String> subPath,
        Optional<Path> virtualPath,
        Optional<Arg> extraJson,
        Optional<String> extraArgs,
        WorkerTool worker) {
      super(buildTarget, projectFilesystem, params, extraJson, extraArgs, worker);
      this.src = src;
      this.subPath = subPath;
      this.virtualPath = virtualPath.map(MorePaths::pathWithUnixSeparators);
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      SourcePathResolver sourcePathResolver = context.getSourcePathResolver();
      buildableContext.recordArtifact(sourcePathResolver.getRelativePath(getSourcePathToOutput()));

      Path outputPath = sourcePathResolver.getAbsolutePath(getSourcePathToOutput());

      Path srcPath = sourcePathResolver.getAbsolutePath(src);
      String jobArgs =
          JsonBuilder.object()
              .addString("command", "transform")
              .addString("outputFilePath", outputPath.toString())
              .addString(
                  "sourceJsFilePath", subPath.map(srcPath::resolve).orElse(srcPath).toString())
              .addString(
                  "sourceJsFileName",
                  virtualPath.orElseGet(
                      () ->
                          MorePaths.pathWithUnixSeparators(
                              sourcePathResolver.getRelativePath(src))))
              .addRaw("extraData", getExtraJson().map(a -> Arg.stringify(a, sourcePathResolver)))
              .addString("extraArgs", getExtraArgs())
              .toString();

      return getBuildSteps(context, jobArgs, outputPath);
    }

    @VisibleForTesting
    SourcePath getSource() {
      return src;
    }

    @VisibleForTesting
    Optional<String> getVirtualPath() {
      return virtualPath;
    }
  }

  static class JsFileRelease extends JsFile {

    @AddToRuleKey private final SourcePath devFile;

    JsFileRelease(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams buildRuleParams,
        SourcePath devFile,
        Optional<Arg> extraJson,
        Optional<String> extraArgs,
        WorkerTool worker) {
      super(buildTarget, projectFilesystem, buildRuleParams, extraJson, extraArgs, worker);
      this.devFile = devFile;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      SourcePathResolver sourcePathResolver = context.getSourcePathResolver();
      buildableContext.recordArtifact(sourcePathResolver.getRelativePath(getSourcePathToOutput()));

      Path outputPath = sourcePathResolver.getAbsolutePath(getSourcePathToOutput());
      String jobArgs =
          JsonBuilder.object()
              .addString("command", "optimize")
              .addString("outputFilePath", outputPath.toString())
              .addString("platform", JsUtil.getPlatformString(getBuildTarget().getFlavors()))
              .addString(
                  "transformedJsFilePath", sourcePathResolver.getAbsolutePath(devFile).toString())
              .addRaw("extraData", getExtraJson().map(a -> Arg.stringify(a, sourcePathResolver)))
              .addString("extraArgs", getExtraArgs())
              .toString();

      return getBuildSteps(context, jobArgs, outputPath);
    }
  }
}
