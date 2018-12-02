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

package com.facebook.buck.core.model.impl;

import com.facebook.buck.core.model.BuildFileTree;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.filesystem.PathMatcher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.Optional;

/**
 * Class to allow looking up parents and children of build files. E.g. for a directory structure
 * that looks like:
 *
 * <pre>
 *   foo/BUCK
 *   foo/bar/baz/BUCK
 *   foo/bar/qux/BUCK
 * </pre>
 *
 * <p>foo/BUCK is the parent of foo/bar/baz/BUCK and foo/bar/qux/BUCK.
 */
public class FilesystemBackedBuildFileTree implements BuildFileTree {
  private final ProjectFilesystem projectFilesystem;
  private final String buildFileName;
  private final LoadingCache<Path, Boolean> pathExistenceCache;

  /**
   * Cache for the base path of a given path. This is useful as many files may share common
   * ancestors before reaching a base path.
   */
  private final LoadingCache<Path, Optional<Path>> basePathOfAncestorCache =
      CacheBuilder.newBuilder()
          .weakValues()
          .build(
              new CacheLoader<Path, Optional<Path>>() {
                @Override
                public Optional<Path> load(Path filePath) throws Exception {
                  Path parent = filePath.getParent();
                  if (parent == null) {
                    return checkProjectRoot();
                  }

                  // If filePath names a directory with a build file, filePath is a base path.
                  // If filePath or any of its parents are in ignoredPaths, we should keep looking.
                  if (isBasePath(parent)) {
                    return Optional.of(parent);
                  }

                  // If filePath isn't root, keep looking.
                  if (parent.equals(projectFilesystem.getRootPath())) {
                    return checkProjectRoot();
                  }

                  return basePathOfAncestorCache.get(parent);
                }
              });

  public FilesystemBackedBuildFileTree(ProjectFilesystem projectFilesystem, String buildFileName) {
    this.projectFilesystem = projectFilesystem;
    this.buildFileName = buildFileName;
    this.pathExistenceCache =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<Path, Boolean>() {
                  @Override
                  public Boolean load(Path key) {
                    return FilesystemBackedBuildFileTree.this.projectFilesystem.isFile(key);
                  }
                });
  }

  /** @return paths relative to BuildTarget that contain their own build files. */
  @Override
  public Collection<Path> getChildPaths(BuildTarget target) {
    // Crawl the subdirectories of target's base path, looking for build files.
    // When we find one, we can stop crawling anything under the directory it's in.
    ImmutableSet.Builder<Path> childPaths = ImmutableSet.builder();
    Path basePath = target.getBasePath();
    ImmutableSet<PathMatcher> ignoredPaths = projectFilesystem.getIgnorePaths();
    try {
      projectFilesystem.walkRelativeFileTree(
          basePath,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              for (PathMatcher ignoredPath : ignoredPaths) {
                if (ignoredPath.matches(dir)) {
                  return FileVisitResult.SKIP_SUBTREE;
                }
              }
              if (dir.equals(basePath)) {
                return FileVisitResult.CONTINUE;
              }
              Path buildFile = dir.resolve(buildFileName);
              if (pathExistenceCache.getUnchecked(buildFile)) {
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
   * Returns the base path for a given path. The base path is the nearest directory at or above
   * filePath that contains a build file. If no base directory is found, returns an empty path.
   */
  @Override
  public Optional<Path> getBasePathOfAncestorTarget(Path filePath) {
    // Since the initial file being passed in tends to be a file instead of a directory, we can skip
    // this initial check of filePath/BUCK if that's the case.
    if (!projectFilesystem.isFile(filePath) && isBasePath(filePath)) {
      return Optional.of(filePath);
    }

    return basePathOfAncestorCache.getUnchecked(filePath);
  }

  /** Returns whether the given path is a directory containing a buck file, i.e. a base path. */
  private boolean isBasePath(Path filePath) {
    return pathExistenceCache.getUnchecked(filePath.resolve(buildFileName))
        && !isBuckOutput(filePath)
        && !projectFilesystem.isIgnored(filePath);
  }

  private Optional<Path> checkProjectRoot() {
    // No build file found in any directory, check the project root
    Path rootBuckFile = Paths.get(buildFileName);
    if (pathExistenceCache.getUnchecked(rootBuckFile)
        && !projectFilesystem.isIgnored(rootBuckFile)) {
      return Optional.of(Paths.get(""));
    }

    // filePath does not fall under any build file
    return Optional.empty();
  }

  /**
   * Assume that any directory called "buck-out", "buck-out/cache" or ".buckd" can be ignored. Not
   * the world's best heuristic, but it works in every existing code base we have access to.
   */
  private boolean isBuckOutput(Path path) {
    Path sameFsBuckOut =
        path.getFileSystem().getPath(projectFilesystem.getBuckPaths().getBuckOut().toString());
    Path sameFsBuckCache = projectFilesystem.getBuckPaths().getCacheDir();

    for (Path segment : path) {
      if (sameFsBuckOut.equals(segment) || sameFsBuckCache.equals(segment)) {
        return true;
      }
    }

    return false;
  }
}
