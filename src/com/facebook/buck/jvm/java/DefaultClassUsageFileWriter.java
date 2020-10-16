/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public final class DefaultClassUsageFileWriter implements ClassUsageFileWriter {
  public static final String ROOT_CELL_IDENTIFIER = "_";

  @Override
  public void writeFile(
      ClassUsageTracker tracker,
      Path relativePath,
      AbsPath rootPath,
      RelPath configuredBuckOut,
      CellPathResolver cellPathResolver) {
    ImmutableMap<Path, Map<Path, Integer>> classUsageMap = tracker.getClassUsageMap();
    try {
      Path parent = relativePath.getParent();
      Preconditions.checkState(
          ProjectFilesystemUtils.exists(rootPath, parent), "directory must exist: %s", parent);
      ObjectMappers.WRITER.writeValue(
          rootPath.resolve(relativePath).toFile(),
          relativizeMap(classUsageMap, rootPath, configuredBuckOut, cellPathResolver));
    } catch (IOException e) {
      throw new HumanReadableException(e, "Unable to write used classes file.");
    }
  }

  private static ImmutableSortedMap<Path, Map<Path, Integer>> relativizeMap(
      ImmutableMap<Path, Map<Path, Integer>> classUsageMap,
      AbsPath rootPath,
      RelPath configuredBuckOut,
      CellPathResolver cellPathResolver) {
    ImmutableSortedMap.Builder<Path, Map<Path, Integer>> builder =
        ImmutableSortedMap.naturalOrder();

    for (Map.Entry<Path, Map<Path, Integer>> jarClassesEntry : classUsageMap.entrySet()) {
      Path jarAbsolutePath = jarClassesEntry.getKey();
      // Don't include jars that are outside of the project
      // Paths outside the project would make these class usage files problematic for caching.
      // Fortunately, such paths are also not interesting for the main use cases that these files
      // address, namely understanding the dependencies one java rule has on others. Jar files
      // outside the project are coming from a build tool (e.g. JDK or Android SDK).
      // Here we first check to see if the path is inside the root filesystem of the build
      // If not, we check to see if it's under one of the other cell roots.
      // If the the absolute path does not reside under any cell root, we exclude it
      ProjectFilesystemUtils.getPathRelativeToProjectRoot(
              rootPath, configuredBuckOut, jarAbsolutePath)
          .map(Optional::of)
          .orElseGet(() -> getCrossCellPath(jarAbsolutePath, cellPathResolver))
          .ifPresent(
              projectPath ->
                  builder.put(projectPath, ImmutableSortedMap.copyOf(jarClassesEntry.getValue())));
    }

    return builder.build();
  }

  /**
   * Try to convert an absolute path to a path rooted in a cell, represented by an absolute path
   * where the first directory element is the cell name (or "_" for the root cell). For example, if
   * the absolute path is <code>/foo/bar/buck-out/gen/baz/a.jar</code> and there exists a cell in
   * the config <code>cell2 = /foo/bar</code> then the path returned will be <code>
   * /cell2/buck-out/gen/baz/a.jar</code>. If the given absolute path is not relative to any of the
   * cell roots in the resolver, Optional.empty() is returned.
   */
  private static Optional<Path> getCrossCellPath(Path jarAbsolutePath, CellPathResolver resolver) {
    // TODO(cjhopman): This is wrong if a cell ends up depending on something in another cell that
    // it doesn't have a mapping for :o
    for (AbsPath cellRoot : resolver.getKnownRoots()) {
      if (jarAbsolutePath.startsWith(cellRoot.getPath())) {
        RelPath relativePath = cellRoot.relativize(jarAbsolutePath);
        Optional<String> cellName = resolver.getCanonicalCellName(cellRoot);
        // We use an absolute path to represent a path rooted in another cell
        AbsPath cellNameRoot = cellRoot.getRoot().resolve(cellName.orElse(ROOT_CELL_IDENTIFIER));
        return Optional.of(cellNameRoot.resolve(relativePath).getPath());
      }
    }
    return Optional.empty();
  }
}
