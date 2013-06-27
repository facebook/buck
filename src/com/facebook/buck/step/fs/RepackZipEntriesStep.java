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

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.CompositeStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;

import java.io.File;
import java.util.List;

import javax.annotation.Nullable;

/**
 * A command that creates a copy of a ZIP archive, making sure that certain user-specified entries
 * are packed with a certain compression level.
 *
 * Can be used, for instance, to force the resources.arsc file in an Android .apk to be compressed.
 */
public class RepackZipEntriesStep extends CompositeStep {

  private static List<ShellStep> createSubCommands(
      String inputFile,
      String outputFile,
      ImmutableSet<String> entries,
      int compressionLevel,
      @Nullable File workingDirectory) {

    if (workingDirectory == null) {
      workingDirectory = Files.createTempDir();
      workingDirectory.deleteOnExit();
    }

    // Extract the entries we want to repack.
    ShellStep unzip = new UnzipStep(
        inputFile,
        workingDirectory.getAbsolutePath(),
        true,
        entries);

    // Initialize destination archive with copy of source archive.
    ShellStep cp = new CopyStep(inputFile, outputFile);

    // Finally, update the entries in the destination archive, using compressionLevel.
    ShellStep zip = new ZipStep(
        ZipStep.Mode.ADD,
        new File(outputFile).getAbsolutePath(),
        entries,
        false /* junkPaths */,
        compressionLevel,
        workingDirectory.getAbsoluteFile());

    return ImmutableList.of(unzip, cp, zip);
  }

  /**
   * Creates a {@link RepackZipEntriesStep}.
   * @param inputFile input archive
   * @param outputFile destination archive
   * @param entries files to repack (e.g. {@code ImmutableSet.of("resources.arsc")})
   * @param compressionLevel 0 to 9
   * @param workingDirectory where to extract entries before repacking. A temporary directory will
   *     be created if this is {@code null}.
   */
  public RepackZipEntriesStep(
      String inputFile,
      String outputFile,
      ImmutableSet<String> entries,
      int compressionLevel,
      @Nullable File workingDirectory) {
    super(createSubCommands(inputFile, outputFile, entries, compressionLevel, workingDirectory));
  }


  /**
   * Creates a {@link RepackZipEntriesStep}. A temporary directory will be created and used
   * to extract entries. Entries will be packed with the maximum compression level.
   * @param inputFile input archive
   * @param outputFile destination archive
   * @param entries files to repack (e.g. {@code ImmutableSet.of("resources.arsc")})
   */
  public RepackZipEntriesStep(
      String inputFile,
      String outputFile,
      ImmutableSet<String> entries) {
    this(inputFile, outputFile, entries, ZipStep.MAX_COMPRESSION_LEVEL, null);
  }

}
