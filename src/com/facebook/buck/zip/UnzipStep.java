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

package com.facebook.buck.zip;

import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.zip.Unzip;
import java.io.IOException;
import java.nio.file.Path;

public class UnzipStep implements Step {

  private final ProjectFilesystem filesystem;
  private final Path zipFile;
  private final Path destinationDirectory;

  public UnzipStep(ProjectFilesystem filesystem, Path zipFile, Path destinationDirectory) {
    this.filesystem = filesystem;
    this.zipFile = zipFile;
    this.destinationDirectory = destinationDirectory;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    Path zip =
        zipFile.isAbsolute()
            ? zipFile
            : filesystem.getPathForRelativeExistingPath(zipFile).toAbsolutePath();
    Path out = filesystem.getPathForRelativeExistingPath(destinationDirectory).toAbsolutePath();

    Unzip.extractZipFile(
        context.getProjectFilesystemFactory(), zip, out, Unzip.ExistingFileMode.OVERWRITE);
    return StepExecutionResult.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "unzip";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format(
        "unzip %s -d %s",
        MorePaths.pathWithUnixSeparators(filesystem.resolve(zipFile)),
        MorePaths.pathWithUnixSeparators(filesystem.resolve(destinationDirectory)));
  }
}
