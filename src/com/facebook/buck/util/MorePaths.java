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

package com.facebook.buck.util;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSortedSet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class MorePaths {

  /** Utility class: do not instantiate. */
  private MorePaths() {}

  public static Path newPathInstance(String path) {
    return separatorsToUnix(path);
  }

  public static Path newPathInstance(File file) {
    return separatorsToUnix(file.getPath());
  }

  /**
   * @return The path using UNIX path separators.
   */
  public static Path separatorsToUnix(String path) {
    if (!File.separator.equals("/")) {
      path = path.replace(File.separator, "/");
    }
    return Paths.get(path).normalize();
  }

  /**
   * @return The path using UNIX path separators.
   */
  public static Path separatorsToUnix(Path path) {
    return separatorsToUnix(path.toString());
  }

  /**
   * @param toMakeAbsolute The {@link Path} to act upon.
   * @return The Path, made absolute and normalized.
   */
  public static Path absolutify(Path toMakeAbsolute) {
    return Preconditions.checkNotNull(toMakeAbsolute).toAbsolutePath().normalize();
  }

  /**
   * Creates a symlink at
   * {@code projectFilesystem.getRootPath().resolve(pathToDesiredLinkUnderProjectRoot)} that
   * points to {@code projectFilesystem.getRootPath().resolve(pathToExistingFileUnderProjectRoot)}
   * using a relative symlink.
   *
   * @param pathToDesiredLinkUnderProjectRoot must reference a file, not a directory.
   * @param pathToExistingFileUnderProjectRoot must reference a file, not a directory.
   * @return the relative path from the new symlink that was created to the existing file.
   */
  public static Path createRelativeSymlink(
      Path pathToDesiredLinkUnderProjectRoot,
      Path pathToExistingFileUnderProjectRoot,
      ProjectFilesystem projectFilesystem) throws IOException {
    return createRelativeSymlink(pathToDesiredLinkUnderProjectRoot,
        pathToExistingFileUnderProjectRoot,
        projectFilesystem.getRootPath());
  }

  /**
   * Creates a symlink at {@code pathToProjectRoot.resolve(pathToDesiredLinkUnderProjectRoot)} that
   * points to {@code pathToProjectRoot.resolve(pathToExistingFileUnderProjectRoot)} using a
   * relative symlink.
   *
   * @param pathToDesiredLinkUnderProjectRoot must reference a file, not a directory.
   * @param pathToExistingFileUnderProjectRoot must reference a file, not a directory.
   * @return the relative path from the new symlink that was created to the existing file.
   */
  public static Path createRelativeSymlink(
      Path pathToDesiredLinkUnderProjectRoot,
      Path pathToExistingFileUnderProjectRoot,
      Path pathToProjectRoot) throws IOException {
    Preconditions.checkArgument(!pathToDesiredLinkUnderProjectRoot.isAbsolute(),
        "Path must be relative to project root: %s.",
        pathToDesiredLinkUnderProjectRoot);
    Preconditions.checkArgument(!pathToExistingFileUnderProjectRoot.isAbsolute(),
        "Path must be relative to project root: %s.",
        pathToExistingFileUnderProjectRoot);

    Path parent = pathToDesiredLinkUnderProjectRoot.getParent();
    String base = Strings.repeat("../", parent == null ? 0 : parent.getNameCount());
    Path target = Paths.get(base, pathToExistingFileUnderProjectRoot.toString());
    Files.createSymbolicLink(pathToProjectRoot.resolve(pathToDesiredLinkUnderProjectRoot), target);
    return target;
  }

  /**
   * Convert a set of input file paths as strings to {@link Path} objects.
   */
  public static ImmutableSortedSet<Path> asPaths(Iterable<String> paths) {
    ImmutableSortedSet.Builder<Path> builder = ImmutableSortedSet.naturalOrder();
    for (String path : paths) {
      builder.add(Paths.get(path));
    }
    return builder.build();
  }
}
