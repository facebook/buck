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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.sourcepath.ArchiveMemberSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/** Provides utility methods for reading dependency file entries. */
class DefaultClassUsageFileReader {
  /** Utility code, not instantiable */
  private DefaultClassUsageFileReader() {}

  private static ImmutableMap<String, ImmutableList<String>> loadClassUsageMap(Path mapFilePath)
      throws IOException {
    return ObjectMappers.readValue(
        mapFilePath, new TypeReference<ImmutableMap<String, ImmutableList<String>>>() {});
  }

  /**
   * This method loads a class usage file that maps JARs to the list of files within those jars that
   * were used. Given our rule's deps, we determine which of these JARS in the class usage file are
   * actually among the deps of our rule.
   */
  public static ImmutableList<SourcePath> loadFromFile(
      ProjectFilesystem projectFilesystem,
      CellPathResolver cellPathResolver,
      Path classUsageFilePath,
      ImmutableMap<Path, SourcePath> jarPathToSourcePath) {
    ImmutableList.Builder<SourcePath> builder = ImmutableList.builder();
    try {
      ImmutableSet<Map.Entry<String, ImmutableList<String>>> classUsageEntries =
          loadClassUsageMap(classUsageFilePath).entrySet();
      for (Map.Entry<String, ImmutableList<String>> jarUsedClassesEntry : classUsageEntries) {
        Path jarAbsolutePath =
            convertRecordedJarPathToAbsolute(
                projectFilesystem, cellPathResolver, jarUsedClassesEntry.getKey());
        SourcePath sourcePath = jarPathToSourcePath.get(jarAbsolutePath);
        if (sourcePath == null) {
          // This indicates a dependency that wasn't among the deps of the rule; i.e.,
          // it came from the build environment (JDK, Android SDK, etc.)
          continue;
        }

        for (String classAbsolutePath : jarUsedClassesEntry.getValue()) {
          builder.add(ArchiveMemberSourcePath.of(sourcePath, Paths.get(classAbsolutePath)));
        }
      }
    } catch (IOException e) {
      throw new BuckUncheckedExecutionException(
          e,
          "When loading class usage files from %s.",
          projectFilesystem.resolve(classUsageFilePath));
    }
    return builder.build();
  }

  public static ImmutableSet<Path> loadUsedJarsFromFile(
      ProjectFilesystem projectFilesystem,
      CellPathResolver cellPathResolver,
      Path classUsageFilePath)
      throws IOException {
    ImmutableSet.Builder<Path> builder = ImmutableSet.builder();
    ImmutableSet<Map.Entry<String, ImmutableList<String>>> classUsageEntries =
        loadClassUsageMap(classUsageFilePath).entrySet();
    for (Map.Entry<String, ImmutableList<String>> jarUsedClassesEntry : classUsageEntries) {
      Path jarAbsolutePath =
          convertRecordedJarPathToAbsolute(
              projectFilesystem, cellPathResolver, jarUsedClassesEntry.getKey());
      builder.add(jarAbsolutePath);
    }
    return builder.build();
  }

  private static Path convertRecordedJarPathToAbsolute(
      ProjectFilesystem projectFilesystem, CellPathResolver cellPathResolver, String jarPath) {
    Path recordedPath = Paths.get(jarPath);
    Path jarAbsolutePath =
        recordedPath.isAbsolute()
            ? getAbsolutePathForCellRootedPath(recordedPath, cellPathResolver)
            : projectFilesystem.resolve(recordedPath);
    return jarAbsolutePath;
  }

  /**
   * Convert a path rooted in another cell to an absolute path in the filesystem
   *
   * @param cellRootedPath a path beginning with '/cell_name/' followed by a relative path in that
   *     cell
   * @param cellPathResolver the CellPathResolver capable of mapping cell_name to absolute root path
   * @return an absolute path: 'path/to/cell/root/' + 'relative/path/in/cell'
   */
  private static Path getAbsolutePathForCellRootedPath(
      Path cellRootedPath, CellPathResolver cellPathResolver) {
    Preconditions.checkArgument(cellRootedPath.isAbsolute(), "Path must begin with /<cell_name>");
    Iterator<Path> pathIterator = cellRootedPath.iterator();
    Path cellName = pathIterator.next();
    Path relativeToCellRoot = pathIterator.next();
    while (pathIterator.hasNext()) {
      relativeToCellRoot = relativeToCellRoot.resolve(pathIterator.next());
    }
    return cellPathResolver
        .getCellPathOrThrow(Optional.of(cellName.toString()))
        .resolve(relativeToCellRoot);
  }
}
