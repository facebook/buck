/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.model;

import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Preconditions;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Static helpers for working with build targets.
 */
public class BuildTargets {

  /** Utility class: do not instantiate. */
  private BuildTargets() {}

  /**
   * Return a path to a file in the buck-out/bin/ directory. {@code format} will be prepended with
   * the {@link com.facebook.buck.util.BuckConstant#BIN_DIR} and the target base path, then
   * formatted with the target short name.
   *
   * @param target The {@link BuildTarget} to scope this path to.
   * @param format {@link String#format} string for the path name.  It should contain one "%s",
   *     which will be filled in with the rule's short name.  It should not start with a slash.
   * @return A {@link java.nio.file.Path} under buck-out/bin, scoped to the base path of
   * {@code target}.
   */

  public static Path getBinPath(BuildTarget target, String format) {
    return Paths.get(String.format("%s/%s" + format,
        BuckConstant.BIN_DIR,
        target.getBasePathWithSlash(),
        target.getShortName()));
  }

  /**
   * Return a path to a file in the buck-out/gen/ directory. {@code format} will be prepended with
   * the {@link com.facebook.buck.util.BuckConstant#GEN_DIR} and the target base path, then
   * formatted with the target short name.
   *
   * @param target The {@link BuildTarget} to scope this path to.
   * @param format {@link String#format} string for the path name.  It should contain one "%s",
   *     which will be filled in with the rule's short name.  It should not start with a slash.
   * @return A {@link java.nio.file.Path} under buck-out/gen, scoped to the base path of
   * {@code target}.
   */
  public static Path getGenPath(BuildTarget target, String format) {
    return Paths.get(String.format("%s/%s" + format,
        BuckConstant.GEN_DIR,
        target.getBasePathWithSlash(),
        target.getShortName()));
  }

  /**
   * Takes the {@link BuildTarget} for {@code hasBuildTarget} and derives a new {@link BuildTarget}
   * from it with the specified flavor.
   * @throws IllegalArgumentException if the original {@link BuildTarget} already has a flavor.
   */
  public static BuildTarget createFlavoredBuildTarget(
      HasBuildTarget hasBuildTarget,
      Flavor flavor) {
    Preconditions.checkNotNull(hasBuildTarget);
    Preconditions.checkNotNull(flavor);
    BuildTarget buildTarget = hasBuildTarget.getBuildTarget();
    Preconditions.checkArgument(!buildTarget.isFlavored(),
        "Cannot add flavor %s to %s.",
        flavor,
        buildTarget);
    return new BuildTarget(buildTarget.getBaseName(), buildTarget.getShortName(), flavor);
  }
}
