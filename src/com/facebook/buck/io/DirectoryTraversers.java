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

import com.google.common.collect.ImmutableSortedSet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class DirectoryTraversers {

  private DirectoryTraversers() {}

  private static final DirectoryTraversers instance = new DirectoryTraversers();

  public static DirectoryTraversers getInstance() {
    return instance;
  }

  /**
   * Aggregates a set of all paths to files in the specified directories. All file paths will be
   * relative to the project root.
   * @param pathToDirectory path to directory to traverse. The directory may not contain symlinks to
   *     other directories.
   * @param traverser
   * @return a set of all files in paths, sorted alphabetically
   */
  public ImmutableSortedSet<Path> findFiles(final String pathToDirectory,
      DirectoryTraverser traverser) throws IOException {
    final ImmutableSortedSet.Builder<Path> allFiles = ImmutableSortedSet.naturalOrder();

    traverser.traverse(new DirectoryTraversal(new File(pathToDirectory)) {
      @Override
      public void visit(File file, String relativePath) {
        allFiles.add(Paths.get(pathToDirectory, relativePath));
      }
    });

    return allFiles.build();
  }
}
