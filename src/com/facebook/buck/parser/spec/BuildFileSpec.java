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

package com.facebook.buck.parser.spec;

import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import org.immutables.value.Value;

/** A specification used by the parser, via {@link TargetNodeSpec}, to match build files. */
@Value.Immutable(builder = false)
public abstract class BuildFileSpec {

  // Base path where to find either a single build file or to recursively for many build files.
  @Value.Parameter
  public abstract Path getBasePath();

  // If present, this indicates that the above path should be recursively searched for build files,
  // and that the paths enumerated here should be ignored.
  @Value.Parameter
  public abstract boolean isRecursive();

  // The absolute cell path in which the build spec exists
  @Value.Parameter
  public abstract Path getCellPath();

  @Value.Check
  protected void check() {
    Preconditions.checkState(
        !getBasePath().isAbsolute(), "base path '%s' must be relative", getBasePath());
  }

  /**
   * Create a {@link BuildFileSpec} for a recursive build target pattern like foo/bar/...
   *
   * @param basePath the relative path within the {@code cellPath}
   * @param cellPath the absolute path to the cell that this target resides within
   * @return a new {@link BuildFileSpec} with {@link #isRecursive()}} set to {@code true}
   */
  public static BuildFileSpec fromRecursivePath(Path basePath, Path cellPath) {
    return ImmutableBuildFileSpec.of(basePath, /* recursive */ true, cellPath);
  }

  /**
   * Create a {@link BuildFileSpec} for a package build target pattern like foo/bar: or foo/bar:baz
   *
   * @param basePath the relative path to the directory containing the build file within the {@code
   *     cellPath}
   * @param cellPath the absolute path to the cell that this target resides within
   * @return a new {@link BuildFileSpec} with {@link #isRecursive()}} set to {@code false}
   */
  public static BuildFileSpec fromPath(Path basePath, Path cellPath) {
    return ImmutableBuildFileSpec.of(basePath, /* recursive */ false, cellPath);
  }

  /** @return a {@link BuildFileSpec} for a specific build target like //foo/bar:baz */
  public static BuildFileSpec fromUnconfiguredBuildTarget(UnconfiguredBuildTargetView target) {
    return fromPath(target.getBasePath(), target.getCellPath());
  }
}
