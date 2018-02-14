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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Simple interface to extract archives of varying types */
public abstract class Unarchiver {

  /**
   * Extract a given archive to a destination
   *
   * @param archiveFile The path to the archive
   * @param filesystem The filesystem that will be extracted into
   * @param relativePath The path relative to the filesystem to extract files into
   * @param stripPrefix If provided, only files under this prefix will be extracted. This prefix
   *     prefix will also be removed from the destination path. e.g. foo.tar.gz/foo/bar/baz with a
   *     prefix of foo will extract bar/baz into the destination directory. If not provided, no
   *     stripping is done.
   * @param existingFileMode How to handle existing files
   * @return A list of paths to files that were created (not directories)
   * @throws IOException If the archive could not be extracted for any reason
   */
  public abstract ImmutableList<Path> extractArchive(
      Path archiveFile,
      ProjectFilesystem filesystem,
      Path relativePath,
      Optional<Path> stripPrefix,
      ExistingFileMode existingFileMode)
      throws IOException;

  /**
   * Extract a given archive to a destination
   *
   * @param archiveFile The path to the archive
   * @param filesystem The filesystem that will be extracted into
   * @param existingFileMode How to handle existing files
   * @return A list of paths to files that were created (not directories)
   * @throws IOException If the archive could not be extracted for any reason
   */
  public ImmutableList<Path> extractArchive(
      Path archiveFile, ProjectFilesystem filesystem, ExistingFileMode existingFileMode)
      throws IOException {
    return extractArchive(
        archiveFile, filesystem, filesystem.getPath(""), Optional.empty(), existingFileMode);
  }

  /**
   * Extract a given archive to a specific directory
   *
   * @param projectFilesystemFactory A factory that creates filesystems
   * @param archiveFile The path to the archive
   * @param destination The destination directory where the archive should be extracted to
   * @param existingFileMode How to handle existing files
   * @return A list of paths to files that were created (not directories)
   * @throws InterruptedException If a filesystem could not be created in the destination directory
   * @throws IOException If the archive could not be extracted for any reason
   */
  public ImmutableList<Path> extractArchive(
      ProjectFilesystemFactory projectFilesystemFactory,
      Path archiveFile,
      final Path destination,
      ExistingFileMode existingFileMode)
      throws InterruptedException, IOException {
    return extractArchive(
        projectFilesystemFactory, archiveFile, destination, Optional.empty(), existingFileMode);
  }

  /**
   * Extract a given archive to a specific directory
   *
   * @param projectFilesystemFactory A factory that creates filesystems
   * @param archiveFile The path to the archive
   * @param destination The destination directory where the archive should be extracted to
   * @param stripPrefix If provided, only files under this prefix will be extracted. This prefix
   *     prefix will also be removed from the destination path. e.g. foo.tar.gz/foo/bar/baz with a
   *     prefix of foo will extract bar/baz into the destination directory. If not provided, no
   *     stripping is done.
   * @param existingFileMode How to handle existing files
   * @return A list of paths to files that were created (not directories)
   * @throws InterruptedException If a filesystem could not be created in the destination directory
   * @throws IOException If the archive could not be extracted for any reason
   */
  public ImmutableList<Path> extractArchive(
      ProjectFilesystemFactory projectFilesystemFactory,
      Path archiveFile,
      final Path destination,
      Optional<Path> stripPrefix,
      ExistingFileMode existingFileMode)
      throws InterruptedException, IOException {
    // Create output directory if it does not exist
    Files.createDirectories(destination);
    return extractArchive(
            archiveFile,
            projectFilesystemFactory.createProjectFilesystem(destination),
            destination.getFileSystem().getPath(""),
            stripPrefix,
            existingFileMode)
        .stream()
        .map(input -> destination.resolve(input).toAbsolutePath())
        .collect(ImmutableList.toImmutableList());
  }
}
