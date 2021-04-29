/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.core.filesystems.AbsPath;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Used to add "complex" inputs to a FileTreeBuilder.
 *
 * <p>Unlike FileTreeBuilder, FileInputsAdder will accept calls to add entire directories or to add
 * paths where a parent directory is a symlink. It will convert these complicated cases into the
 * appropriate calls on the underlying FileTreeBuilder.
 */
class FileInputsAdder {
  /** Interface for consuming the found inputs and for accessing some filesystem information. */
  interface Delegate {
    void addFile(AbsPath path) throws IOException;

    void addEmptyDirectory(AbsPath path) throws IOException;

    void addSymlink(AbsPath symlink, Path fixedTarget);

    /**
     * Returns the iterable elements which are the entries in the directory.
     *
     * @param target The path to the directory
     * @return directory contents or {@code null} if {@code target} is not a directory
     * @throws IOException if an I/O error occurs when opening the directory
     */
    @Nullable
    ImmutableList<Path> getDirectoryContents(Path target) throws IOException;

    /**
     * Returns the iterable elements which are the entries in the directory.
     *
     * @param target The path to the directory
     * @return directory contents or {@code null} if {@code target} is not a directory
     * @throws IOException if an I/O error occurs when opening the directory
     */
    @Nullable
    default Iterable<AbsPath> getDirectoryContents(AbsPath target) throws IOException {
      ImmutableList<Path> contents = getDirectoryContents(target.getPath());
      return contents != null
          ? contents.stream().map(AbsPath::of).collect(ImmutableList.toImmutableList())
          : null;
    }

    @Nullable
    Path getSymlinkTarget(Path path) throws IOException;
  }

  private final Set<AbsPath> addedInputs = new HashSet<>();
  private final Map<AbsPath, AbsPath> map = new HashMap<>();
  private final Delegate delegate;
  private final AbsPath cellPathPrefix;

  FileInputsAdder(Delegate delegate, AbsPath cellPathPrefix) {
    this.delegate = delegate;
    this.cellPathPrefix = cellPathPrefix;
  }

  /**
   * addInput() can accept a directory, and in that case it should add all the recursive children.
   * Also, addInput() may be called multiple times with the same path, or with a child or a parent
   * of a path that has already been added. To prevent repeatedly iterating over directory contents,
   * we maintain a cache of which paths have been added as inputs.
   */
  void addInput(AbsPath path) throws IOException {
    if (addedInputs.contains(path)) {
      return;
    }
    addedInputs.add(path);

    if (!path.startsWith(cellPathPrefix)) {
      // TODO(cjhopman): Should we map absolute paths to platform requirements?
      return;
    }

    AbsPath target = addSingleInput(path);

    if (target.startsWith(cellPathPrefix)) {
      Iterable<AbsPath> children = delegate.getDirectoryContents(target);
      /// skip if is not a directory
      if (children == null) {
        return;
      }

      if (Iterables.isEmpty(children)) {
        delegate.addEmptyDirectory(target);
      } else {
        for (AbsPath child : children) {
          addInput(child);
        }
      }
    }
  }

  /**
   * addSingleInput() may be called with the path to either a file or a directory (either of which
   * may be symlinks or have a symlink as one of its parents).
   *
   * <p>addSingleInput() returns the "canonical" path. i.e. it resolves all symlinks (except those
   * in parents of cellPathPrefix).
   *
   * <p>addSingleInput() ensures that all the symlinks in parents of this path are added to the
   * underlying FileTreeBuilder, and if path itself is a regular file, it too will be added to the
   * FileTreeBuilder.
   */
  private AbsPath addSingleInput(AbsPath path) throws IOException {
    if (map.containsKey(path)) {
      return map.get(path);
    }

    Preconditions.checkArgument(path.normalize().equals(path));

    if (!path.startsWith(cellPathPrefix)) {
      map.put(path, path);
      return path;
    }

    AbsPath parent = path.getParent();
    if (parent.getPath().getNameCount() != cellPathPrefix.getPath().getNameCount()) {
      parent = addSingleInput(parent);
    }

    if (!parent.equals(path.getParent())) {
      // Some parent is a symlink, add the target.
      AbsPath target = addSingleInput(parent.resolve(path.getFileName()));
      map.put(path, target);
      return target;
    }

    Path symlinkTarget = delegate.getSymlinkTarget(path.getPath());
    if (symlinkTarget != null) {
      AbsPath resolvedTarget = path.getParent().resolve(symlinkTarget).normalize();

      boolean contained = resolvedTarget.startsWith(cellPathPrefix);
      Path fixedTarget = resolvedTarget.getPath();
      if (contained) {
        fixedTarget = parent.relativize(resolvedTarget).getPath();
      }
      delegate.addSymlink(path, fixedTarget);

      AbsPath target = contained ? addSingleInput(resolvedTarget) : resolvedTarget;
      map.put(path, target);
      return target;
    }

    if (Files.isRegularFile(path.getPath())) {
      delegate.addFile(path);
    }
    map.put(path, path);
    return path;
  }

  /**
   * A simple base delegate implementation when nothing special needs to be done for the filesystem
   * operations.
   */
  public abstract static class AbstractDelegate implements Delegate {
    @Override
    public ImmutableList<Path> getDirectoryContents(Path target) throws IOException {
      if (!Files.isDirectory(target)) {
        return null;
      }
      try (Stream<Path> listing = Files.list(target)) {
        return listing.collect(ImmutableList.toImmutableList());
      }
    }

    @Override
    public Path getSymlinkTarget(Path path) throws IOException {
      return Files.isSymbolicLink(path) ? Files.readSymbolicLink(path) : null;
    }
  }
}
