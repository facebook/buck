/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util.hashing;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.Hasher;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PathHashing {
  // Utility class, do not instantiate.
  private PathHashing() {}

  private static final Path EMPTY_PATH = Paths.get("");

  public static ImmutableSet<Path> hashPath(
      Hasher hasher,
      ProjectFileHashLoader fileHashLoader,
      ProjectFilesystem projectFilesystem,
      Path root)
      throws IOException {
    Preconditions.checkArgument(
        !root.equals(EMPTY_PATH), "Path to hash (%s) must not be empty", root);
    ImmutableSet.Builder<Path> children = ImmutableSet.builder();
    for (Path path : ImmutableSortedSet.copyOf(projectFilesystem.getFilesUnderPath(root))) {
      StringHashing.hashStringAndLength(hasher, PathFormatter.pathWithUnixSeparators(path));
      if (!root.equals(path)) {
        children.add(root.relativize(path));
      }
      hasher.putBytes(fileHashLoader.get(path).asBytes());
    }
    return children.build();
  }
}
