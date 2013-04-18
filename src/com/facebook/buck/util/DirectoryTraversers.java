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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.File;

public class DirectoryTraversers {

  private DirectoryTraversers() {}

  private static final DirectoryTraversers instance = new DirectoryTraversers();

  public static DirectoryTraversers getInstance() {
    return instance;
  }

  /**
   * Aggregates a set of all paths to files in the specified directories. All file paths will be
   * relative to the project root.
   * @param paths to directories to traverse. Each path must be relative to the project root, and
   *     the directory may not contain symlinks to other directories.
   * @param traverser
   * @return a set of all files in paths, sorted alphabetically
   */
  public ImmutableSortedSet<String> findFiles(ImmutableSet<String> paths,
      DirectoryTraverser traverser) {
    final ImmutableSortedSet.Builder<String> allFiles = ImmutableSortedSet.naturalOrder();
    for (String pathToDirectory : paths) {
      final String directoryPathWithTrailingSlash =
          pathToDirectory.endsWith("/") ? pathToDirectory : pathToDirectory + '/';
      traverser.traverse(new DirectoryTraversal(new File(pathToDirectory)) {
        @Override
        public void visit(File file, String relativePath) {
          allFiles.add(directoryPathWithTrailingSlash + relativePath);
        }
      });
    }
    return allFiles.build();
  }
}
