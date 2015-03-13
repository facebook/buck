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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import java.io.File;
import java.nio.file.Path;

public class NdkBuildStep extends ShellStep {

  private final Path root;
  private final Path makefile;
  private final Path buildArtifactsDirectory;
  private final Path binDirectory;
  private final ImmutableList<String> flags;
  private final int maxJobCount;
  private final Function<String, String> macroExpander;

  public NdkBuildStep(
      Path root,
      Path makefile,
      Path buildArtifactsDirectory,
      Path binDirectory,
      Iterable<String> flags,
      Function<String, String> macroExpander) {
    this.root = root;
    this.makefile = makefile;
    this.buildArtifactsDirectory = buildArtifactsDirectory;
    this.binDirectory = binDirectory;
    this.flags = ImmutableList.copyOf(flags);
    this.maxJobCount = Runtime.getRuntime().availableProcessors();
    this.macroExpander = macroExpander;
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
      throw new HumanReadableException("Must define a local.properties file" +
          " with a property named 'ndk.dir' that points to the absolute path of" +
          " your Android NDK directory, or set ANDROID_NDK.");
    }
    Optional<Path> ndkBuild = context.resolveExecutable(ndkRoot.get(), "ndk-build");
    if (!ndkBuild.isPresent()) {
      throw new HumanReadableException("Unable to find ndk-build");
    }

    ImmutableList.Builder<String> builder = ImmutableList.builder();
    builder.add(
        ndkBuild.get().toAbsolutePath().toString(),
        "-j",
        Integer.toString(this.maxJobCount),
        "-C",
        this.root.toString());


    Iterable<String> flags = Iterables.transform(this.flags, macroExpander);
    builder.addAll(flags);

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    Function<Path, Path> absolutifier = projectFilesystem.getAbsolutifier();
    builder.add(
        "APP_PROJECT_PATH=" + absolutifier.apply(buildArtifactsDirectory) + File.separatorChar,
        "APP_BUILD_SCRIPT=" + absolutifier.apply(makefile),
        "NDK_OUT=" + absolutifier.apply(buildArtifactsDirectory) + File.separatorChar,
        "NDK_LIBS_OUT=" + projectFilesystem.resolve(binDirectory),
        "BUCK_PROJECT_DIR=" + projectFilesystem.getRootPath());

    // Suppress the custom build step messages (e.g. "Compile++ ...").
    if (Platform.detect() == Platform.WINDOWS) {
      builder.add("host-echo-build-step=@REM");
    } else {
      builder.add("host-echo-build-step=@#");
    }

    // If we're running verbosely, force all the subcommands from the ndk build to be printed out.
    if (context.getVerbosity().shouldPrintCommand()) {
      builder.add("V=1");
    // Otherwise, suppress everything, including the "make: entering directory..." messages.
    } else {
      builder.add("--silent");
    }

    return builder.build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    ImmutableMap<String, String> base = super.getEnvironmentVariables(context);
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();

    // Ensure the external environment gets superceded by internal mappings.
    builder.putAll(context.getEnvironment());
    builder.putAll(base);
    return builder.build();
  }

  // The ndk-build command delegates to `make` to run a lot of subcommands, so print them as they
  // happen.
  @Override
  protected boolean shouldFlushStdOutErrAsProgressIsMade(Verbosity verbosity) {
    return verbosity.shouldPrintCommand();
  }

}
