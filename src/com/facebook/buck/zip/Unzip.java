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

import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.io.MorePosixFilePermissions;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteStreams;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.zip.ZipError;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

public class Unzip {

  /** Utility class: do not instantiate. */
  private Unzip() {}

  public enum ExistingFileMode {
    OVERWRITE,
    OVERWRITE_AND_CLEAN_DIRECTORIES,
  }

  private static void writeZipContents(
      ZipFile zip, ZipArchiveEntry entry, ProjectFilesystem filesystem, Path target)
      throws IOException {
    // Write file
    try (InputStream is = zip.getInputStream(entry)) {
      if (entry.isUnixSymlink()) {
        filesystem.createSymLink(
            target,
            filesystem.getPath(new String(ByteStreams.toByteArray(is), Charsets.UTF_8)),
            /* force */ true);
      } else {
        try (OutputStream out = filesystem.newFileOutputStream(target)) {
          ByteStreams.copy(is, out);
        }
      }
    }

    // restore mtime for the file
    filesystem.resolve(target).toFile().setLastModified(entry.getTime());

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
      MoreFiles.makeExecutable(filesystem.resolve(target));
    }
  }

  private static boolean isTopLevel(Path path, SortedMap<Path, ZipArchiveEntry> pathMap) {
    for (Path p = path.getParent(); p != null; p = p.getParent()) {
      if (pathMap.containsKey(p)) {
        return false;
      }
    }
    return true;
  }

  private static void fillIntermediatePaths(Path path, SortedMap<Path, ZipArchiveEntry> pathMap) {
    for (Path p = path.getParent(); p != null; p = p.getParent()) {
      if (pathMap.containsKey(p)) {
        break;
      }
      pathMap.put(p, new ZipArchiveEntry(p + "/"));
    }
  }

  /** Unzips a file to a destination and returns the paths of the written files. */
  public static ImmutableList<Path> extractZipFile(
      Path zipFile,
      ProjectFilesystem filesystem,
      Path relativePath,
      ExistingFileMode existingFileMode)
      throws IOException {

    // We want to remove stale contents of directories listed in zipFile, but avoid deleting and
    // re-creating any directories that already exist. We *also* want to avoid a full recursive
    // scan of listed directories, since that's almost as slow as deleting. So we preprocess the
    // contents of zipFile and then scan the existing filesystem to remove stale artifacts.

    ImmutableList.Builder<Path> filesWritten = ImmutableList.builder();
    try (ZipFile zip = new ZipFile(zipFile.toFile())) {
      // Get the list of paths in zipFile.  Keep them sorted by path, so dirs come before contents.
      SortedMap<Path, ZipArchiveEntry> pathMap = new TreeMap<>();
      for (ZipArchiveEntry entry : Collections.list(zip.getEntries())) {
        Path target = relativePath.resolve(entry.getName()).normalize();
        pathMap.put(target, entry);
      }
      // A zip file isn't required to list intermediate paths (e.g., it can contain "foo/" and
      // "foo/bar/baz"), but we need to know not to delete those intermediates, so fill them in.
      for (SortedMap.Entry<Path, ZipArchiveEntry> p : new ArrayList<>(pathMap.entrySet())) {
        if (!isTopLevel(p.getKey(), pathMap)) {
          fillIntermediatePaths(p.getKey(), pathMap);
        }
      }
      for (SortedMap.Entry<Path, ZipArchiveEntry> p : pathMap.entrySet()) {
        Path target = p.getKey();
        ZipArchiveEntry entry = p.getValue();
        if (entry.isDirectory()) {
          if (filesystem.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            // We have a pre-existing directory: delete its contents if they aren't in the zip.
            if (existingFileMode == ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES) {
              for (File f : filesystem.listFiles(target)) {
                if (!pathMap.containsKey(f.toPath())) {
                  filesystem.deleteRecursivelyIfExists(f.toPath());
                }
              }
            }
          } else if (filesystem.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            filesystem.deleteFileAtPath(target);
            filesystem.mkdirs(target);
          } else {
            filesystem.mkdirs(target);
          }
        } else {
          if (filesystem.isFile(target, LinkOption.NOFOLLOW_LINKS)) { // NOPMD for clarity
            // pass
          } else if (filesystem.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            filesystem.deleteRecursivelyIfExists(target);
          } else {
            filesystem.createParentDirs(target);
          }
          filesWritten.add(target);
          writeZipContents(zip, entry, filesystem, target);
        }
      }
    }
    return filesWritten.build();
  }

  public static ImmutableList<Path> extractZipFile(
      Path zipFile, ProjectFilesystem filesystem, ExistingFileMode existingFileMode)
      throws IOException {
    return extractZipFile(zipFile, filesystem, filesystem.getPath(""), existingFileMode);
  }

  public static ImmutableList<Path> extractZipFile(
      Path zipFile, final Path destination, ExistingFileMode existingFileMode)
      throws InterruptedException, IOException {
    // Create output directory if it does not exist
    Files.createDirectories(destination);
    return extractZipFile(
            zipFile,
            new ProjectFilesystem(destination),
            destination.getFileSystem().getPath(""),
            existingFileMode)
        .stream()
        .map(input -> destination.resolve(input).toAbsolutePath())
        .collect(MoreCollectors.toImmutableList());
  }

  public static ImmutableSet<Path> getZipMembers(Path archiveAbsolutePath) throws IOException {
    try (FileSystem zipFs = FileSystems.newFileSystem(archiveAbsolutePath, null)) {
      Path root = Iterables.getOnlyElement(zipFs.getRootDirectories());
      return Files.walk(root)
          .filter(path -> !Files.isDirectory(path))
          .map(root::relativize)
          .map(path -> Paths.get(path.toString())) // Clear the filesystem from the path
          .collect(MoreCollectors.toImmutableSet());
    } catch (ZipError error) {
      // For some reason the zip filesystem support throws an error when an IOException would do
      // just as well.
      throw new IOException(
          String.format("Could not read %s because of %s", archiveAbsolutePath, error.toString()),
          error);
    }
  }
}
