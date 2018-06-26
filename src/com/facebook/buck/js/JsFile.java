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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.json.JsonBuilder;
import com.facebook.buck.util.json.JsonBuilder.ObjectBuilder;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

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
  public BuildTargetSourcePath getSourcePathToOutput() {
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

  @Nullable
  abstract BuildTarget getSourceBuildTarget(SourcePathRuleFinder ruleFinder);

  ImmutableList<Step> getBuildSteps(BuildContext context, ObjectBuilder jobArgs, Path outputPath) {
    return ImmutableList.of(
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), outputPath)),
        JsUtil.jsonWorkerShellStepAddingFlavors(
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

    @Nullable
    @Override
    BuildTarget getSourceBuildTarget(SourcePathRuleFinder ruleFinder) {
      return src instanceof BuildTargetSourcePath
          ? ((BuildTargetSourcePath) src).getTarget()
          : null;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      SourcePathResolver sourcePathResolver = context.getSourcePathResolver();
      buildableContext.recordArtifact(sourcePathResolver.getRelativePath(getSourcePathToOutput()));

      Path outputPath = sourcePathResolver.getAbsolutePath(getSourcePathToOutput());

      Path srcPath = sourcePathResolver.getAbsolutePath(src);
      ObjectBuilder jobArgs =
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
              .addString("extraArgs", getExtraArgs());

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

    @AddToRuleKey private final BuildTargetSourcePath devFile;

    JsFileRelease(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams buildRuleParams,
        BuildTargetSourcePath devFile,
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
      ObjectBuilder jobArgs =
          JsonBuilder.object()
              .addString("command", "optimize")
              .addString("outputFilePath", outputPath.toString())
              .addString("platform", JsUtil.getPlatformString(getBuildTarget().getFlavors()))
              .addString(
                  "transformedJsFilePath", sourcePathResolver.getAbsolutePath(devFile).toString())
              .addRaw("extraData", getExtraJson().map(a -> Arg.stringify(a, sourcePathResolver)))
              .addString("extraArgs", getExtraArgs());

      return getBuildSteps(context, jobArgs, outputPath);
    }

    @Override
    @Nullable
    BuildTarget getSourceBuildTarget(SourcePathRuleFinder ruleFinder) {
      JsFileDev devRule = (JsFileDev) ruleFinder.getRule(devFile);
      return devRule.getSourceBuildTarget(ruleFinder);
    }
  }
}
