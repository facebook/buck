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

package com.facebook.buck.io.file;

import com.facebook.buck.core.filesystems.BuckUnixPath;
import com.google.common.hash.Hasher;
import java.nio.file.Path;

/**
 * FastPaths is primarily utilities for interacting with Path objects in a way that is efficient
 * when those Paths are BuckUnixPath.
 *
 * <p>We use FastPaths for this instead of putting it in MorePaths to give a hint to the reader
 * about why we aren't just doing seemingly simple things ourselves.
 */
public class FastPaths {
  /**
   * Gets a Path segment as a String. Roughly equivalent to {@code path.getName(index).toString()}.
   */
  public static String getNameString(Path path, int index) {
    if (path instanceof BuckUnixPath) {
      return BuckUnixPath.InternalsForFastPaths.getNameString((BuckUnixPath) path, index);
    }
    return path.getName(index).toString();
  }

  /**
   * Adds the Path to the hasher as unencoded chars. Roughly equivalent to {@code
   * hasher.putUnencodedChars(path.toString())}.
   */
  public static Hasher hashPathFast(Hasher hasher, Path path) {
    if (!(path instanceof BuckUnixPath)) {
      return hasher.putUnencodedChars(path.toString());
    }
    if (path.isAbsolute()) {
      hasher.putChar('/');
    }
    for (int i = 0; i < path.getNameCount(); i++) {
      if (i != 0) {
        hasher.putChar('/');
      }
      hasher.putUnencodedChars(FastPaths.getNameString(path, i));
    }
    return hasher;
  }
}
