/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import com.android.tools.build.bundletool.commands.BuildApksCommand;
import com.android.tools.build.bundletool.model.Aapt2Command;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;

/** A {@link Step} that converts from an AndroidAppBundle to an apks. */
public class BuildApksStep implements Step {

  private final ProjectFilesystem projectFilesystem;
  private final Path aabFile;
  private final Path apksLocation;
  private final Path aapt2Location;

  public BuildApksStep(
      ProjectFilesystem filesystem, Path aabFile, Path apksLocation, Path aapt2Location) {

    this.projectFilesystem = filesystem;
    this.aabFile = aabFile;
    this.apksLocation = apksLocation;
    this.aapt2Location = aapt2Location;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {
    BuildApksCommand.Builder buildApksCommandBuilder =
        BuildApksCommand.builder()
            .setBundlePath(this.aabFile)
            .setOverwriteOutput(true)
            .setAapt2Command(Aapt2Command.createFromExecutablePath(aapt2Location))
            .setOutputFile(this.apksLocation)
            .setApkBuildMode(BuildApksCommand.ApkBuildMode.UNIVERSAL);

    buildApksCommandBuilder.build().execute();
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "aab_extraction";
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    ImmutableList.Builder<String> args =
        ImmutableList.<String>builder()
            .add("bundletool")
            .add("build-apks")
            .add("--bundle")
            .add("\"" + projectFilesystem.getPathForRelativePath(aabFile).toString() + "\"")
            .add("--output")
            .add("\"" + projectFilesystem.getPathForRelativePath(apksLocation).toString() + "\"")
            .add("--mode universal");
    return Joiner.on(" ").join(args.build());
  }
}
