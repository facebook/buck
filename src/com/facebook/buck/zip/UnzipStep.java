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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class UnzipStep implements Step {

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
  public String getShortName() {
    return "unzip";
  }

  @Override
  public int execute(ExecutionContext context) {
    try {
      extractZipFile(pathToZipFile,
          pathToDestinationDirectory,
          filesToExtract,
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

    // specific files within the archive to unzip -- if empty, extract all
    args.addAll(filesToExtract);

    return Joiner.on(" ").join(args.build());
  }

  @VisibleForTesting
  static void extractZipFile(String zipFile,
                             String destination,
                             ImmutableSet<String> filesToExtract,
                             boolean overwriteExistingFiles) throws IOException {
    // Create output directory is not exists
    File folder = new File(destination);
    // TODO UnzipStep could be a CompositeStep with a MakeCleanDirectoryStep for the output dir.
    if (!folder.exists() && !folder.mkdirs()) {
      throw new IOException(String.format("Folder %s could not be created.", folder.toString()));
    }

    try (ZipInputStream zip = new ZipInputStream(new FileInputStream(zipFile))) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        String fileName = entry.getName();
        // If filesToExtract is not empty, check if current entry is contained.
        if (!filesToExtract.isEmpty() && !filesToExtract.contains(fileName)) {
          continue;
        }
        File target = new File(folder, fileName);
        if (target.exists() && !overwriteExistingFiles) {
          continue;
        }
        if (entry.isDirectory()) {
          // Create the directory and all its parent directories
          if (!target.mkdirs()) {
            throw new IOException(String.format("Folder %s could not be created.",
                target.toString()));
          }
        } else {
          // Create parent folder
          File parentFolder = target.getParentFile();
          if (!parentFolder.exists() && !parentFolder.mkdirs()) {
            throw new IOException(String.format("Folder %s could not be created.",
                parentFolder.toString()));
          }
          // Write file
          try (FileOutputStream out = new FileOutputStream(target)) {
            ByteStreams.copy(zip, out);
          }
        }
      }
    }
  }
}
