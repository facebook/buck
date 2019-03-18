/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.core.files;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.Queue;
import javax.annotation.Nullable;

/**
 * Iterator which recursively traverses {@link FileTree} searching for specific file name Returns
 * relative Path of that file including file name itself This type of iterator is useful for finding
 * all build files under some path
 */
public class FileTreeFileNameIterator implements Iterator<Path> {

  private final Path fileName;
  private final Queue<FileTree> queue = new LinkedList<>();
  @Nullable private Path nextPath = null;

  private FileTreeFileNameIterator(FileTree fileTree, String fileName) {
    this.fileName = Paths.get(fileName);
    queue.add(fileTree);
  }

  /**
   * Create new instance of {@link FileTreeFileNameIterator}
   *
   * @param fileTree File tree to recursively iterate
   * @param fileName File name to search for, for example "BUCK"
   */
  public static FileTreeFileNameIterator of(FileTree fileTree, String fileName) {
    return new FileTreeFileNameIterator(fileTree, fileName);
  }

  /**
   * Create new instance of {@link FileTreeFileNameIterator} and return it as {@link Iterable} which
   * can be used in for loop
   *
   * @param fileTree File tree to recursively iterate
   * @param fileName File name to search for, for example "BUCK"
   */
  public static Iterable<Path> ofIterable(FileTree fileTree, String fileName) {
    return () -> of(fileTree, fileName);
  }

  @Override
  public boolean hasNext() {
    if (nextPath == null) {
      moveNext();
    }
    return nextPath != null;
  }

  @Override
  public Path next() {
    if (nextPath != null) {
      Path result = nextPath;
      nextPath = null;
      return result;
    }

    moveNext();

    if (nextPath == null) {
      throw new NoSuchElementException();
    }

    return next();
  }

  private void moveNext() {
    while (true) {
      FileTree next = queue.poll();

      if (next == null) {
        nextPath = null;
        return;
      }

      // recurse down
      next.getChildren().forEach((path, childtree) -> queue.add(childtree));

      Path candidate = next.getPath().resolve(fileName);
      if (next.getDirectoryList().getFiles().contains(candidate)) {
        nextPath = candidate;
        return;
      }
    }
  }
}
