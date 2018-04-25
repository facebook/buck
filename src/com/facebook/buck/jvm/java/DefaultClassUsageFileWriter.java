/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableSetMultimap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

public final class DefaultClassUsageFileWriter implements ClassUsageFileWriter {
  @Override
  public void writeFile(
      ClassUsageTracker tracker,
      Path relativePath,
      ProjectFilesystem filesystem,
      CellPathResolver cellPathResolver) {
    ImmutableSetMultimap<Path, Path> classUsageMap = tracker.getClassUsageMap();
    try {
      ObjectMappers.WRITER.writeValue(
          filesystem.resolve(relativePath).toFile(),
          relativizeMap(classUsageMap, filesystem, cellPathResolver));
    } catch (IOException e) {
      throw new HumanReadableException(e, "Unable to write used classes file.");
    }
  }

  private static ImmutableSetMultimap<Path, Path> relativizeMap(
      ImmutableSetMultimap<Path, Path> classUsageMap,
      ProjectFilesystem filesystem,
      CellPathResolver cellPathResolver) {
    ImmutableSetMultimap.Builder<Path, Path> builder = ImmutableSetMultimap.builder();

    // Ensure deterministic ordering.
    builder.orderKeysBy(Comparator.naturalOrder());
    builder.orderValuesBy(Comparator.naturalOrder());

    for (Map.Entry<Path, Collection<Path>> jarClassesEntry : classUsageMap.asMap().entrySet()) {
      Path jarAbsolutePath = jarClassesEntry.getKey();
      // Don't include jars that are outside of the project
      // Paths outside the project would make these class usage files problematic for caching.
      // Fortunately, such paths are also not interesting for the main use cases that these files
      // address, namely understanding the dependencies one java rule has on others. Jar files
      // outside the project are coming from a build tool (e.g. JDK or Android SDK).
      // Here we first check to see if the path is inside the root filesystem of the build
      // If not, we check to see if it's under one of the other cell roots.
      // If the the absolute path does not reside under any cell root, we exclude it
      filesystem
          .getPathRelativeToProjectRoot(jarAbsolutePath)
          .map(Optional::of)
          .orElseGet(() -> getCrossCellPath(jarAbsolutePath, cellPathResolver))
          .ifPresent(projectPath -> builder.putAll(projectPath, jarClassesEntry.getValue()));
    }

    return builder.build();
  }

  /**
   * Try to convert an absolute path to a path rooted in a cell, represented by an absolute path
   * where the first directory element is the cell name. For example, if the absolute path is <code>
   * /foo/bar/buck-out/gen/baz/a.jar</code> and there exists a cell in the config <code>
   * cell2 = /foo/bar</code> then the path returned will be <code>/cell2/buck-out/gen/baz/a.jar
   * </code>. If the given absolute path is not relative to any of the cell roots in the resolver,
   * Optional.empty() is returned.
   */
  private static Optional<Path> getCrossCellPath(Path jarAbsolutePath, CellPathResolver resolver) {
    for (Map.Entry<String, Path> cellEntry : resolver.getCellPaths().entrySet()) {
      Path cellRoot = cellEntry.getValue();
      if (jarAbsolutePath.startsWith(cellRoot)) {
        Path relativePath = cellRoot.relativize(jarAbsolutePath);
        // We use an absolute path to represent a path rooted in another cell
        Path cellNameRoot = cellRoot.getRoot().resolve(cellEntry.getKey());
        return Optional.of(cellNameRoot.resolve(relativePath));
      }
    }
    return Optional.empty();
  }
}
