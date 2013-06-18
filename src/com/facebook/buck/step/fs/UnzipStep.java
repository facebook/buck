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

package com.facebook.buck.step.fs;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.Set;

public class UnzipStep extends ShellStep {

  private final String pathToZipFile;
  private final String pathToDestinationDirectory;
  private final boolean overwriteExistingFiles;
  private final ImmutableSet<String> filesToExtract;

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
    this(
        pathToZipFile,
        pathToDestinationDirectory,
        overwriteExistingFiles,
        ImmutableSet.<String>of());
  }

  /**
   * Creates an {@link UnzipStep} that extracts only specific files from a .zip archive.
   * Example:
   * <pre>
   *   new UnzipCommand("my.apk", "/my/dir",
   *        true, ImmutableSet.of("resources.arsc"))
   * </pre>
   * @param pathToZipFile archive to unzip.
   * @param pathToDestinationDirectory extract into this folder.
   * @param overwriteExistingFiles if {@code true}, existing files on disk will be overwritten.
   * @param filesToExtract only these files will be extracted. If this is the empty set, the entire
   *    archive will be extracted.
   */
  public UnzipStep(
      String pathToZipFile,
      String pathToDestinationDirectory,
      boolean overwriteExistingFiles,
      Set<String> filesToExtract) {
    this.pathToZipFile = Preconditions.checkNotNull(pathToZipFile);
    this.pathToDestinationDirectory = Preconditions.checkNotNull(pathToDestinationDirectory);
    this.overwriteExistingFiles = overwriteExistingFiles;
    this.filesToExtract = ImmutableSet.copyOf(Preconditions.checkNotNull(filesToExtract));
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
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

    // specific files within the archive to unzip -- if empty, extract all
    args.addAll(filesToExtract);

    return args.build();
  }

  @Override
  public String getShortName(ExecutionContext context) {
    return getDescription(context);
  }

}
