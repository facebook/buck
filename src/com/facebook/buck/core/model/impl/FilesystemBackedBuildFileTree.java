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
  private final Path buildFile;
  private final Path rootPath;

  /**
   * Cache for the base path of a given path. Key is a folder for which base path is wanted and
   * value is a base path. If folder is a package itself (i.e. it does have a build file) then key
   * and value are the same object. All paths are relative to provided filesystem root.
   */
  private final LoadingCache<Path, Optional<Path>> basePathOfAncestorCache =
      CacheBuilder.newBuilder()
          .weakValues()
          .build(
              new CacheLoader<Path, Optional<Path>>() {
                @Override
                public Optional<Path> load(Path folderPath) throws Exception {

                  Path buildFileCandidate = folderPath.resolve(buildFile);

                  // projectFilesystem.isIgnored() is invoked for any folder in a tree
                  // this is not effective, can be optimized by using more efficient tree matchers
                  // for ignored paths
                  if (projectFilesystem.isFile(buildFileCandidate)
                      && !projectFilesystem.isIgnored(buildFileCandidate)
                      && !isBuckSpecialPath(folderPath)) {
                    return Optional.of(folderPath);
                  }

                  if (folderPath.equals(rootPath)) {
                    return Optional.empty();
                  }

                  // traverse up
                  Path parent = folderPath.getParent();
                  if (parent == null) {
                    parent = rootPath;
                  }

                  return basePathOfAncestorCache.get(parent);
                }
              });

  public FilesystemBackedBuildFileTree(ProjectFilesystem projectFilesystem, String buildFileName) {
    this.projectFilesystem = projectFilesystem;
    this.buildFile = projectFilesystem.getPath(buildFileName);
    this.rootPath = projectFilesystem.getPath("");
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
              Optional<Path> baseAncestorPathPath = basePathOfAncestorCache.getUnchecked(dir);
              if (baseAncestorPathPath.isPresent() && baseAncestorPathPath.get().equals(dir)) {
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

    // This will do `stat` which might be expensive. In fact, we almost always know if filePath
    // is a file or folder at caller's site, but the API based on Path is just too generic.
    // To avoid this call we might want to come up with 2 different functions (or cache
    // `projectFilesystem.isFile`) on BuildFileTree interface.
    if (projectFilesystem.isFile(filePath)) {
      filePath = filePath.getParent();
      if (filePath == null) {
        filePath = rootPath;
      }
    }

    if (filePath.isAbsolute()) {
      filePath = projectFilesystem.relativize(filePath);
    }

    return basePathOfAncestorCache.getUnchecked(filePath);
  }

  /**
   * @return True if path should be ignored because it is Buck special path, like buck-out or cache
   *     folder, which means it cannot contain build files
   */
  private boolean isBuckSpecialPath(Path path) {
    Path buckOut = projectFilesystem.getBuckPaths().getBuckOut();
    Path buckCache = projectFilesystem.getBuckPaths().getCacheDir();

    return path.startsWith(buckOut) || path.startsWith(buckCache);
  }
}
