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

import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.unarchive.ArchiveFormat;
import java.nio.file.Path;
import java.util.Optional;

/** A step that extracts zip archives */
public class UnzipStep extends UnarchiveStep {

  /**
   * Create an instance of UnzipStep
   *
   * @param filesystem The filesystem that the archive will be extracted into
   * @param zipFile The path to the file to extract
   * @param destinationDirectory The directory to extract files into
   * @param stripPrefix If present, strip this prefix from paths inside of the archive
   */
  public UnzipStep(
      ProjectFilesystem filesystem,
      Path zipFile,
      Path destinationDirectory,
      Optional<Path> stripPrefix) {
    super(ArchiveFormat.ZIP, filesystem, zipFile, destinationDirectory, stripPrefix);
  }

  @Override
  public String getShortName() {
    return "unzip";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format(
        "unzip %s -d %s",
        MorePaths.pathWithUnixSeparators(filesystem.resolve(archiveFile)),
        MorePaths.pathWithUnixSeparators(filesystem.resolve(destinationDirectory)));
  }
}
