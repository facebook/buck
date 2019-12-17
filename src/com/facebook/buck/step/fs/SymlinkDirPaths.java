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
package com.facebook.buck.step.fs;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * A {@link SymlinkPaths} symlinking all paths found under a given {@link
 * com.facebook.buck.core.sourcepath.SourcePath} directory.
 */
public class SymlinkDirPaths implements SymlinkPaths {

  private final Path directory;

  public SymlinkDirPaths(Path directory) {
    this.directory = directory;
  }

  @Override
  public void forEachSymlink(SymlinkConsumer consumer) throws IOException {
    Files.walkFileTree(
        directory,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            consumer.accept(directory.relativize(file), file);
            return super.visitFile(file, attrs);
          }
        });
  }
}
