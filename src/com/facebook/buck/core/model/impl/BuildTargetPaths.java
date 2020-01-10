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
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Preconditions;
import java.nio.file.Path;

/**
 * Static helpers for working with build targets.
 *
 * @deprecated use {@link BuildPaths} instead, which handles flavoured and unflavoured {@link
 *     BuildTarget}s in the paths the same way as RE/{@link
 *     com.facebook.buck.rules.modern.ModernBuildRule}s do.
 */
@Deprecated
public class BuildTargetPaths {

  /** Utility class: do not instantiate. */
  private BuildTargetPaths() {}

  /**
   * Return a path to a file in the buck-out/bin/ directory. {@code format} will be prepended with
   * the {@link BuckPaths#getScratchDir()} and the target base path, then formatted with the target
   * short name.
   *
   * @param target The {@link BuildTarget} to scope this path to.
   * @param format {@link String#format} string for the path name. It should contain one "%s", which
   *     will be filled in with the rule's short name. It should not start with a slash.
   * @return A {@link java.nio.file.Path} under buck-out/bin, scoped to the base path of {@code
   *     target}.
   */
  public static Path getScratchPath(
      ProjectFilesystem filesystem, BuildTarget target, String format) {
    Preconditions.checkArgument(
        !format.startsWith("/"), "format string should not start with a slash");
    return filesystem
        .getBuckPaths()
        .getScratchDir()
        .resolve(getBasePath(filesystem, target, format).toPath(filesystem.getFileSystem()));
  }

  /**
   * Return a path to a file in the buck-out/annotation/ directory. {@code format} will be prepended
   * with the {@link BuckPaths#getAnnotationDir()} and the target base path, then formatted with the
   * target short name.
   *
   * @param target The {@link BuildTarget} to scope this path to.
   * @param format {@link String#format} string for the path name. It should contain one "%s", which
   *     will be filled in with the rule's short name. It should not start with a slash.
   * @return A {@link java.nio.file.Path} under buck-out/annotation, scoped to the base path of
   *     {@code target}.
   */
  public static Path getAnnotationPath(
      ProjectFilesystem filesystem, BuildTarget target, String format) {
    Preconditions.checkArgument(
        !format.startsWith("/"), "format string should not start with a slash");
    return filesystem
        .getBuckPaths()
        .getAnnotationDir()
        .resolve(getBasePath(filesystem, target, format).toPath(filesystem.getFileSystem()));
  }

  /**
   * Return a relative path to a file in the buck-out/gen/ directory. {@code format} will be
   * prepended with the {@link BuckPaths#getGenDir()} and the target base path, then formatted with
   * the target short name.
   *
   * @param target The {@link BuildTarget} to scope this path to.
   * @param format {@link String#format} string for the path name. It should contain one "%s", which
   *     will be filled in with the rule's short name. It should not start with a slash.
   * @return A {@link java.nio.file.Path} under buck-out/gen, scoped to the base path of {@code
   *     target}.
   */
  public static Path getGenPath(ProjectFilesystem filesystem, BuildTarget target, String format) {
    Preconditions.checkArgument(
        !format.startsWith("/"), "format string should not start with a slash");

    return filesystem
        .getBuckPaths()
        .getGenDir()
        .resolve(getBasePath(filesystem, target, format).toPath(filesystem.getFileSystem()));
  }

  /** A folder where all targets in the file of target are created. */
  public static Path getGenPathForBaseName(ProjectFilesystem filesystem, BuildTarget target) {
    return filesystem
        .getBuckPaths()
        .getGenDir()
        .resolve(getBasePathForBaseName(filesystem, target).toPath(filesystem.getFileSystem()));
  }

  /**
   * Return a relative path to a file. {@code format} will be prepended with the target base path,
   * then formatted with the target short name.
   *
   * <p>This is portion of the path returned by, e.g., {@link #getGenPath(ProjectFilesystem,
   * BuildTarget, String)}
   *
   * @param target The {@link BuildTarget} to scope this path to.
   * @param format {@link String#format} string for the path name. It should contain one "%s", which
   *     will be filled in with the rule's short name. It should not start with a slash.
   * @return A {@link java.nio.file.Path} scoped to the base path of {@code target}.
   */
  public static ForwardRelativePath getBasePath(
      ProjectFilesystem filesystem, BuildTarget target, String format) {
    Preconditions.checkArgument(
        !format.startsWith("/"), "format string should not start with a slash");

    return getBasePathForBaseName(filesystem, target)
        .resolve(formatLastSegment(format, target.getShortNameAndFlavorPostfix()));
  }

  /** Return a relative path for all targets in a package of a {@link BuildTarget}. */
  public static ForwardRelativePath getBasePathForBaseName(
      ProjectFilesystem filesystem, BuildTarget target) {
    boolean includeTargetConfigHash = filesystem.getBuckPaths().shouldIncludeTargetConfigHash();
    ForwardRelativePath configHashPath =
        ForwardRelativePath.of(
            includeTargetConfigHash
                ? TargetConfigurationHasher.hash(target.getTargetConfiguration())
                : "");

    return configHashPath.resolve(target.getCellRelativeBasePath().getPath());
  }

  private static String formatLastSegment(String format, String arg) {
    if (Platform.detect() == Platform.WINDOWS) {
      // TODO(nga): prohibit backslashes in format
      format = format.replace('\\', '/');
    }

    return String.format(format, arg);
  }
}
