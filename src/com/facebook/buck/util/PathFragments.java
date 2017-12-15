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

package com.facebook.buck.util;

import com.google.common.collect.Streams;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;

/** Conversion utilities between Paths and PathFragments. */
public final class PathFragments {
  private PathFragments() {} // Utility class, do not instantiate.

  // NB: This code avoids invoking toString() on either Path or PathFragment instances which
  // may be long lived. For both classes, toString() results are cached in the object instance,
  // leading to excessive memory usage.

  /** Convert a Skylark PathFragment to a Java Path. */
  public static PathFragment pathToFragment(Path path) {
    Path root = path.getRoot();
    char driveLetter;
    if (root == null) {
      driveLetter = '\0';
    } else {
      String rootString = root.toString();
      if (rootString.equals("/")) {
        driveLetter = '\0';
      } else if (rootString.charAt(1) == ':') {
        driveLetter = Character.toUpperCase(rootString.charAt(0));
      } else {
        throw new AssertionError("Path.getRoot() returned an unexpected value: " + rootString);
      }
    }
    // Note: Path normally elide all empty components, since path components cannot be empty.
    // However, fileSystem.getPath("") will return a relative path with a single empty component.
    // PathFragment can represent this path by simply having no segments, hence the code below
    // filters out empty components.
    return PathFragment.create(
        driveLetter,
        path.isAbsolute(),
        Streams.stream(path)
            .map(Object::toString)
            .filter(s -> !s.isEmpty())
            .toArray(String[]::new));
  }

  /** Convert a skylark PathFragment to a Path using the default filesystem. */
  public static Path fragmentToPath(PathFragment fragment) {
    return fragmentToPath(FileSystems.getDefault(), fragment);
  }

  /** Convert a skylark PathFragment to a Path. */
  public static Path fragmentToPath(FileSystem fileSystem, PathFragment fragment) {
    char driveLetter = fragment.getDriveLetter();
    String rootComponent;
    if (driveLetter != '\0') {
      // Note: Bazel leaves behavior of volume prefixed relative paths undetermined.
      rootComponent =
          fragment.isAbsolute()
              ? new String(new char[] {driveLetter, ':', '/'})
              : new String(new char[] {driveLetter, ':'});
    } else {
      rootComponent = fragment.isAbsolute() ? "/" : "";
    }
    return fileSystem.getPath(rootComponent, fragment.getSegments().toArray(new String[0]));
  }
}
