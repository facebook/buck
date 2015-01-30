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

package com.facebook.buck.apple;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;

import java.nio.file.Path;

/**
 * Helper class to convert among various relative path-like objects.
 *
 * Methods are named in the form of {@code xToY}, such that invocation of the method will take a
 * path referenced from Y to one that is referenced from X.
 */
public final class PathRelativizer {
  private SourcePathResolver resolver;

  private final Path outputPathToProjectRoot;

  public PathRelativizer(Path projectRoot, Path outputDirectory, SourcePathResolver resolver) {
    this.resolver = resolver;
    this.outputPathToProjectRoot = MorePaths.relativize(
        outputDirectory.toAbsolutePath(),
        projectRoot.toAbsolutePath());
  }

  /**
   * Path from output directory to a build target's buck file directory.
   */
  public Path outputPathToBuildTargetPath(BuildTarget target, Path... paths) {
    Path result = outputPathToProjectRoot.resolve(target.getBasePath());
    for (Path p : paths) {
      result = result.resolve(p);
    }
    return result.normalize();
  }

  /**
   * Path from output directory to given path that's relative to the root directory.
   */
  public Path outputDirToRootRelative(Path path) {
    return outputPathToProjectRoot.resolve(path).normalize();
  }

  /**
   * Map a SourcePath to one that's relative to the output directory.
   */
  public Path outputPathToSourcePath(SourcePath sourcePath) {
    return outputDirToRootRelative(resolver.getPath(sourcePath).normalize());
  }
}
