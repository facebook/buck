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

package com.facebook.buck.cli;

import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PathArguments {

  /** Utility class: do not instantiate. */
  private PathArguments() {}

  static class ReferencedFiles {
    final ImmutableSet<Path> relativePathsUnderProjectRoot;
    final ImmutableSet<Path> absolutePathsOutsideProjectRootOrNonExistingPaths;

    public ReferencedFiles(
        ImmutableSet<Path> relativePathsUnderProjectRoot,
        ImmutableSet<Path> absolutePathsOutsideProjectRootOrNonExistingPaths) {
      this.relativePathsUnderProjectRoot = relativePathsUnderProjectRoot;
      this.absolutePathsOutsideProjectRootOrNonExistingPaths =
          absolutePathsOutsideProjectRootOrNonExistingPaths;
    }
  }

  /**
   * Filter files under the project root, and convert to canonical relative path style.
   * For example, the project root is /project,
   * 1. file path /project/./src/com/facebook/./test/../Test.java will be converted to
   *    src/com/facebook/Test.java
   * 2. file path /otherproject/src/com/facebook/Test.java will be ignored.
   */
  static ReferencedFiles getCanonicalFilesUnderProjectRoot(
      Path projectRoot, Iterable<String> nonCanonicalFilePaths)
      throws IOException {
    // toRealPath() is used throughout to resolve symlinks or else the Path.startsWith() check will
    // not be reliable.
    ImmutableSet.Builder<Path> projectFiles = ImmutableSet.builder();
    ImmutableSet.Builder<Path> nonProjectFiles = ImmutableSet.builder();
    Path normalizedRoot = projectRoot.toRealPath();
    for (String filePath : nonCanonicalFilePaths) {
      Path canonicalFullPath = Paths.get(filePath);
      if (!canonicalFullPath.isAbsolute()) {
        canonicalFullPath = projectRoot.resolve(canonicalFullPath);
      }
      if (!canonicalFullPath.toFile().exists()) {
        nonProjectFiles.add(canonicalFullPath);
        continue;
      }
      canonicalFullPath = canonicalFullPath.toRealPath();

      // Ignore files that aren't under project root.
      if (canonicalFullPath.startsWith(normalizedRoot)) {
        Path relativePath = canonicalFullPath.subpath(
            normalizedRoot.getNameCount(),
            canonicalFullPath.getNameCount());
        projectFiles.add(relativePath);
      } else {
        nonProjectFiles.add(canonicalFullPath);
      }
    }
    return new ReferencedFiles(projectFiles.build(), nonProjectFiles.build());
  }


}
