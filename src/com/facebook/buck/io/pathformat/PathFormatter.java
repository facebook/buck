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

package com.facebook.buck.io.pathformat;

import com.facebook.buck.core.filesystems.PathWrapper;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Utilities to format paths with predictable path separators. */
public class PathFormatter {
  /** Utility class: do not instantiate. */
  private PathFormatter() {}

  public static String pathWithUnixSeparators(String path) {
    return pathWithUnixSeparators(Paths.get(path));
  }

  public static String pathWithUnixSeparators(Path path) {
    return path.toString().replace('\\', '/');
  }

  public static String pathWithUnixSeparators(PathWrapper path) {
    return pathWithUnixSeparators(path.getPath());
  }

  public static String pathWithWindowsSeparators(Path path) {
    return path.toString().replace('/', '\\');
  }

  public static String pathWithUnixSeparatorsAndTrailingSlash(Path path) {
    return pathWithUnixSeparators(path) + "/";
  }
}
