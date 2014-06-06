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

import com.facebook.buck.util.MorePosixFilePermissions;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Enumeration;
import java.util.Set;

public class Unzip {

  /** Utility class: do not instantiate. */
  private Unzip() {}


  /**
   * Unzips a file to a destination and returns the paths of the written files.
   */
  public static ImmutableList<Path> extractZipFile(String zipFile,
      String destination,
      boolean overwriteExistingFiles) throws IOException {
    // Create output directory if it does not exist
    File folder = new File(destination);
    // TODO(mbolin): UnzipStep could be a CompositeStep with a MakeCleanDirectoryStep for the output
    // dir.
    Files.createDirectories(folder.toPath());

    ImmutableList.Builder<Path> filesWritten = ImmutableList.builder();
    try (ZipFile zip = new ZipFile(new File(zipFile))) {
      Enumeration<ZipArchiveEntry> entries = zip.getEntries();
      while (entries.hasMoreElements()) {
        ZipArchiveEntry entry = entries.nextElement();
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

          filesWritten.add(target.toPath());
          // Write file
          try (FileOutputStream out = new FileOutputStream(target)) {
            ByteStreams.copy(zip.getInputStream(entry), out);
          }

          // We encode whether this file was executable via storing 0100 in the fields
          // that are typically used by zip implementations to store POSIX permissions.
          // If we find it was executable, use the platform independent java interface
          // to make this unpacked file executable.
          Set<PosixFilePermission> permissions =
              MorePosixFilePermissions.fromMode(entry.getExternalAttributes() >> 16);
          if (permissions.contains(PosixFilePermission.OWNER_EXECUTE)) {
              // TODO(user): Currently, at least for POSIX filesystems, this just
              // adds execute permissions for the owner.  However, it might be nice to
              // add these for all roles (e.g. owner, group, other) that already have
              // read perms (e.g. rw-r----- => rwx-r-x--, instead of rw-r----- =>
              // rwxr-----).
              target.setExecutable(/* executable */ true, /* ownerOnly */ true);
          }

        }
      }
    }
    return filesWritten.build();
  }

}
