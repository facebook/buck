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

package com.facebook.buck.java.abi;


import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/**
 * A {@link Walker} which operates on all files in a directory in sorted order.
 */
class DirectoryWalker implements Walker {
  private final Path root;

  public DirectoryWalker(Path root) {
    this.root = Preconditions.checkNotNull(root);
  }

  @Override
  public void walk(final FileAction onFile) throws IOException {

    // First find all dir entries and sort them to create a deterministic iteration order.
    final Set<Path> files = Sets.newTreeSet();
    SimpleFileVisitor<Path> visitor = new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Path relativized = root.relativize(file);
        files.add(relativized);


        return FileVisitResult.CONTINUE;
      }
    };
    Files.walkFileTree(root, visitor);

    // Now call the action on each of the files.
    for (Path path : files) {
      try (InputStream is = Files.newInputStream(root.resolve(path));
           BufferedInputStream bis = new BufferedInputStream(is)) {
        onFile.visit(path, bis);
      }
    }
  }
}
