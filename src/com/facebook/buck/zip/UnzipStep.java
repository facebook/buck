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

package com.facebook.buck.zip;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.IOException;

public class UnzipStep implements Step {

  private final String pathToZipFile;
  private final String pathToDestinationDirectory;
  private final boolean overwriteExistingFiles;

  /**
   * Creates an {@link UnzipStep} that extracts an entire .zip archive.
   * @param pathToZipFile archive to unzip.
   * @param pathToDestinationDirectory extract into this folder.
   * @param overwriteExistingFiles if {@code true}, existing files on disk will be overwritten.
   */
  public UnzipStep(
      String pathToZipFile,
      String pathToDestinationDirectory,
      boolean overwriteExistingFiles) {
    this.pathToZipFile = Preconditions.checkNotNull(pathToZipFile);
    this.pathToDestinationDirectory = Preconditions.checkNotNull(pathToDestinationDirectory);
    this.overwriteExistingFiles = overwriteExistingFiles;
  }

  @Override
  public String getShortName() {
    return "unzip";
  }

  @Override
  public int execute(ExecutionContext context) {
    try {
      Unzip.extractZipFile(pathToZipFile,
          pathToDestinationDirectory,
          overwriteExistingFiles);
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    }
    return 0;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add("unzip");

    Verbosity verbosity = context.getVerbosity();
    if (!verbosity.shouldUseVerbosityFlagIfAvailable()) {
      if (verbosity.shouldPrintStandardInformation()) {
        args.add("-q");
      } else {
        args.add("-qq");
      }
    }

    // overwrite existing files without prompting
    if (overwriteExistingFiles) {
      args.add("-o");
    }

    // output directory
    args.add("-d").add(pathToDestinationDirectory);

    // file to unzip
    args.add(pathToZipFile);

    return Joiner.on(" ").join(args.build());
  }
}
