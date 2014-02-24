/*
 * Copyright 2013-present Facebook, Inc.
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

import com.google.common.io.ByteStreams;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Unzip {

  /** Utility class: do not instantiate. */
  private Unzip() {}

  public static void extractZipFile(String zipFile,
      String destination,
      boolean overwriteExistingFiles) throws IOException {
    // Create output directory if it does not exist
    File folder = new File(destination);
    // TODO(mbolin): UnzipStep could be a CompositeStep with a MakeCleanDirectoryStep for the output
    // dir.
    Files.createDirectories(folder.toPath());

    try (ZipInputStream zip = new ZipInputStream(new FileInputStream(zipFile))) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        String fileName = entry.getName();
        File target = new File(folder, fileName);
        if (target.exists() && !overwriteExistingFiles) {
          continue;
        }

        // TODO(mbolin): Keep track of which directories have already been written to avoid
        // making unnecessary Files.createDirectories() calls. In practice, a single zip file will
        // have many entries in the same directory.

        if (entry.isDirectory()) {
          // Create the directory and all its parent directories
          Files.createDirectories(target.toPath());
        } else {
          // Create parent folder
          Files.createDirectories(target.toPath().getParent());
          // Write file
          try (FileOutputStream out = new FileOutputStream(target)) {
            ByteStreams.copy(zip, out);
          }
        }
      }
    }
  }

}
