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

import com.facebook.buck.io.MorePaths;
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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

public abstract class JsFile extends AbstractBuildRule {

  @AddToRuleKey private final Optional<String> extraArgs;

  @AddToRuleKey private final WorkerTool worker;

  public JsFile(BuildRuleParams params, Optional<String> extraArgs, WorkerTool worker) {
    super(params);
    this.extraArgs = extraArgs;
    this.worker = worker;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(
        getBuildTarget(),
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.json"));
  }

  ImmutableList<Step> getBuildSteps(BuildContext context, String jobArgsFormat, Path outputPath) {
    return ImmutableList.of(
        RmStep.of(getProjectFilesystem(), outputPath),
        JsUtil.workerShellStep(
            worker,
            String.format(jobArgsFormat, extraArgs.orElse("")),
            getBuildTarget(),
            context.getSourcePathResolver(),
            getProjectFilesystem()));
  }

  static class JsFileDev extends JsFile {
    @AddToRuleKey private final SourcePath src;

    @AddToRuleKey private final Optional<String> subPath;

    @AddToRuleKey private final Optional<String> virtualPath;

    JsFileDev(
        BuildRuleParams params,
        SourcePath src,
        Optional<String> subPath,
        Optional<Path> virtualPath,
        Optional<String> extraArgs,
        WorkerTool worker) {
      super(params, extraArgs, worker);
      this.src = src;
      this.subPath = subPath;
      this.virtualPath = virtualPath.map(MorePaths::pathWithUnixSeparators);
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      final SourcePathResolver sourcePathResolver = context.getSourcePathResolver();
      buildableContext.recordArtifact(sourcePathResolver.getRelativePath(getSourcePathToOutput()));

      final Path outputPath = sourcePathResolver.getAbsolutePath(getSourcePathToOutput());
      final Path srcPath = sourcePathResolver.getAbsolutePath(src);
      final String jobArgs =
          String.format(
              "transform %%s --filename %s --out %s %s",
              virtualPath.orElseGet(
                  () -> MorePaths.pathWithUnixSeparators(sourcePathResolver.getRelativePath(src))),
              outputPath,
              subPath.map(srcPath::resolve).orElse(srcPath));

      return getBuildSteps(context, jobArgs, outputPath);
    }

    @VisibleForTesting
    Optional<String> getVirtualPath() {
      return virtualPath;
    }
  }

  static class JsFileRelease extends JsFile {

    @AddToRuleKey private final SourcePath devFile;

    JsFileRelease(
        BuildRuleParams buildRuleParams,
        SourcePath devFile,
        Optional<String> extraArgs,
        WorkerTool worker) {
      super(buildRuleParams, extraArgs, worker);
      this.devFile = devFile;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      final SourcePathResolver sourcePathResolver = context.getSourcePathResolver();
      buildableContext.recordArtifact(sourcePathResolver.getRelativePath(getSourcePathToOutput()));

      final Path outputPath = sourcePathResolver.getAbsolutePath(getSourcePathToOutput());
      final String jobArgs =
          String.format(
              "optimize %%s %s --out %s %s",
              JsFlavors.platformArgForRelease(getBuildTarget().getFlavors()),
              outputPath,
              sourcePathResolver.getAbsolutePath(devFile).toString());

      return getBuildSteps(context, jobArgs, outputPath);
    }
  }
}
