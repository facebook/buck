/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.zip;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.zip.RepackZipEntries;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;

/**
 * A command that creates a copy of a ZIP archive, making sure that certain user-specified entries
 * are packed with a certain compression level.
 *
 * <p>Can be used, for instance, to force the resources.arsc file in an Android .apk to be
 * compressed.
 */
public class RepackZipEntriesStep implements Step {

  private final ProjectFilesystem filesystem;
  private final Path inputPath;
  private final Path outputPath;
  private final ImmutableSet<String> entries;
  private final ZipCompressionLevel compressionLevel;

  /**
   * Creates a {@link RepackZipEntriesStep}. A temporary directory will be created and used to
   * extract entries. Entries will be packed with the maximum compression level.
   *
   * @param inputPath input archive
   * @param outputPath destination archive
   * @param entries files to repack (e.g. {@code ImmutableSet.of("resources.arsc")})
   */
  public RepackZipEntriesStep(
      ProjectFilesystem filesystem, Path inputPath, Path outputPath, ImmutableSet<String> entries) {
    this(filesystem, inputPath, outputPath, entries, ZipCompressionLevel.MAX);
  }

  /**
   * Creates a {@link RepackZipEntriesStep}.
   *
   * @param inputPath input archive
   * @param outputPath destination archive
   * @param entries files to repack (e.g. {@code ImmutableSet.of("resources.arsc")})
   * @param compressionLevel the level of compression to use
   */
  public RepackZipEntriesStep(
      ProjectFilesystem filesystem,
      Path inputPath,
      Path outputPath,
      ImmutableSet<String> entries,
      ZipCompressionLevel compressionLevel) {
    this.filesystem = filesystem;
    this.inputPath = inputPath;
    this.outputPath = outputPath;
    this.entries = entries;
    this.compressionLevel = compressionLevel;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context) throws IOException {
    Path inputFile = filesystem.getPathForRelativePath(inputPath);
    Path outputFile = filesystem.getPathForRelativePath(outputPath);
    RepackZipEntries.repack(inputFile, outputFile, entries, compressionLevel);

    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "repack zip";
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    return String.format("repack %s in %s", inputPath, outputPath);
  }
}
