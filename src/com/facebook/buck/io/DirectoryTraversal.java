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

package com.facebook.buck.io;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;

/**
 * Traverses all files in a directory. This class <em>will</em> follow symlinks.
 */
public abstract class DirectoryTraversal {

  private final Path root;
  private final ImmutableSet<Path> ignorePaths;

  /** @param root must be a directory */
  public DirectoryTraversal(Path root, ImmutableSet<Path> ignorePaths) {
    this.root = root;
    this.ignorePaths = ignorePaths;
  }

  public DirectoryTraversal(Path root) {
    this(root, ImmutableSet.<Path>of());
  }

  public Path getRoot() {
    return root;
  }

  public final void traverse() throws IOException {
    Preconditions.checkState(Files.isDirectory(root), "Must be a directory: %s", root);

    FileVisitor<Path> visitor = new FileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
          throws IOException {
        if (ignorePaths.contains(root.relativize(dir))) {
          return FileVisitResult.SKIP_SUBTREE;
        } else {
          visitDirectory(dir, root.relativize(dir).toString());
          return FileVisitResult.CONTINUE;
        }
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        visit(file, root.relativize(file).toString());
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
        return FileVisitResult.CONTINUE;
      }
    };

    Files.walkFileTree(
        root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), /* maxDepth */ Integer.MAX_VALUE,
        visitor);
  }

  /**
   * @param file an ordinary file (not a directory)
   * @param relativePath a path such as "foo.txt" or "foo/bar.txt"
   */
  public abstract void visit(Path file, String relativePath) throws IOException;

  /**
   * @param directory a directory.
   * @param relativePath a path such as "foo" or "foo" with no trailing slash.
   */
  public void visitDirectory(Path directory, String relativePath) throws IOException {
    // Do nothing by default.
  }

  public static void main(String[] args) throws IOException {
    Path directory = Paths.get(args[0]);
    new DirectoryTraversal(directory) {

      @Override
      public void visit(Path file, String relativePath) {
        System.out.println(relativePath);
      }
    }.traverse();
  }
}
