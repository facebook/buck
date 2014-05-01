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

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nullable;

public class MorePaths {

  private static final boolean SYSTEM_PATH_SEPARATOR_IS_UNIX_PATH_SEPARATOR =
      File.separator.equals("/");

  /** Utility class: do not instantiate. */
  private MorePaths() {}

  public static final Function<String, Path> TO_PATH = new Function<String, Path>() {
    @Override
    public Path apply(String path) {
      return Paths.get(path);
    }
  };

  public static String pathWithUnixSeparators(String path) {
    return pathWithUnixSeparators(Paths.get(path));
  }

  public static String pathWithUnixSeparators(Path path) {
    if (SYSTEM_PATH_SEPARATOR_IS_UNIX_PATH_SEPARATOR) {
      return path.toString();
    } else {
      return path.toString().replace(File.separator, "/");
    }
  }

  /**
   * @param toMakeAbsolute The {@link Path} to act upon.
   * @return The Path, made absolute and normalized.
   */
  public static Path absolutify(Path toMakeAbsolute) {
    return Preconditions.checkNotNull(toMakeAbsolute).toAbsolutePath().normalize();
  }

  /**
   * Get the path of a file relative to a base directory.
   *
   * @param path must reference a file, not a directory.
   * @param baseDir must reference a directory that is relative to a common directory with the path.
   *     may be null if referencing the same directory as the path.
   * @return the relative path of path from the directory baseDir.
   */
  public static Path getRelativePath(Path path, @Nullable Path baseDir) {
    if (baseDir == null) {
      // This allows callers to use this method with "file.parent()" for files from the project
      // root dir.
      baseDir = Paths.get("");
    }
    Preconditions.checkArgument(!path.isAbsolute(),
        "Path must be relative: %s.", path);
    Preconditions.checkArgument(!baseDir.isAbsolute(),
        "Path must be relative: %s.", baseDir);
    return baseDir.relativize(path);
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
   * relative symlink. Both params must be relative to the project root.
   *
   * @param pathToDesiredLinkUnderProjectRoot must reference a file, not a directory.
   * @param pathToExistingFileUnderProjectRoot must reference a file, not a directory.
   * @return the relative path from the new symlink that was created to the existing file.
   */
  public static Path createRelativeSymlink(
      Path pathToDesiredLinkUnderProjectRoot,
      Path pathToExistingFileUnderProjectRoot,
      Path pathToProjectRoot) throws IOException {
    Path target = getRelativePath(
        pathToExistingFileUnderProjectRoot,
        pathToDesiredLinkUnderProjectRoot.getParent());
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

  /**
   * Filters out {@link Path} objects from {@code paths} that aren't a subpath of {@code root} and
   * returns a set of paths relative to {@code root}.
   */
  public static ImmutableSet<Path> filterForSubpaths(Iterable<Path> paths, final Path root) {
    final Path normalizedRoot = root.toAbsolutePath().normalize();
    return FluentIterable.from(paths)
        .filter(new Predicate<Path>() {
          @Override
          public boolean apply(Path input) {
            if (input.isAbsolute()) {
              return input.normalize().startsWith(normalizedRoot);
            } else {
              return true;
            }
          }
        })
        .transform(new Function<Path, Path>() {
          @Override
          public Path apply(Path input) {
            if (input.isAbsolute()) {
              return normalizedRoot.relativize(input.normalize());
            } else {
              return input;
            }
          }
        })
        .toSet();
  }

  /**
   * @return Whether the input path directs to a file in the buck generated files folder.
   */
  public static boolean isGeneratedFile(Path pathRelativeToProjectRoot) {
    return pathRelativeToProjectRoot.startsWith(BuckConstant.GEN_PATH);
  }
}
