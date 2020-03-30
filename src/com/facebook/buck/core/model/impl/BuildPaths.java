/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.model.impl;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Utility class that generates the build output paths for {@link BuildTarget}s in a systematic
 * manner.
 */
public class BuildPaths {

  private BuildPaths() {}

  /**
   * Return a path to a file in the buck-out/bin/ directory, formatted with the target short name.
   *
   * @param target The {@link BuildTarget} to scope this path to.
   * @return A {@link java.nio.file.Path} under buck-out/bin, scoped to the base path of {@code
   *     target}.
   */
  public static Path getScratchDir(ProjectFilesystem filesystem, BuildTarget target) {
    return BuildTargetPaths.getScratchPath(filesystem, target, getFormat(target));
  }

  /**
   * Return a path to a file in the buck-out/annotation/ directory, formatted with the target short
   * name.
   *
   * @param target The {@link BuildTarget} to scope this path to.
   * @return A {@link java.nio.file.Path} under buck-out/annotation, scoped to the base path of
   *     {@code target}.
   */
  public static Path getAnnotationDir(ProjectFilesystem filesystem, BuildTarget target) {
    return BuildTargetPaths.getAnnotationPath(filesystem, target, getFormat(target));
  }

  /**
   * Return a relative path to a file in the buck-out/gen/ directory, formatted with the target
   * short name.
   *
   * @param target The {@link BuildTarget} to scope this path to.
   * @return A {@link java.nio.file.Path} under buck-out/gen, scoped to the base path of {@code
   *     target}.
   */
  public static Path getGenDir(ProjectFilesystem filesystem, BuildTarget target) {
    return BuildTargetPaths.getGenPath(filesystem, target, getFormat(target));
  }

  /**
   * Return a relative path to a file taking into account the {@code target}'s package path and
   * formatting with the short name.
   *
   * <p>This is a portion of the path returned by, e.g., {@link #getGenDir(ProjectFilesystem,
   * BuildTarget)}
   *
   * @param target The {@link BuildTarget} to scope this path to.
   * @return A {@link java.nio.file.Path} scoped to the base path to {@code target}.
   */
  public static ForwardRelativePath getBaseDir(ProjectFilesystem filesystem, BuildTarget target) {
    return BuildTargetPaths.getBasePath(filesystem, target, getFormat(target));
  }

  /**
   * Removes the hash from the buck-out path. Used temporarily to create links.
   *
   * @param path Hashed buck-out path
   * @param target Build target that generated {@code path}
   * @return A path without the hash directory or {@code Optional.empty()} if the hash was not
   *     found.
   */
  // TODO(gabrielrc): Remove this once we removed all hardcoded buck paths.
  public static Optional<Path> removeHashFrom(Path path, BuildTarget target) {
    String hash = TargetConfigurationHasher.hash(target.getTargetConfiguration());
    int index = Iterables.indexOf(path, p -> p.endsWith(hash));
    if (index == -1) { // hash not found
      return Optional.empty();
    }
    Path pathWithoutHash =
        path.subpath(0, index).resolve(path.subpath(index + 1, path.getNameCount()));
    if (path.isAbsolute()) {
      // Path.subpath(0, n) doesn't include the root
      return Optional.of(path.getRoot().resolve(pathWithoutHash));
    }
    return Optional.of(pathWithoutHash);
  }

  private static String getFormat(BuildTarget target) {
    return target.isFlavored() ? "%s" : "%s__";
  }
}
