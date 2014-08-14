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
  public static ImmutableList<Path> extractZipFile(
      Path zipFile,
      Path destination,
      boolean overwriteExistingFiles) throws IOException {
    // Create output directory if it does not exist
    Files.createDirectories(destination);

    ImmutableList.Builder<Path> filesWritten = ImmutableList.builder();
    try (ZipFile zip = new ZipFile(zipFile.toFile())) {
      Enumeration<ZipArchiveEntry> entries = zip.getEntries();
      while (entries.hasMoreElements()) {
        ZipArchiveEntry entry = entries.nextElement();
        String fileName = entry.getName();
        Path target = destination.resolve(fileName);
        if (Files.exists(target) && !overwriteExistingFiles) {
          continue;
        }

        // TODO(mbolin): Keep track of which directories have already been written to avoid
        // making unnecessary Files.createDirectories() calls. In practice, a single zip file will
        // have many entries in the same directory.

        if (entry.isDirectory()) {
          // Create the directory and all its parent directories
          Files.createDirectories(target);
        } else {
          // Create parent folder
          Files.createDirectories(target.getParent());

          filesWritten.add(target);
          // Write file
          try (FileOutputStream out = new FileOutputStream(target.toFile())) {
            ByteStreams.copy(zip.getInputStream(entry), out);
          }

          // TODO(simons): Implement what the comment below says we should do.
          //
          // Sets the file permissions of the output file given the information in {@code entry}'s
          // extra data field. According to the docs at
          // http://www.opensource.apple.com/source/zip/zip-6/unzip/unzip/proginfo/extra.fld there
          // are two extensions that might support file permissions: Acorn and ASi UNIX. We shall
          // assume that inputs are not from an Acorn SparkFS. The relevant section from the docs:
          //
          // <pre>
          //    The following is the layout of the ASi extra block for Unix.  The
          //    local-header and central-header versions are identical.
          //    (Last Revision 19960916)
          //
          //    Value         Size        Description
          //    -----         ----        -----------
          //   (Unix3) 0x756e        Short       tag for this extra block type ("nu")
          //   TSize         Short       total data size for this block
          //   CRC           Long        CRC-32 of the remaining data
          //   Mode          Short       file permissions
          //   SizDev        Long        symlink'd size OR major/minor dev num
          //   UID           Short       user ID
          //   GID           Short       group ID
          //   (var.)        variable    symbolic link filename
          //
          //   Mode is the standard Unix st_mode field from struct stat, containing
          //   user/group/other permissions, setuid/setgid and symlink info, etc.
          // </pre>
          //
          // From the stat man page, we see that the following mask values are defined for the file
          // permissions component of the st_mode field:
          //
          // <pre>
          //   S_ISUID   0004000   set-user-ID bit
          //   S_ISGID   0002000   set-group-ID bit (see below)
          //   S_ISVTX   0001000   sticky bit (see below)
          //
          //   S_IRWXU     00700   mask for file owner permissions
          //
          //   S_IRUSR     00400   owner has read permission
          //   S_IWUSR     00200   owner has write permission
          //   S_IXUSR     00100   owner has execute permission
          //
          //   S_IRWXG     00070   mask for group permissions
          //   S_IRGRP     00040   group has read permission
          //   S_IWGRP     00020   group has write permission
          //   S_IXGRP     00010   group has execute permission
          //
          //   S_IRWXO     00007   mask for permissions for others
          //   (not in group)
          //   S_IROTH     00004   others have read permission
          //   S_IWOTH     00002   others have write permission
          //   S_IXOTH     00001   others have execute permission
          // </pre>
          //
          // For the sake of our own sanity, we're going to assume that no-one is using symlinks,
          // but we'll check and throw if they are.
          //
          // Before we do anything, we should check the header ID. Pfft!
          //
          // Having jumped through all these hoops, it turns out that InfoZIP's "unzip" store the
          // values in the external file attributes of a zip entry (found in the zip's central
          // directory) assuming that the OS creating the zip was one of an enormous list that
          // includes UNIX but not Windows, it first searches for the extra fields, and if not found
          // falls through to a code path that supports MS-DOS and which stores the UNIX file
          // attributes in the upper 16 bits of the external attributes field.
          //
          // We'll support neither approach fully, but we encode whether this file was executable
          // via storing 0100 in the fields that are typically used by zip implementations to store
          // POSIX permissions. If we find it was executable, use the platform independent java
          // interface to make this unpacked file executable.

          Set<PosixFilePermission> permissions =
              MorePosixFilePermissions.fromMode(entry.getExternalAttributes() >> 16);
          if (permissions.contains(PosixFilePermission.OWNER_EXECUTE)) {
            // TODO(user): Currently, at least for POSIX filesystems, this just
            // adds execute permissions for the owner.  However, it might be nice to
            // add these for all roles (e.g. owner, group, other) that already have
            // read perms (e.g. rw-r----- => rwx-r-x--, instead of rw-r----- =>
            // rwxr-----).
            target.toFile().setExecutable(/* executable */ true, /* ownerOnly */ true);
          }

        }
      }
    }
    return filesWritten.build();
  }

}
