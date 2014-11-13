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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

class DirectoryWalker implements Walker {
  private final Path root;

  public DirectoryWalker(Path root) {
    this.root = Preconditions.checkNotNull(root);
  }

  @Override
  public void walk(final FileAction onFile) throws IOException {
    SimpleFileVisitor<Path> visitor = new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Path relativized = root.relativize(file);

        try (
            InputStream is = Files.newInputStream(file);
          BufferedInputStream bis = new BufferedInputStream(is)) {
          onFile.visit(relativized, bis);
        }

        return FileVisitResult.CONTINUE;
      }
    };
    Files.walkFileTree(root, visitor);
  }
}
