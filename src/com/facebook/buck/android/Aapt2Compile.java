/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.zip.ZipScrubberStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.SortedSet;
import javax.annotation.Nullable;

/** Perform the "aapt2 compile" step of a single Android resource. */
public class Aapt2Compile extends AbstractBuildRule {
  private final AndroidPlatformTarget androidPlatformTarget;
  // TODO(dreiss): Eliminate this and just make resDir our dep.
  private final ImmutableSortedSet<BuildRule> compileDeps;
  @AddToRuleKey private final SourcePath resDir;

  public Aapt2Compile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      AndroidPlatformTarget androidPlatformTarget,
      ImmutableSortedSet<BuildRule> compileDeps,
      SourcePath resDir) {
    super(buildTarget, projectFilesystem);
    this.androidPlatformTarget = androidPlatformTarget;
    this.compileDeps = compileDeps;
    this.resDir = resDir;
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return compileDeps;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                getProjectFilesystem(),
                getOutputPath().getParent())));
    steps.add(
        new Aapt2CompileStep(
            context.getSourcePathResolver(),
            getProjectFilesystem().getRootPath(),
            androidPlatformTarget,
            context.getSourcePathResolver().getAbsolutePath(resDir),
            getOutputPath()));
    steps.add(ZipScrubberStep.of(getProjectFilesystem().resolve(getOutputPath())));
    buildableContext.recordArtifact(getOutputPath());

    return steps.build();
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getOutputPath());
  }

  private Path getOutputPath() {
    return BuildTargetPaths.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "%s/resources.flata");
  }

  static class Aapt2CompileStep extends ShellStep {
    private final AndroidPlatformTarget androidPlatformTarget;
    private final Path resDirPath;
    private final Path outputPath;
    private final SourcePathResolver pathResolver;

    Aapt2CompileStep(
        SourcePathResolver pathResolver,
        Path workingDirectory,
        AndroidPlatformTarget androidPlatformTarget,
        Path resDirPath,
        Path outputPath) {
      super(workingDirectory);
      this.pathResolver = pathResolver;
      this.androidPlatformTarget = androidPlatformTarget;
      this.resDirPath = resDirPath;
      this.outputPath = outputPath;
    }

    @Override
    public String getShortName() {
      return "aapt2_compile";
    }

    @Override
    protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      builder.addAll(
          androidPlatformTarget.getAapt2Executable().get().getCommandPrefix(pathResolver));

      builder.add("compile");
      builder.add("--legacy"); // TODO(dreiss): Maybe make this an option?
      builder.add("-o");
      builder.add(outputPath.toString());
      builder.add("--dir");
      builder.add(resDirPath.toString());

      return builder.build();
    }
  }
}
