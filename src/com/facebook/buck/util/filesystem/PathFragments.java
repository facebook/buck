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

package com.facebook.buck.util.filesystem;

import com.google.devtools.build.lib.vfs.PathFragment;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;

/** Conversion utilities between Paths and PathFragments. */
public final class PathFragments {
  private PathFragments() {} // Utility class, do not instantiate.

  /** Convert a Skylark PathFragment to a Java Path. */
  public static PathFragment pathToFragment(Path path) {
    // TODO(buckteam): Avoid invoking toString() on Path instances which may be long lived.
    // toString() results are cached in the object instance, leading to excessive memory usage.
    return PathFragment.create(path.toString());
  }

  /** Convert a skylark PathFragment to a Path using the default filesystem. */
  public static Path fragmentToPath(PathFragment fragment) {
    return fragmentToPath(FileSystems.getDefault(), fragment);
  }

  /** Convert a skylark PathFragment to a Path. */
  public static Path fragmentToPath(FileSystem fileSystem, PathFragment fragment) {
    return fileSystem.getPath(fragment.toString());
  }
}
