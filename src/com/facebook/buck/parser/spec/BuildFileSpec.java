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

package com.facebook.buck.parser.spec;

import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/** A specification used by the parser, via {@link TargetNodeSpec}, to match build files. */
@BuckStyleValue
public abstract class BuildFileSpec {

  // Base path where to find either a single build file or to recursively for many build files.
  public abstract CellRelativePath getCellRelativeBaseName();

  // If present, this indicates that the above path should be recursively searched for build files,
  // and that the paths enumerated here should be ignored.
  public abstract boolean isRecursive();

  /**
   * Create a {@link BuildFileSpec} for a recursive build target pattern like foo/bar/...
   *
   * @return a new {@link BuildFileSpec} with {@link #isRecursive()}} set to {@code true}
   */
  public static BuildFileSpec fromRecursivePath(CellRelativePath cellRelativePath) {
    return ImmutableBuildFileSpec.of(cellRelativePath, /* recursive */ true);
  }

  /**
   * Create a {@link BuildFileSpec} for a package build target pattern like foo/bar: or foo/bar:baz
   *
   * @return a new {@link BuildFileSpec} with {@link #isRecursive()}} set to {@code false}
   */
  public static BuildFileSpec fromPath(CellRelativePath cellRelativePath) {
    return ImmutableBuildFileSpec.of(cellRelativePath, /* recursive */ false);
  }

  /** @return a {@link BuildFileSpec} for a specific build target like //foo/bar:baz */
  public static BuildFileSpec fromUnconfiguredBuildTarget(UnconfiguredBuildTarget target) {
    return fromPath(target.getCellRelativeBasePath());
  }
}
