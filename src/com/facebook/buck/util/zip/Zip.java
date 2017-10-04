/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.util.zip;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;

public class Zip {

  private Zip() {}

  /**
   * Takes a sequence of paths relative to the project root and writes a zip file to {@code out}
   * with the contents and structure that matches that of the specified paths.
   */
  public static void create(
      ProjectFilesystem projectFilesystem, Collection<Path> pathsToIncludeInZip, Path out)
      throws IOException {
    try (CustomZipOutputStream zip = ZipOutputStreams.newOutputStream(out)) {
      for (Path path : pathsToIncludeInZip) {
        boolean isDirectory = projectFilesystem.isDirectory(path);
        CustomZipEntry entry = new CustomZipEntry(path, isDirectory);

        // We want deterministic ZIPs, so avoid mtimes.
        entry.setFakeTime();

        entry.setExternalAttributes(projectFilesystem.getFileAttributesForZipEntry(path));

        zip.putNextEntry(entry);
        if (!isDirectory) {
          try (InputStream input = projectFilesystem.newFileInputStream(path)) {
            ByteStreams.copy(input, zip);
          }
        }
        zip.closeEntry();
      }
    }
  }
}
