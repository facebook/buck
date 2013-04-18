/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.util;

import com.google.common.base.Preconditions;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Traverses all files in a directory. This class <em>will</em> follow symlinks. Unfortunately,
 * Java 6 is not "symlink aware". In Java 7, this class could be replaced with
 * java.nio.file.Files.walkFileTree.
 * <p>
 * In the event where symlinks should be traversed, consider shelling out to Python and using
 * os.walk(), which provides the option to traverse symlinks.
 */
public abstract class DirectoryTraversal {

  private final File root;

  /** @param root must be a directory with no cyclic symlinks as children */
  public DirectoryTraversal(File root) {
    this.root = Preconditions.checkNotNull(root);
  }

  public File getRoot() {
    return root;
  }

  private static class DirectoryWithRelativePath {
    private final File directory;
    private final String relativePath;
    private DirectoryWithRelativePath(File directory, String relativePath) {
      this.directory = directory;
      this.relativePath = relativePath;
    }
  }

  public final void traverse() {
    Preconditions.checkState(root.isDirectory(), "Must be a directory: %s", root);

    Deque<DirectoryWithRelativePath> directoriesToVisit =
        new ArrayDeque<DirectoryWithRelativePath>();
    directoriesToVisit.add(new DirectoryWithRelativePath(root, ""));
    while (!directoriesToVisit.isEmpty()) {
      DirectoryWithRelativePath directoryWithRelativePath = directoriesToVisit.pop();
      String relativePath = directoryWithRelativePath.relativePath;
      for (File entry : directoryWithRelativePath.directory.listFiles()) {
        if (entry.isDirectory()) {
          String directoryPath = relativePath + (relativePath.isEmpty() ? "" : "/")
              + entry.getName();
          directoriesToVisit.push(new DirectoryWithRelativePath(entry, directoryPath));
        } else {
          // We do not assert that entry.isFile() because it may be a symlink to a file.
          visit(entry, relativePath + (relativePath.isEmpty() ? "" : "/") + entry.getName());
        }
      }
    }
  }

  /**
   * @param file an ordinary file (not a directory)
   * @param relativePath a path such as "foo.txt" or "foo/bar.txt"
   */
  public abstract void visit(File file, String relativePath);

  public static void main(String[] args) {
    File directory = new File(args[0]);
    new DirectoryTraversal(directory) {

      @Override
      public void visit(File file, String relativePath) {
        System.out.println(relativePath);
      }
    }.traverse();
  }
}
