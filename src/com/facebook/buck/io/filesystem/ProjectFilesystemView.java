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

package com.facebook.buck.io.filesystem;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitor;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.Set;

/**
 * A configured view over the {@link ProjectFilesystem}. All traversal operations on this filesystem
 * behaves according configured ignored paths and according to the configured rootpath.
 */
public interface ProjectFilesystemView {

  /**
   * @param path an absolute path
   * @return true if path is a subdirectory of the root
   */
  boolean isSubdirOf(Path path);

  /**
   * @param path an absolute path to relativize
   * @return a relative path from the path root of this view to the given path
   */
  Path relativize(Path path);

  /**
   * @param path the relative path to resolve
   * @return an absolute path of the given path relative to the root of this view
   */
  Path resolve(Path path);

  /** @see #resolve(Path) * */
  Path resolve(String path);

  /**
   * Checks whether there is a normal file at the specified path
   *
   * @param path relative path to the root
   * @param options whether to resolve symlinks
   * @return true if path is a file
   */
  boolean isFile(Path path, LinkOption... options);

  /**
   * @param path relative path to the root
   * @return true iff the given path maps to a directory
   */
  boolean isDirectory(Path path);

  /**
   * Read basic attributes of a file from a file system
   *
   * @param path relative path to the root
   * @param type type of attributes object
   * @param options whether to resolve symlinks
   * @return File attributes object
   */
  <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
      throws IOException;

  /** @return the absolute path of the root of this view */
  Path getRootPath();

  /**
   * @param newRelativeRoot the new root relative to the current root
   * @param additionalIgnores new ignored paths in addition to the current ignores
   * @return a new View based on the current view
   */
  ProjectFilesystemView withView(Path newRelativeRoot, ImmutableSet<PathMatcher> additionalIgnores);

  /**
   * @param path an relative paths to the root of this filesystem view
   * @return whether the path is ignored by this view
   */
  boolean isIgnored(Path path);

  /**
   * Similar to {@link #walkFileTree(Path, Set, FileVisitor)} fileVisitor receives paths relative to
   * the root of this view
   */
  void walkRelativeFileTree(
      Path pathRelativeToProjectRoot,
      EnumSet<FileVisitOption> visitOptions,
      FileVisitor<Path> fileVisitor)
      throws IOException;

  /**
   * Walks the filesystem starting at a given path relative to the root of this view, and all paths
   * give to {@code fileVisitor} is absolute
   */
  void walkFileTree(
      Path pathRelativeToProjectRoot, Set<FileVisitOption> options, FileVisitor<Path> fileVisitor)
      throws IOException;

  ImmutableSet<Path> getFilesUnderPath(
      Path pathRelativeToProjectRoot, EnumSet<FileVisitOption> visitOptions) throws IOException;

  /**
   * Returns a list of files under the given path relative to the root of this view, filtered both
   * blacklist and the given filter. The returned paths are also relative to the root of this view.
   */
  ImmutableSet<Path> getFilesUnderPath(
      Path pathRelativeToProjectRoot, Predicate<Path> filter, EnumSet<FileVisitOption> visitOptions)
      throws IOException;

  /**
   * Gets a list of paths of the contents of the given directory, obeying the ignores. All paths are
   * relative to the root of this view.
   */
  ImmutableCollection<Path> getDirectoryContents(Path pathToUse) throws IOException;
}
