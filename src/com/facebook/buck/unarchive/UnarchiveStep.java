/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.unarchive;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.PatternsMatcher;
import com.facebook.buck.util.unarchive.ArchiveFormat;
import com.facebook.buck.util.unarchive.ExistingFileMode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** A step that extracts arbitrary archives */
public abstract class UnarchiveStep implements Step {

  private final ArchiveFormat format;
  protected final ProjectFilesystem filesystem;
  protected final Path archiveFile;
  protected final Path destinationDirectory;
  protected final PatternsMatcher entriesToExclude;
  private final Optional<Path> stripPrefix;

  /**
   * Create an instance of UnarchiveStep
   *
   * @param format The type of file that will be extracted
   * @param filesystem The filesystem that the archive will be extracted into
   * @param archiveFile The path to the file to extract
   * @param destinationDirectory The directory to extract files into
   * @param stripPrefix If present, strip this prefix from paths inside of the archive
   */
  public UnarchiveStep(
      ArchiveFormat format,
      ProjectFilesystem filesystem,
      Path archiveFile,
      Path destinationDirectory,
      Optional<Path> stripPrefix,
      PatternsMatcher entriesToExclude) {
    this.format = format;
    this.filesystem = filesystem;
    this.archiveFile = archiveFile;
    this.destinationDirectory = destinationDirectory;
    this.stripPrefix = stripPrefix;
    this.entriesToExclude = entriesToExclude;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    Path archive =
        archiveFile.isAbsolute()
            ? archiveFile
            : filesystem.getPathForRelativeExistingPath(archiveFile).toAbsolutePath();
    Path out = filesystem.getPathForRelativeExistingPath(destinationDirectory).toAbsolutePath();

    format
        .getUnarchiver()
        .extractArchive(
            context.getProjectFilesystemFactory(),
            archive,
            out,
            stripPrefix,
            entriesToExclude,
            ExistingFileMode.OVERWRITE);
    return StepExecutionResults.SUCCESS;
  }
}
