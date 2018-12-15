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

package com.facebook.buck.io.filesystem.impl;

import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.PathMatcher;
import com.facebook.buck.io.filesystem.ProjectFilesystemView;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;

/** A {@link ProjectFilesystemView} for the {@link DefaultProjectFilesystem} */
public class DefaultProjectFilesystemView implements ProjectFilesystemView {

  private final DefaultProjectFilesystem filesystemParent;

  /**
   * an relative path representing the root of this view relative to the {@code filesystemParent}'s
   * root
   */
  @VisibleForTesting final Path projectRoot;

  /** the relative project root resolved against the filesystem */
  private final Path resolvedProjectRoot;

  @VisibleForTesting final ImmutableMap<PathMatcher, Predicate<Path>> ignoredPaths;

  DefaultProjectFilesystemView(
      DefaultProjectFilesystem filesystemParent,
      Path projectRoot,
      Path resolvedProjectRoot,
      ImmutableMap<PathMatcher, Predicate<Path>> ignoredPaths) {

    this.filesystemParent = filesystemParent;
    this.projectRoot = projectRoot;
    this.ignoredPaths = ignoredPaths;
    this.resolvedProjectRoot = resolvedProjectRoot;
  }

  @Override
  public boolean isSubdirOf(Path path) {
    return path.normalize().startsWith(resolvedProjectRoot.normalize());
  }

  @Override
  public Path relativize(Path path) {
    return resolvedProjectRoot.relativize(path);
  }

  @Override
  public Path resolve(Path path) {
    return resolvedProjectRoot.resolve(path);
  }

  @Override
  public Path resolve(String path) {
    return resolvedProjectRoot.resolve(path);
  }

  @Override
  public boolean isDirectory(Path path) {
    return filesystemParent.isDirectory(projectRoot.resolve(path));
  }

  @Override
  public Path getRootPath() {
    return resolvedProjectRoot;
  }

  @Override
  public DefaultProjectFilesystemView withView(
      Path newRelativeRoot, ImmutableSet<PathMatcher> additionalIgnores) {
    Path newRoot = projectRoot.resolve(newRelativeRoot);
    Path resolvedNewRoot = filesystemParent.resolve(newRoot);
    ImmutableMap.Builder<PathMatcher, Predicate<Path>> mapBuilder =
        ImmutableMap.builderWithExpectedSize(ignoredPaths.size() + additionalIgnores.size());
    mapBuilder.putAll(ignoredPaths);
    for (PathMatcher p : additionalIgnores) {
      mapBuilder.put(p, path -> p.matches(resolvedNewRoot.relativize(path)));
    }
    return new DefaultProjectFilesystemView(
        filesystemParent, newRoot, resolvedNewRoot, mapBuilder.build());
  }

  @Override
  public boolean isIgnored(Path path) {
    return isIgnoredInternal(resolvedProjectRoot.resolve(path).normalize().toAbsolutePath());
  }

  // Tests if the given absolute path is ignored
  private boolean isIgnoredInternal(Path path) {
    for (Predicate<Path> matcher : ignoredPaths.values()) {
      if (matcher.test(path)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void walkRelativeFileTree(
      Path pathRelativeToProjectRoot,
      EnumSet<FileVisitOption> visitOptions,
      FileVisitor<Path> fileVisitor)
      throws IOException {
    filesystemParent.walkFileTreeWithPathMapping(
        projectRoot.resolve(pathRelativeToProjectRoot),
        visitOptions,
        fileVisitor,
        this::shouldExplorePaths,
        this::relativize);
  }

  @Override
  public void walkFileTree(
      Path pathRelativeToProjectRoot, Set<FileVisitOption> options, FileVisitor<Path> fileVisitor)
      throws IOException {
    filesystemParent.walkFileTree(
        projectRoot.resolve(pathRelativeToProjectRoot),
        options,
        fileVisitor,
        this::shouldExplorePaths);
  }

  @Override
  public ImmutableSet<Path> getFilesUnderPath(
      Path pathRelativeToProjectRoot, EnumSet<FileVisitOption> visitOptions) throws IOException {
    return getFilesUnderPath(pathRelativeToProjectRoot, x -> true, visitOptions);
  }

  @Override
  public ImmutableSet<Path> getFilesUnderPath(
      Path pathRelativeToProjectRoot, Predicate<Path> filter, EnumSet<FileVisitOption> visitOptions)
      throws IOException {
    ImmutableSet.Builder<Path> paths = ImmutableSet.builder();
    walkRelativeFileTree(
        pathRelativeToProjectRoot,
        visitOptions,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
            if (filter.test(path)) {
              paths.add(path);
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return paths.build();
  }

  @Override
  public ImmutableCollection<Path> getDirectoryContents(Path pathToUse) throws IOException {
    try (DirectoryStream<Path> stream =
        filesystemParent.getDirectoryContentsStream(
            filesystemParent.getPathForRelativePath(pathToUse))) {
      return FluentIterable.from(stream)
          .filter(this::shouldExplorePaths)
          .transform(absolutePath -> MorePaths.relativize(resolvedProjectRoot, absolutePath))
          .toSortedList(Comparator.naturalOrder());
    }
  }

  private boolean shouldExplorePaths(Path p) {
    return !isIgnoredInternal(p);
  }
}
