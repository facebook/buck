/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.nio.file.Paths;

public class NdkBuildStep extends ShellStep {

  private final String makefileDirectory;
  private final String makefilePath;
  private final Path buildArtifactsDirectory;
  private final Path binDirectory;
  private final ImmutableList<String> flags;
  private final int maxJobCount;

  public NdkBuildStep(
      String makefileDirectory,
      Path buildArtifactsDirectory,
      Path binDirectory,
      Iterable<String> flags) {
    this.makefileDirectory = Preconditions.checkNotNull(makefileDirectory);
    Preconditions.checkArgument(makefileDirectory.endsWith("/"));
    this.makefilePath = this.makefileDirectory + "Android.mk";
    this.buildArtifactsDirectory = Preconditions.checkNotNull(buildArtifactsDirectory);
    this.binDirectory = Preconditions.checkNotNull(binDirectory);
    this.flags = ImmutableList.copyOf(flags);
    this.maxJobCount = Runtime.getRuntime().availableProcessors();
  }

  @Override
  public String getShortName() {
    return "ndk_build";
  }

  @Override
  protected boolean shouldPrintStderr(Verbosity verbosity) {
    return verbosity.shouldPrintStandardInformation();
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    Optional<Path> ndkRoot = context.getAndroidPlatformTarget().getNdkDirectory();
    if (!ndkRoot.isPresent()) {
      throw new HumanReadableException("Must define a local.properties file"
          + " with a property named 'ndk.dir' that points to the absolute path of"
          + " your Android NDK directory, or set ANDROID_NDK.");
    }

    ImmutableList.Builder<String> builder = ImmutableList.builder();
    builder.add(
        ndkRoot.get().resolve("ndk-build").toAbsolutePath().toString(),
        "-j",
        Integer.toString(this.maxJobCount),
        "-C",
        this.makefileDirectory);

    builder.addAll(this.flags);

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    Function<Path, Path> absolutifier = projectFilesystem.getAbsolutifier();
    builder.add(
        "APP_PROJECT_PATH=" + absolutifier.apply(buildArtifactsDirectory) + "/",
        "APP_BUILD_SCRIPT=" + absolutifier.apply(Paths.get(makefilePath)),
        "NDK_OUT=" + absolutifier.apply(buildArtifactsDirectory) + "/",
        "NDK_LIBS_OUT=" + projectFilesystem.resolve(binDirectory));

    return builder.build();
  }
}
