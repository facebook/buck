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

package com.facebook.buck.hashing;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;

import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.Funnels;
import com.google.common.hash.Hasher;
import com.google.common.io.ByteStreams;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.nio.file.Path;

import java.util.Set;

public class PathHashing {
  // Utility class, do not instantiate.
  private PathHashing() { }

  /**
   * Iterates recursively over all files under {@code paths}, sorts
   * the filenames, and updates the {@link Hasher} with the names and
   * contents of all files inside.
   */
  public static void hashPaths(
      Hasher hasher,
      ProjectFilesystem projectFilesystem,
      Set<Path> paths) throws IOException {
    try (final OutputStream hasherOutputStream = Funnels.asOutputStream(hasher)) {
      for (Path path : walkedPathsInSortedOrder(projectFilesystem, paths)) {
        StringHashing.hashStringAndLength(hasher, MorePaths.pathWithUnixSeparators(path));
        hasher.putLong(projectFilesystem.getFileSize(path));
        try (InputStream inputStream = projectFilesystem.newFileInputStream(path)) {
          ByteStreams.copy(inputStream, hasherOutputStream);
        }
      }
    }
  }

  private static ImmutableSortedSet<Path> walkedPathsInSortedOrder(
      ProjectFilesystem projectFilesystem,
      Set<Path> pathsToWalk) throws IOException {
    final ImmutableSortedSet.Builder<Path> walkedPaths = ImmutableSortedSet.naturalOrder();
    for (Path pathToWalk : pathsToWalk) {
      walkedPaths.addAll(projectFilesystem.getFilesUnderPath(pathToWalk));
    }
    return walkedPaths.build();
  }
}
