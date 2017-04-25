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

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.zip.ZipScrubberStep;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import javax.annotation.Nullable;

/** Perform the "aapt2 compile" step of a single Android resource. */
public class Aapt2Compile extends AbstractBuildRule {
  @AddToRuleKey private final SourcePath resDir;

  public Aapt2Compile(BuildRuleParams buildRuleParams, SourcePath resDir) {
    super(buildRuleParams);
    this.resDir = resDir;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), getOutputPath().getParent()));
    steps.add(
        new Aapt2CompileStep(
            getProjectFilesystem().getRootPath(),
            context.getSourcePathResolver().getRelativePath(resDir),
            getOutputPath()));
    steps.add(ZipScrubberStep.of(getProjectFilesystem().resolve(getOutputPath())));
    buildableContext.recordArtifact(getOutputPath());

    return steps.build();
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), getOutputPath());
  }

  private Path getOutputPath() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s/resources.flata");
  }

  static class Aapt2CompileStep extends ShellStep {
    private final Path resDirPath;
    private final Path outputPath;

    Aapt2CompileStep(Path workingDirectory, Path resDirPath, Path outputPath) {
      super(workingDirectory);
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
      AndroidPlatformTarget androidPlatformTarget = context.getAndroidPlatformTarget();

      builder.add(androidPlatformTarget.getAapt2Executable().toString());
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
