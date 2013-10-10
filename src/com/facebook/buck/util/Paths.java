/*
 * Copyright 2012-present Facebook, Inc.
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

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;

import java.io.File;

public class Paths {

  private Paths() {}

  /**
   * @param from must always refer to a directory (it should either be the empty string or end with
   *     a slash).
   * @param to may refer to either a file or a directory
   */
  public static String computeRelativePath(String from, String to) {
    Preconditions.checkNotNull(from);
    Preconditions.checkArgument(from.isEmpty() || from.endsWith("/"),
        "Directory path must either be the empty string or end with a slash");
    Preconditions.checkNotNull(to);

    if (from.isEmpty()) {
      return to;
    }

    // Both from and to have the same string prefix through this character index.
    int samePrefixIndex = 0;
    while (true) {
      int slashIndex = from.indexOf('/', samePrefixIndex);
      if (slashIndex < 0) {
        break;
      }

      int indexIncludingSlash = slashIndex + 1;
      if (indexIncludingSlash > to.length()) {
        break;
      }

      String fromPathElement = from.substring(samePrefixIndex, indexIncludingSlash);
      String toPathElement = to.substring(samePrefixIndex, indexIncludingSlash);
      if (fromPathElement.equals(toPathElement)) {
        samePrefixIndex = indexIncludingSlash;
      } else {
        break;
      }
    }

    int directoryDepth = 0;
    for (int charIndex = samePrefixIndex + 1; charIndex < from.length(); charIndex++) {
      if (from.charAt(charIndex) == '/') {
        directoryDepth++;
      }
    }

    return Strings.repeat("../", directoryDepth) + to.substring(samePrefixIndex);
  }

  public static Iterable<String> transformFileToAbsolutePath(Iterable<File> files) {
    return Iterables.transform(files, Functions.FILE_TO_ABSOLUTE_PATH);
  }

  /**
   * Returns normalized path. On Windows \ will be replaced with /.
   * @return Normalized path
   */
  public static String normalizePathSeparator(String path) {
    return path.replace("\\", "/");
  }

  /** @return true if the specified path contains a backslash character. */
  public static boolean containsBackslash(String path) {
    return path.indexOf('\\') >= 0;
  }
}
