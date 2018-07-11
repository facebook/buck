/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.testutil;

import com.facebook.buck.util.environment.Platform;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * This class contains methods that convert Unix names to Windows. It is used in existing tests that
 * have hardcoded Unix file names and when ran on Windows, the tests fail because the expected and
 * the Java returned file name (especially for tool paths) don't match.
 */
public final class PathNormalizer {

  /**
   * Converts a potentially Unix path to Windows path, using 'C' as a logical drive, for cases where
   * the OS the test runs is Windows and the path passed is in Unix format.
   *
   * @param path The path that could be in Unix format that needs to be converted to Windows path.
   * @return The converted path to Windows format. If the path is in Windows form, no any changes to
   *     the path are performed.
   */
  public static Path toWindowsPathIfNeeded(Path path) {
    return toWindowsPathIfNeeded('C', path);
  }

  /**
   * Converts a potentially Unix path to Windows path, using 'C' as a logical drive, for cases *
   * where the OS the test runs is Windows and the path passed is in Unix format.
   *
   * @param logicalDrive The logical drive letter to prepend the Unix path with.
   * @param path The path that could be in Unix format that needs to be converted to Windows path.
   * @return The converted path to Windows format. If the path is in Windows form, no any changes to
   *     the path are performed.
   */
  public static Path toWindowsPathIfNeeded(char logicalDrive, Path path) {
    if (!Platform.detect().getType().isWindows()) {
      return path;
    }

    if (path.startsWith("\\")) {
      // This is Unix path (hardcoded in some test). Prepend the Windows logical drive part.
      return Paths.get(String.format("%c:%s", logicalDrive, path));
    }

    return path;
  }
}
