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

/** A step that extracts tar archives */
public class UntarStep extends UnarchiveStep {

  /**
   * Create an instance of UntarStep
   *
   * @param filesystem The filesystem that the archive will be extracted into
   * @param archiveFile The path to the file to extract
   * @param destinationDirectory The directory to extract files into
   * @param stripPrefix If present, strip this prefix from paths inside of the archive
   * @param format The format to extract
   * @throws RuntimeException if a non-tar format is provided
   */
  public UntarStep(
      ProjectFilesystem filesystem,
      Path archiveFile,
      Path destinationDirectory,
      Optional<Path> stripPrefix,
      ArchiveFormat format) {
    super(format, filesystem, archiveFile, destinationDirectory, stripPrefix);

    switch (format) {
      case TAR:
      case TAR_BZ2:
      case TAR_GZ:
      case TAR_XZ:
        break;
      case ZIP:
      default:
        throw new RuntimeException(
            "Invalid archive format given to untar step. Got " + format.toString());
    }
  }

  @Override
  public String getShortName() {
    return "untar";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format(
        "tar xf %s -C %s",
        MorePaths.pathWithUnixSeparators(filesystem.resolve(archiveFile)),
        MorePaths.pathWithUnixSeparators(filesystem.resolve(destinationDirectory)));
  }
}
