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

import com.facebook.buck.core.cell.CellPathExtractor;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.sourcepath.ArchiveMemberSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.annotations.VisibleForTesting;
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
import java.util.function.BinaryOperator;
import java.util.stream.Stream;

/** Provides utility methods for reading dependency file entries. */
public class DefaultClassUsageFileReader {
  /** Utility code, not instantiable */
  private DefaultClassUsageFileReader() {}

  private static ImmutableMap<String, ImmutableMap<String, Integer>> loadClassUsageMap(
      Path mapFilePath) {
    if (!mapFilePath.toFile().exists()) {
      return ImmutableMap.of();
    }
    try {
      return ObjectMappers.readValue(
          mapFilePath, new TypeReference<ImmutableMap<String, ImmutableMap<String, Integer>>>() {});
    } catch (IOException e) {
      throw new BuckUncheckedExecutionException(
          e, "When loading class usage map from %s.", mapFilePath);
    }
  }

  /**
   * This method loads a class usage file that maps JARs to the list of files within those jars that
   * were used. Given our rule's deps, we determine which of these JARS in the class usage file are
   * actually among the deps of our rule.
   */
  public static ImmutableList<SourcePath> loadFromFile(
      AbsPath root,
      CellPathResolver cellPathResolver,
      ImmutableList<Path> classUsageFilePaths,
      ImmutableMap<Path, SourcePath> jarPathToSourcePath) {
    ImmutableList.Builder<SourcePath> builder = ImmutableList.builder();

    for (Map.Entry<String, ImmutableMap<String, Integer>> jarUsedClassesEntry :
        getClassUsageEntries(classUsageFilePaths).entrySet()) {
      AbsPath jarAbsolutePath =
          convertRecordedJarPathToAbsolute(root, cellPathResolver, jarUsedClassesEntry.getKey());
      SourcePath sourcePath = jarPathToSourcePath.get(jarAbsolutePath.getPath());
      if (sourcePath == null) {
        // This indicates a dependency that wasn't among the deps of the rule; i.e.,
        // it came from the build environment (JDK, Android SDK, etc.)
        continue;
      }

      for (String classAbsolutePath : jarUsedClassesEntry.getValue().keySet()) {
        builder.add(ArchiveMemberSourcePath.of(sourcePath, Paths.get(classAbsolutePath)));
      }
    }
    return builder.build();
  }

  public static ImmutableSet<AbsPath> loadUsedJarsFromFile(
      AbsPath root,
      CellPathExtractor cellPathExtractor,
      ImmutableList<AbsPath> classUsageFilePaths,
      boolean doUltralightChecking) {
    ImmutableSet.Builder<AbsPath> builder = ImmutableSet.builder();

    for (Map.Entry<String, ImmutableMap<String, Integer>> entry :
        getClassUsageEntries(
                classUsageFilePaths.stream()
                    .map(AbsPath::getPath)
                    .collect(ImmutableList.toImmutableList()))
            .entrySet()) {
      if (doUltralightChecking && isUltralightOnlyDependency(entry.getValue())) {
        continue;
      }

      String jarPath = entry.getKey();
      AbsPath jarAbsolutePath = convertRecordedJarPathToAbsolute(root, cellPathExtractor, jarPath);
      builder.add(jarAbsolutePath);
    }
    return builder.build();
  }

  private static ImmutableMap<String, ImmutableMap<String, Integer>> getClassUsageEntries(
      ImmutableList<Path> classUsageFilePaths) {
    ImmutableMap<String, ImmutableMap<String, Integer>> classUsageEntries = ImmutableMap.of();
    for (Path classUsageFilePath : classUsageFilePaths) {
      ImmutableMap<String, ImmutableMap<String, Integer>> classUsageMap =
          loadClassUsageMap(classUsageFilePath);
      classUsageEntries =
          merge(classUsageEntries, classUsageMap, (a, b) -> merge(a, b, Integer::sum));
    }
    return classUsageEntries;
  }

  /** Merges two {@link ImmutableMap}s using provided merge function */
  private static <K, V> ImmutableMap<K, V> merge(
      ImmutableMap<K, V> a, ImmutableMap<K, V> b, BinaryOperator<V> mergeFunction) {
    return Stream.concat(a.entrySet().stream(), b.entrySet().stream())
        .collect(
            ImmutableMap.toImmutableMap(
                ImmutableMap.Entry::getKey, ImmutableMap.Entry::getValue, mergeFunction));
  }

  /**
   * Wild hack that allows us to remove Ultralight-only dependencies from the unused deps checker. A
   * dependency is Ultralight-only if we only loaded the Module-related classes.
   *
   * @deprecated T62272524
   */
  @Deprecated
  @VisibleForTesting
  static boolean isUltralightOnlyDependency(ImmutableMap<String, Integer> jarUsedClassesEntry) {
    return jarUsedClassesEntry.size() == 2
        && jarUsedClassesEntry.keySet().stream()
            .anyMatch(path -> path.startsWith("_STRIPPED_RESOURCES/ultralight/modules/"))
        && jarUsedClassesEntry.entrySet().stream().allMatch(entry -> entry.getValue().equals(1));
  }

  private static AbsPath convertRecordedJarPathToAbsolute(
      AbsPath root, CellPathExtractor cellPathExtractor, String jarPath) {
    Path recordedPath = Paths.get(jarPath);
    return recordedPath.isAbsolute()
        ? getAbsolutePathForCellRootedPath(recordedPath, cellPathExtractor)
        : AbsPath.of(ProjectFilesystemUtils.getPathForRelativePath(root, recordedPath));
  }

  /**
   * Convert a path rooted in another cell to an absolute path in the filesystem
   *
   * @param cellRootedPath a path beginning with '/cell_name/' followed by a relative path in that
   *     cell
   * @param cellPathExtractor the CellPathExtractor capable of mapping cell_name to absolute root
   *     path
   * @return an absolute path: 'path/to/cell/root/' + 'relative/path/in/cell'
   */
  private static AbsPath getAbsolutePathForCellRootedPath(
      Path cellRootedPath, CellPathExtractor cellPathExtractor) {
    Preconditions.checkArgument(cellRootedPath.isAbsolute(), "Path must begin with /<cell_name>");
    Iterator<Path> pathIterator = cellRootedPath.iterator();
    Path cellNamePath = pathIterator.next();
    Path relativeToCellRoot = pathIterator.next();
    while (pathIterator.hasNext()) {
      relativeToCellRoot = relativeToCellRoot.resolve(pathIterator.next());
    }
    String cellName = cellNamePath.toString();
    CanonicalCellName canonicalCellName =
        cellName.equals(DefaultClassUsageFileWriter.ROOT_CELL_IDENTIFIER)
            ? CanonicalCellName.rootCell()
            /**
             * as we write only {@link CanonicalCellName} based paths in {@link
             * DefaultClassUsageFileWriter#getCrossCellPath} then we could read value and explicitly
             * convert it into {@link CanonicalCellName}
             */
            : CanonicalCellName.of(Optional.of(cellName));
    return cellPathExtractor.getCellPathOrThrow(canonicalCellName).resolve(relativeToCellRoot);
  }
}
