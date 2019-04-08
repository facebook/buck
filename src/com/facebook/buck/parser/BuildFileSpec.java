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

package com.facebook.buck.parser;

import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
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
  // TODO: this should go out of this class
  @Value.Parameter
  public abstract Path getCellPath();

  public static BuildFileSpec fromRecursivePath(Path basePath, Path cellPath) {
    return ImmutableBuildFileSpec.of(basePath, /* recursive */ true, cellPath);
  }

  public static BuildFileSpec fromPath(Path basePath, Path cellPath) {
    return ImmutableBuildFileSpec.of(basePath, /* recursive */ false, cellPath);
  }

  public static BuildFileSpec fromUnconfiguredBuildTarget(UnconfiguredBuildTargetView target) {
    return fromPath(target.getBasePath(), target.getCellPath());
  }
}
