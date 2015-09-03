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

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;

import java.io.IOException;
import java.nio.file.Path;

public class UnzipStep implements Step {
  private static final Logger LOG = Logger.get(UnzipStep.class);

  private final ProjectFilesystem filesystem;
  private final Path zipFile;
  private final Path destinationDirectory;

  public UnzipStep(ProjectFilesystem filesystem, Path zipFile, Path destinationDirectory) {
    this.filesystem = filesystem;
    this.zipFile = zipFile;
    this.destinationDirectory = destinationDirectory;
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    Path zip = filesystem.getPathForRelativeExistingPath(zipFile).toAbsolutePath();
    Path out = filesystem.getPathForRelativeExistingPath(destinationDirectory).toAbsolutePath();

    try {
      Unzip.extractZipFile(zip, out, Unzip.ExistingFileMode.OVERWRITE);
    } catch (IOException e) {
      LOG.warn(e, "Unable to unpack zip: %s", zipFile);
      return 1;
    }
    return 0;
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
