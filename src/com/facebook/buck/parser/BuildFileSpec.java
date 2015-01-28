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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * A specification used by the parser, via {@link TargetNodeSpec}, to match build files.
 */
public class BuildFileSpec {

  // Base path where to find either a single build file or to recursively for many build files.
  private final Path basePath;

  // If present, this indicates that the above path should be recursively searched for build files,
  // and that the paths enumerated here should be ignored.
  private final boolean recursive;
  private final ImmutableSet<Path> recursiveIgnorePaths;

  private BuildFileSpec(
      Path basePath,
      boolean recursive,
      ImmutableSet<Path> recursiveIgnorePaths) {
    this.basePath = basePath;
    this.recursive = recursive;
    this.recursiveIgnorePaths = recursiveIgnorePaths;
  }

  public static BuildFileSpec fromRecursivePath(Path basePath, ImmutableSet<Path> ignorePaths) {
    return new BuildFileSpec(basePath, /* recursive */ true, ignorePaths);
  }

  public static BuildFileSpec fromRecursivePath(Path basePath) {
    return new BuildFileSpec(
        basePath,
        /* recursive */ true,
        /* ignorePaths */ ImmutableSet.<Path>of());
  }

  public static BuildFileSpec fromPath(Path basePath) {
    return new BuildFileSpec(
        basePath,
        /* recursive */ false,
        ImmutableSet.<Path>of());
  }

  public static BuildFileSpec fromBuildTarget(BuildTarget target) {
    return fromPath(target.getBasePath());
  }

  /**
   * Find all build in the given {@link ProjectFilesystem}, and pass each to the given callable.
   */
  public void forEachBuildFile(
      ProjectFilesystem filesystem,
      final String buildFileName,
      final Function<Path, Void> function)
      throws IOException {

    // If non-recursive, we just want the build file in the target spec's given base dir.
    if (!recursive) {
      function.apply(basePath.resolve(buildFileName));
      return;
    }

    // Otherwise, we need to do a recursive walk to find relevant build files.
    filesystem.walkRelativeFileTree(
        basePath,
        new FileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(
              Path dir,
              BasicFileAttributes attrs)
              throws IOException {
            // Skip sub-dirs that we should ignore.
            if (recursiveIgnorePaths.contains(dir)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(
              Path file,
              BasicFileAttributes attrs)
              throws IOException {
            if (buildFileName.equals(file.getFileName().toString())) {
              function.apply(file);
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(
              Path file, IOException exc) throws IOException {
            throw exc;
          }

          @Override
          public FileVisitResult postVisitDirectory(
              Path dir,
              IOException exc)
              throws IOException {
            if (exc != null) {
              throw exc;
            }
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /**
   * @return paths to build files that this spec match in the given {@link ProjectFilesystem}.
   */
  public ImmutableSet<Path> findBuildFiles(
      ProjectFilesystem filesystem,
      String buildFileName) throws IOException {
    final ImmutableSet.Builder<Path> buildFiles = ImmutableSet.builder();

    forEachBuildFile(
        filesystem,
        buildFileName,
        new Function<Path, Void>() {
          @Override
          public Void apply(Path buildFile) {
            buildFiles.add(buildFile);
            return null;
          }
        });

    return buildFiles.build();
  }

}
