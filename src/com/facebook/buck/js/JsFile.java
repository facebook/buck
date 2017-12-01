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
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

public abstract class JsFile extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  @AddToRuleKey private final Optional<String> extraArgs;

  @AddToRuleKey private final WorkerTool worker;

  public JsFile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Optional<String> extraArgs,
      WorkerTool worker) {
    super(buildTarget, projectFilesystem, params);
    this.extraArgs = extraArgs;
    this.worker = worker;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(
        getBuildTarget(),
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.jsfile"));
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
        Optional<String> extraArgs,
        WorkerTool worker) {
      super(buildTarget, projectFilesystem, params, extraArgs, worker);
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

      String jobArgs;
      try {
        jobArgs = getJobArgs(sourcePathResolver, outputPath);
      } catch (IOException ex) {
        throw JsUtil.getJobArgsException(ex, getBuildTarget());
      }

      return getBuildSteps(context, jobArgs, outputPath);
    }

    private String getJobArgs(SourcePathResolver sourcePathResolver, Path outputFilePath)
        throws IOException {
      BuildTarget target = getBuildTarget();
      ByteArrayOutputStream stream = new ByteArrayOutputStream();
      JsonGenerator generator = ObjectMappers.createGenerator(stream);
      generator.writeStartObject();

      generator.writeFieldName("command");
      generator.writeString("transform");

      generator.writeFieldName("outputFilePath");
      generator.writeString(outputFilePath.toString());

      generator.writeFieldName("sourceJsFilePath");
      Path srcPath = sourcePathResolver.getAbsolutePath(src);
      generator.writeString(subPath.map(srcPath::resolve).orElse(srcPath).toString());

      generator.writeFieldName("sourceJsFileName");
      generator.writeString(
          virtualPath.orElseGet(
              () -> MorePaths.pathWithUnixSeparators(sourcePathResolver.getRelativePath(src))));

      getExtraArgs()
          .ifPresent(
              value -> {
                try {
                  generator.writeFieldName("extraArgs");
                  generator.writeString(value);
                } catch (IOException ex) {
                  throw JsUtil.getJobArgsException(ex, target);
                }
              });

      generator.writeEndObject();
      generator.close();
      return stream.toString(StandardCharsets.UTF_8.name());
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
        Optional<String> extraArgs,
        WorkerTool worker) {
      super(buildTarget, projectFilesystem, buildRuleParams, extraArgs, worker);
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
              "optimize %s %s --out %s %s",
              getExtraArgs().orElse(""),
              JsFlavors.platformArgForRelease(getBuildTarget().getFlavors()),
              outputPath,
              sourcePathResolver.getAbsolutePath(devFile).toString());

      return getBuildSteps(context, jobArgs, outputPath);
    }
  }
}
