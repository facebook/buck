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

package com.facebook.buck.util.unarchive;

import com.facebook.buck.io.file.MorePosixFilePermissions;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;

/** Utility class to extract a .tar.* file */
public class Untar extends Unarchiver {

  private final Optional<String> compressorType;

  private Untar(Optional<String> compressorType) {
    this.compressorType = compressorType;
  }

  public static Untar tarUnarchiver() {
    return new Untar(Optional.empty());
  }

  public static Untar bzip2Unarchiver() {
    return new Untar(Optional.of(CompressorStreamFactory.BZIP2));
  }

  public static Untar gzipUnarchiver() {
    return new Untar(Optional.of(CompressorStreamFactory.GZIP));
  }

  public static Untar xzUnarchiver() {
    return new Untar(Optional.of(CompressorStreamFactory.XZ));
  }

  public static Untar zstdUnarchiver() {
    return new Untar(Optional.of(CompressorStreamFactory.ZSTANDARD));
  }

  @Override
  public ImmutableSet<Path> extractArchive(
      Path archiveFile,
      ProjectFilesystem filesystem,
      Path filesystemRelativePath,
      Optional<Path> stripPath,
      ExistingFileMode existingFileMode)
      throws IOException {
    return extractArchive(
        archiveFile,
        filesystem,
        filesystemRelativePath,
        stripPath,
        existingFileMode,
        Platform.detect() == Platform.WINDOWS);
  }

  @VisibleForTesting
  ImmutableSet<Path> extractArchive(
      Path archiveFile,
      ProjectFilesystem filesystem,
      Path filesystemRelativePath,
      Optional<Path> stripPath,
      ExistingFileMode existingFileMode,
      boolean writeSymlinksAfterCreatingFiles)
      throws IOException {

    ImmutableSet.Builder<Path> paths = ImmutableSet.builder();
    HashSet<Path> dirsToTidy = new HashSet<>();
    TreeMap<Path, Long> dirCreationTimes = new TreeMap<>();
    DirectoryCreator creator = new DirectoryCreator(filesystem);

    // On windows, we create hard links instead of symlinks. This is fine, but the
    // destination file may not exist yet, which is an error. So, just hold onto the paths until
    // all files are extracted, and /then/ try to do the links
    Map<Path, Path> windowsSymlinkMap = new HashMap<>();

    try (TarArchiveInputStream archiveStream = getArchiveInputStream(archiveFile)) {
      TarArchiveEntry entry;
      while ((entry = archiveStream.getNextTarEntry()) != null) {
        Path destFile = Paths.get(entry.getName());
        Path destPath;
        if (stripPath.isPresent()) {
          if (!destFile.startsWith(stripPath.get())) {
            continue;
          }
          destPath =
              filesystemRelativePath.resolve(stripPath.get().relativize(destFile)).normalize();
        } else {
          destPath = filesystemRelativePath.resolve(destFile).normalize();
        }

        if (entry.isDirectory()) {
          dirsToTidy.add(destPath);
          mkdirs(creator, destPath);
          dirCreationTimes.put(destPath, entry.getModTime().getTime());
        } else if (entry.isSymbolicLink()) {
          if (writeSymlinksAfterCreatingFiles) {
            recordSymbolicLinkForWindows(creator, destPath, entry, windowsSymlinkMap);
          } else {
            writeSymbolicLink(creator, destPath, entry);
          }
          paths.add(destPath);
          setAttributes(filesystem, destPath, entry);
        } else if (entry.isFile()) {
          writeFile(creator, archiveStream, destPath);
          paths.add(destPath);
          setAttributes(filesystem, destPath, entry);
        }
      }

      writeWindowsSymlinks(creator, windowsSymlinkMap);
    } catch (CompressorException e) {
      throw new IOException(
          String.format("Could not get decompressor for archive at %s", archiveFile), e);
    }

    setDirectoryModificationTimes(filesystem, dirCreationTimes);

    ImmutableSet<Path> filePaths = paths.build();
    if (existingFileMode == ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES) {
      // Clean out directories of files that were not in the archive
      tidyDirectories(filesystem, dirsToTidy, filePaths);
    }
    return filePaths;
  }

  private TarArchiveInputStream getArchiveInputStream(Path tarFile)
      throws IOException, CompressorException {
    BufferedInputStream inputStream = new BufferedInputStream(Files.newInputStream(tarFile));
    if (compressorType.isPresent()) {
      return new TarArchiveInputStream(
          new CompressorStreamFactory()
              .createCompressorInputStream(compressorType.get(), inputStream));
    } else {
      return new TarArchiveInputStream(inputStream);
    }
  }

  /** Cleans up any files that exist on the filesystem that were not in the archive */
  private void tidyDirectories(
      ProjectFilesystem filesystem, Set<Path> dirsToTidy, ImmutableSet<Path> createdFiles)
      throws IOException {
    for (Path directory : dirsToTidy) {
      for (Path foundFile : filesystem.getDirectoryContents(directory)) {
        if (!createdFiles.contains(foundFile) && !dirsToTidy.contains(foundFile)) {
          filesystem.deleteRecursivelyIfExists(foundFile);
        }
      }
    }
  }

  /** Create a director on the filesystem */
  private void mkdirs(DirectoryCreator creator, Path target) throws IOException {
    ProjectFilesystem filesystem = creator.getFilesystem();
    if (filesystem.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
      creator.recordPath(target);
    } else {
      creator.forcefullyCreateDirs(target);
    }
  }

