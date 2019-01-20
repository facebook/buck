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

package com.facebook.buck.util.unarchive;

import com.facebook.buck.io.file.MorePosixFilePermissions;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.PatternsMatcher;
import com.google.common.base.Charsets;
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
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.zip.ZipError;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/** A simple utility class that extracts zip files */
public class Unzip extends Unarchiver {

  private void writeZipContents(
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

    Path filePath = filesystem.resolve(target);
    File file = filePath.toFile();

    // restore mtime for the file
    file.setLastModified(entry.getTime());

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
    if (permissions.contains(PosixFilePermission.OWNER_EXECUTE)
        && file.getCanonicalFile().exists()) {
      MostFiles.makeExecutable(filePath);
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

  private void extractFile(
      ImmutableSet.Builder<Path> filesWritten,
      ZipFile zip,
      DirectoryCreator creator,
      Path target,
      ZipArchiveEntry entry)
      throws IOException {
    ProjectFilesystem filesystem = creator.getFilesystem();
    if (filesystem.isFile(target, LinkOption.NOFOLLOW_LINKS)) { // NOPMD for clarity
      // pass
    } else if (filesystem.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      filesystem.deleteRecursivelyIfExists(target);
    } else if (target.getParent() != null) {
      creator.forcefullyCreateDirs(target.getParent());
    }
    filesWritten.add(target);
    writeZipContents(zip, entry, filesystem, target);
  }

  private void extractDirectory(
      ExistingFileMode existingFileMode,
      SortedMap<Path, ZipArchiveEntry> pathMap,
      DirectoryCreator creator,
      Path target)
      throws IOException {
    ProjectFilesystem filesystem = creator.getFilesystem();
    if (filesystem.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
      // We have a pre-existing directory: delete its contents if they aren't in the zip.
      if (existingFileMode == ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES) {
        for (Path path : filesystem.getDirectoryContents(target)) {
          if (!pathMap.containsKey(path)) {
            filesystem.deleteRecursivelyIfExists(path);
          }
        }
      }
    } else if (filesystem.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      filesystem.deleteFileAtPath(target);
      creator.mkdirs(target);
    } else {
      creator.forcefullyCreateDirs(target);
    }
  }

  /**
   * Get a listing of all files in a zip file that start with a prefix, ignore others
   *
   * @param zip The zip file to scan
   * @param relativePath The relative path where the extraction will be rooted
   * @param prefix The prefix that will be stripped off.
   * @return The list of paths in {@code zip} sorted by path so dirs come before contents. Prefixes
   *     are stripped from paths in the zip file, such that foo/bar/baz.txt with a prefix of foo/
   *     will be in the map at {@code relativePath}/bar/baz.txt
   */
  private static SortedMap<Path, ZipArchiveEntry> getZipFilePathsStrippingPrefix(
      ZipFile zip, Path relativePath, Path prefix, PatternsMatcher entriesToExclude) {
    SortedMap<Path, ZipArchiveEntry> pathMap = new TreeMap<>();
    for (ZipArchiveEntry entry : Collections.list(zip.getEntries())) {
      String entryName = entry.getName();
      if (entriesToExclude.matchesAny(entryName)) {
        continue;
      }
      Path entryPath = Paths.get(entryName);
      if (entryPath.startsWith(prefix)) {
        Path target = relativePath.resolve(prefix.relativize(entryPath)).normalize();
        pathMap.put(target, entry);
      }
    }
    return pathMap;
  }

  /**
   * Get a listing of all files in a zip file
   *
   * @param zip The zip file to scan
   * @param relativePath The relative path where the extraction will be rooted
   * @return The list of paths in {@code zip} sorted by path so dirs come before contents.
   */
  private static SortedMap<Path, ZipArchiveEntry> getZipFilePaths(
      ZipFile zip, Path relativePath, PatternsMatcher entriesToExclude) {
    SortedMap<Path, ZipArchiveEntry> pathMap = new TreeMap<>();
    for (ZipArchiveEntry entry : Collections.list(zip.getEntries())) {
      String entryName = entry.getName();
      if (entriesToExclude.matchesAny(entryName)) {
        continue;
      }
      Path target = relativePath.resolve(entryName).normalize();
      pathMap.put(target, entry);
    }
    return pathMap;
  }

  /** Unzips a file to a destination and returns the paths of the written files. */
  @Override
  public ImmutableSet<Path> extractArchive(
      Path archiveFile,
      ProjectFilesystem filesystem,
      Path relativePath,
      Optional<Path> stripPrefix,
      PatternsMatcher entriesToExclude,
      ExistingFileMode existingFileMode)
      throws IOException {

    // We want to remove stale contents of directories listed in {@code archiveFile}, but avoid
    // deleting and
    // re-creating any directories that already exist. We *also* want to avoid a full recursive
    // scan of listed directories, since that's almost as slow as deleting. So we preprocess the
    // contents of {@code archiveFile} and then scan the existing filesystem to remove stale
    // artifacts.

    ImmutableSet.Builder<Path> filesWritten = ImmutableSet.builder();
    try (ZipFile zip = new ZipFile(archiveFile.toFile())) {
      SortedMap<Path, ZipArchiveEntry> pathMap;
      if (stripPrefix.isPresent()) {
        pathMap =
            getZipFilePathsStrippingPrefix(zip, relativePath, stripPrefix.get(), entriesToExclude);
      } else {
        pathMap = getZipFilePaths(zip, relativePath, entriesToExclude);
      }
      // A zip file isn't required to list intermediate paths (e.g., it can contain "foo/" and
      // "foo/bar/baz"), but we need to know not to delete those intermediates, so fill them in.
      for (SortedMap.Entry<Path, ZipArchiveEntry> p : new ArrayList<>(pathMap.entrySet())) {
        if (!isTopLevel(p.getKey(), pathMap)) {
          fillIntermediatePaths(p.getKey(), pathMap);
        }
      }

      DirectoryCreator creator = new DirectoryCreator(filesystem);

      for (SortedMap.Entry<Path, ZipArchiveEntry> p : pathMap.entrySet()) {
        Path target = p.getKey();
        ZipArchiveEntry entry = p.getValue();
        if (entry.isDirectory()) {
          extractDirectory(existingFileMode, pathMap, creator, target);
        } else {
          extractFile(filesWritten, zip, creator, target, entry);
        }
      }
    }
    return filesWritten.build();
  }

  /**
   * Gets a set of files that are contained in an archive
   *
   * @param archiveAbsolutePath The absolute path to the archive
   * @return A set of files (not directories) that are contained in the zip file
   * @throws IOException If there is an error reading the archive
   */
  public static ImmutableSet<Path> getZipMembers(Path archiveAbsolutePath) throws IOException {
    try (FileSystem zipFs = FileSystems.newFileSystem(archiveAbsolutePath, null)) {
      Path root = Iterables.getOnlyElement(zipFs.getRootDirectories());
      return Files.walk(root)
          .filter(path -> !Files.isDirectory(path))
          .map(root::relativize)
          .map(path -> Paths.get(path.toString())) // Clear the filesystem from the path
          .collect(ImmutableSet.toImmutableSet());
    } catch (ZipError error) {
      // For some reason the zip filesystem support throws an error when an IOException would do
      // just as well.
      throw new IOException(
          String.format("Could not read %s because of %s", archiveAbsolutePath, error.toString()),
          error);
    }
  }
}
