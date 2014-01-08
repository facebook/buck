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
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.Set;

public class AidlStep extends ShellStep {

  private final Path aidlFilePath;
  private final Set<String> importDirectoryPaths;
  private final Path destinationDirectory;

  public AidlStep(
      Path aidlFilePath,
      Set<String> importDirectoryPaths,
      Path destinationDirectory) {
    this.aidlFilePath = Preconditions.checkNotNull(aidlFilePath);
    this.importDirectoryPaths = ImmutableSet.copyOf(importDirectoryPaths);
    this.destinationDirectory = Preconditions.checkNotNull(destinationDirectory);
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();

    // The arguments passed to aidl are based off of what I observed when running Ant in verbose
    // mode.
    AndroidPlatformTarget androidPlatformTarget = context.getAndroidPlatformTarget();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    args.add(androidPlatformTarget.getAidlExecutable().getAbsolutePath());

    // For some reason, all of the flags to aidl do not permit a space between the flag name and
    // the flag value.

    // fail when trying to compile a parcelable
    args.add("-b");

    // file created by --preprocess to import
    args.add("-p" + androidPlatformTarget.getAndroidFrameworkIdlFile().getAbsolutePath());

    // search path for import statements
    for (String importDirectoryPath : importDirectoryPaths) {
      args.add("-I" + importDirectoryPath);
    }

    // base output folder for generated files
    args.add("-o" + projectFilesystem.resolve(destinationDirectory));

    args.add(aidlFilePath.toString());

    return args.build();
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