  /** Prepares to write out a file. This deletes existing files/directories */
  private void prepareForFile(DirectoryCreator creator, Path target) throws IOException {
    ProjectFilesystem filesystem = creator.getFilesystem();
    if (filesystem.isFile(target, LinkOption.NOFOLLOW_LINKS)) {
      return;
    } else if (filesystem.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      filesystem.deleteRecursivelyIfExists(target);
    } else if (target.getParent() != null) {
      creator.forcefullyCreateDirs(target.getParent());
    }
  }

  /** Writes a regular file from an archive */
  private void writeFile(DirectoryCreator creator, TarArchiveInputStream inputStream, Path target)
      throws IOException {
    ProjectFilesystem filesystem = creator.getFilesystem();
    prepareForFile(creator, target);

    try (OutputStream outputStream = filesystem.newFileOutputStream(target)) {
      ByteStreams.copy(inputStream, outputStream);
    }
  }

  /** Writes out a symlink from an archive */
  private void writeSymbolicLink(DirectoryCreator creator, Path target, TarArchiveEntry entry)
      throws IOException {
    prepareForFile(creator, target);
    creator.getFilesystem().createSymLink(target, Paths.get(entry.getLinkName()), true);
  }

  /**
   * Cleans up the destination for the symlink, and symlink file -> symlink target is recorded in
   * {@code windowsSymlinkMap}
   */
  private void recordSymbolicLinkForWindows(
      DirectoryCreator creator,
      Path destPath,
      TarArchiveEntry entry,
      Map<Path, Path> windowsSymlinkMap)
      throws IOException {
    prepareForFile(creator, destPath);
    Path linkPath = Paths.get(entry.getLinkName());
    if (destPath.isAbsolute()) {
      windowsSymlinkMap.put(destPath, linkPath);
    } else {
      // Symlink might be '../foo/Bar', so make sure we resolve relative to the src file
      // We make them relative to the file again when we write them out
      Path destinationRelativeTargetPath = destPath.getParent().resolve(linkPath).normalize();
      windowsSymlinkMap.put(destPath, destinationRelativeTargetPath);
    }
  }

  /**
   * Writes out a single symlink, and any symlinks that may recursively need to exist before writing
   *
   * <p>That is, if foo1/bar should point to foo2/bar, but foo2/bar is also in the map pointing to
   * foo3/bar, then foo2/bar will be writtten first, then foo1/bar
   *
   * @param creator Creator with the right filesystem to create symlinks
   * @param windowsSymlinkMap The map of paths to their target. NOTE: This method modifies the map
   *     as it traverses, removing files that have been written out
   * @param linkFilePath The link file that will actually be created
   * @param target What the link should point to
   * @throws IOException
   */
  private void writeWindowsSymlink(
      DirectoryCreator creator, Map<Path, Path> windowsSymlinkMap, Path linkFilePath, Path target)
      throws IOException {
    if (windowsSymlinkMap.containsKey(target)) {
      writeWindowsSymlink(creator, windowsSymlinkMap, target, windowsSymlinkMap.get(target));
    }
    Path relativeTargetPath = linkFilePath.getParent().relativize(target);
    creator.getFilesystem().createSymLink(linkFilePath, relativeTargetPath, true);
    windowsSymlinkMap.remove(linkFilePath);
  }

  /**
   * Writes out symlinks on windows. This is necessary because we use hardlinks on windows, and the
   * target files have to exist before links can be written (unlike symlinks on posix platforms)
   *
   * @param creator Creator with the right filesystem to create symlinks
   * @param windowsSymlinkMap The map of paths to their target. NOTE: This method modifies the map
   *     as it traverses, removing files that have been written out
   * @throws IOException
   */
  private void writeWindowsSymlinks(DirectoryCreator creator, Map<Path, Path> windowsSymlinkMap)
      throws IOException {
    ImmutableSet<Path> linkFilePaths = ImmutableSet.copyOf(windowsSymlinkMap.keySet());

    for (Path linkFilePath : linkFilePaths) {
      // We're going to delete as we go along, so keys need to be separate from the map itself
      if (windowsSymlinkMap.containsKey(linkFilePath)) {
        writeWindowsSymlink(
            creator, windowsSymlinkMap, linkFilePath, windowsSymlinkMap.get(linkFilePath));
      }
    }
  }

  /** Sets the modification time and the execution bit on a file */
  private void setAttributes(ProjectFilesystem filesystem, Path path, TarArchiveEntry entry) {
    File file = filesystem.getRootPath().resolve(path).toFile();
    file.setLastModified(entry.getModTime().getTime());
    Set<PosixFilePermission> posixPermissions = MorePosixFilePermissions.fromMode(entry.getMode());
    if (posixPermissions.contains(PosixFilePermission.OWNER_EXECUTE)) {
      file.setExecutable(true, true);
    }
  }

  /** Set the modification times on directories that were directly specified in the archive */
  private void setDirectoryModificationTimes(
      ProjectFilesystem filesystem, NavigableMap<Path, Long> dirToTime) {
    for (Map.Entry<Path, Long> pathAndTime : dirToTime.descendingMap().entrySet()) {
      File file = filesystem.getRootPath().resolve(pathAndTime.getKey()).toFile();
      file.setLastModified(pathAndTime.getValue());
    }
  }
}
