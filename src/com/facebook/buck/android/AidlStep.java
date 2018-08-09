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

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.Verbosity;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

public class AidlStep extends ShellStep {

  private final ProjectFilesystem filesystem;
  private final ToolchainProvider toolchainProvider;
  private final Path aidlFilePath;
  private final Set<String> importDirectoryPaths;
  private final Path destinationDirectory;

  public AidlStep(
      ProjectFilesystem filesystem,
      ToolchainProvider toolchainProvider,
      Path aidlFilePath,
      Set<String> importDirectoryPaths,
      Path destinationDirectory) {
    super(filesystem.getRootPath());

    this.filesystem = filesystem;
    this.toolchainProvider = toolchainProvider;
    this.aidlFilePath = aidlFilePath;
    this.importDirectoryPaths = ImmutableSet.copyOf(importDirectoryPaths);
    this.destinationDirectory = destinationDirectory;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();

    // The arguments passed to aidl are based off of what I observed when running Ant in verbose
    // mode.
    verifyImportPaths(filesystem, importDirectoryPaths);

    AndroidPlatformTarget androidPlatformTarget =
        toolchainProvider.getByName(
            AndroidPlatformTarget.DEFAULT_NAME, AndroidPlatformTarget.class);

    args.add(androidPlatformTarget.getAidlExecutable().toString());

    // For some reason, all of the flags to aidl do not permit a space between the flag name and
    // the flag value.

    // file created by --preprocess to import
    args.add("-p" + androidPlatformTarget.getAndroidFrameworkIdlFile());

    // search path for import statements
    for (String importDirectoryPath : importDirectoryPaths) {
      Path resolved = filesystem.getPathForRelativePath(Paths.get(importDirectoryPath));
      args.add("-I" + resolved);
    }

    // base output folder for generated files
    args.add("-o" + filesystem.resolve(destinationDirectory));

    // aidl includes this path in the generated file and so it must not be absolute.
    args.add(MorePaths.relativize(workingDirectory, aidlFilePath).toString());

    return args.build();
  }

  private void verifyImportPaths(ProjectFilesystem filesystem, Set<String> importDirectoryPaths) {
    for (String path : importDirectoryPaths) {
      if (!filesystem.exists(Paths.get(path))) {
        throw new HumanReadableException("Cannot find import path: %s", path);
      }
    }
  }

  @Override
  protected boolean shouldPrintStderr(Verbosity verbosity) {
    return verbosity.shouldPrintStandardInformation();
  }

  @Override
  public String getShortName() {
    return "aidl";
  }
}
