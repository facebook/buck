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

package com.facebook.buck.model;

import com.facebook.buck.io.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.Set;

/**
 * Class to allow looking up parents and children of build files.
 * E.g. for a directory structure that looks like:
 * <pre>
 *   foo/BUCK
 *   foo/bar/baz/BUCK
 *   foo/bar/qux/BUCK
 * </pre>
 * <p>
 * foo/BUCK is the parent of foo/bar/baz/BUCK and foo/bar/qux/BUCK.
 */
public class FilesystemBackedBuildFileTree extends BuildFileTree {
  private final ProjectFilesystem projectFilesystem;
  private final String buildFileName;

  public FilesystemBackedBuildFileTree(ProjectFilesystem projectFilesystem, String buildFileName) {
    this.projectFilesystem = projectFilesystem;
    this.buildFileName = buildFileName;
  }

  /**
   * @return paths relative to BuildTarget that contain their own build files.
   */
  @Override
  public Collection<Path> getChildPaths(BuildTarget target) {
    // Crawl the subdirectories of target's base path, looking for build files.
    // When we find one, we can stop crawling anything under the directory it's in.
    final ImmutableSet.Builder<Path> childPaths = ImmutableSet.builder();
    final Path basePath = target.getBasePath();
    final Set<Path> ignoredPaths = projectFilesystem.getIgnorePaths();
    try {
      projectFilesystem.walkRelativeFileTree(basePath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              if (ignoredPaths.contains(dir)) {
                return FileVisitResult.SKIP_SUBTREE;
              }
              if (dir.equals(basePath)) {
                return FileVisitResult.CONTINUE;
              }
              Path buildFile = dir.resolve(buildFileName);
              if (projectFilesystem.isFile(buildFile)) {
                childPaths.add(basePath.relativize(dir));
                return FileVisitResult.SKIP_SUBTREE;
              }

              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return childPaths.build();
  }

  /**
   * Returns the base path for a given path. The base path is the nearest directory at or
   * above filePath that contains a build file. If no base directory is found, returns an empty
   * path.
   */
  @Override
  public Optional<Path> getBasePathOfAncestorTarget(Path filePath) {
    while (filePath != null) {
      // If filePath names a directory with a build file, filePath is a base path.
      // If filePath or any of its parents are in ignoredPaths, we should keep looking.
      if (projectFilesystem.isFile(filePath.resolve(buildFileName)) &&
          !projectFilesystem.isIgnored(filePath)) {
        return Optional.of(filePath);
      }

      // If filePath is root, we're done looking.
      if (filePath.equals(projectFilesystem.getRootPath())) {
        break;
      }

      // filePath isn't a base path; look at its parent.
      filePath = filePath.getParent();
    }

    // No build file found in any directory, check the project root
    Path rootBuckFile = Paths.get(buildFileName);
    if (projectFilesystem.isFile(rootBuckFile) &&
        !projectFilesystem.isIgnored(rootBuckFile)) {
      return Optional.of(Paths.get(""));
    }

    // filePath does not fall under any build file
    return Optional.absent();
  }
}
