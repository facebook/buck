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

package com.facebook.buck.cxx.toolchain.objectfile;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.file.FileContentsScrubber;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class OsoSymbolsContentsScrubber implements FileContentsScrubber {

  private final Optional<ImmutableMap<Path, Path>> cellRootMap;
  private final Optional<ImmutableSet<Path>> exemptPaths;
  private final Optional<AbsPath> exemptTargetsListPath;
  private final Optional<ImmutableMultimap<String, AbsPath>> targetToOutputPathMap;

  public OsoSymbolsContentsScrubber(ImmutableMap<Path, Path> cellRootMap) {
    this(Optional.of(cellRootMap), Optional.empty(), Optional.empty(), Optional.empty());
  }

  public OsoSymbolsContentsScrubber(ImmutableSet<Path> exemptPaths) {
    this(Optional.empty(), Optional.of(exemptPaths), Optional.empty(), Optional.empty());
  }

  public OsoSymbolsContentsScrubber(
      Optional<AbsPath> focusedTargetsPath,
      Optional<ImmutableMultimap<String, AbsPath>> targetToOutputPathMap) {
    this(Optional.empty(), Optional.empty(), focusedTargetsPath, targetToOutputPathMap);
  }

  private OsoSymbolsContentsScrubber(
      Optional<ImmutableMap<Path, Path>> cellRootMap,
      Optional<ImmutableSet<Path>> exemptPaths,
      Optional<AbsPath> exemptTargetsListPath,
      Optional<ImmutableMultimap<String, AbsPath>> targetToOutputPathMap) {
    if (exemptTargetsListPath.isPresent()) {
      Preconditions.checkArgument(targetToOutputPathMap.isPresent());
    }

    this.cellRootMap = cellRootMap;
    this.exemptPaths = exemptPaths;
    this.exemptTargetsListPath = exemptTargetsListPath;
    this.targetToOutputPathMap = targetToOutputPathMap;
  }

  @Override
  public void scrubFile(
      FileChannel file,
      Path filePath,
      ProcessExecutor processExecutor,
      ImmutableMap<String, String> environment)
      throws IOException, ScrubException, InterruptedException {
    if (!Machos.isMacho(file)) {
      return;
    }

    Optional<ImmutableSet<Path>> exemptPaths = getExemptPaths();

    // If cellRootMap is present we're doing N_OSO scrubbing to
    // swap absolute paths for relative paths.
    // If exemptPaths is present we're scrubbing all paths to fake
    // paths other than exempt paths to implement focused debugging.
    // If neither is present we don't perform file scrubbing.
    if (cellRootMap.isPresent() || exemptPaths.isPresent()) {
      try {
        Machos.relativizeOsoSymbols(file, cellRootMap, exemptPaths);
      } catch (Machos.MachoException e) {
        throw new ScrubException(e.getMessage());
      }
    }
  }

  private Optional<ImmutableSet<Path>> getExemptPaths() throws IOException {
    if (this.exemptPaths.isPresent()) {
      return this.exemptPaths;
    } else if (this.exemptTargetsListPath.isPresent()) {
      List<String> exemptTargets =
          ObjectMappers.READER.readValue(
              ObjectMappers.createParser(exemptTargetsListPath.get().getPath()),
              new TypeReference<List<String>>() {});

      ImmutableMultimap<String, AbsPath> targetToOutputPath = targetToOutputPathMap.get();

      Set<Path> exemptPathsFromTargets =
          exemptTargets.stream()
              .filter(targetToOutputPath::containsKey)
              .map(targetToOutputPath::get)
              .flatMap(Collection::stream)
              .map(AbsPath::getPath)
              .collect(Collectors.toSet());

      if (!exemptPathsFromTargets.isEmpty()) {
        return Optional.of(ImmutableSet.copyOf(exemptPathsFromTargets));
      } else {
        return Optional.empty();
      }
    } else {
      return Optional.empty();
    }
  }

  private static int findEndIndexForCommonPathComponents(ImmutableList<Path> absoluteCellPaths) {
    // Finds the index (excl) for which all path components are the same. For example, if we have
    // /a/b/c and /a/b/d, index = 2 (excl) as path components at index 0 ("a") and index 1 ("b")
    // are equal for all paths.
    //
    // Invariant: All path components up to pathNameIndex (exclusive) are the same for all paths
    //            in `absoluteCellPaths`
    int pathNameIndex = 0;
    while (true) {
      for (int i = 0; i < absoluteCellPaths.size(); ++i) {
        Path absCellPath = absoluteCellPaths.get(i);
        if (pathNameIndex >= absCellPath.getNameCount()) {
          return pathNameIndex;
        }

        if (i > 0) {
          // As we compare against the components of the first path, only compare paths apart from
          // the first one (no need to compare against itself)
          Path firstCellPathComponent = absoluteCellPaths.get(0).getName(pathNameIndex);
          Path currentCellPathComponent = absCellPath.getName(pathNameIndex);
          if (!firstCellPathComponent.equals(currentCellPathComponent)) {
            return pathNameIndex;
          }
        }
      }

      // All path components at the current index are the same, so move to the next one
      ++pathNameIndex;
    }
  }

  private static Optional<String> computeOsoPrefixForCellRootMapWithoutSuffixCheck(
      ImmutableMap<Path, Path> cellRootMap) {
    if (cellRootMap.isEmpty()) {
      return Optional.empty();
    }

    ImmutableList<Path> absoluteCellPaths =
        cellRootMap.keySet().stream().collect(ImmutableList.toImmutableList());

    int pathNameIndex = findEndIndexForCommonPathComponents(absoluteCellPaths);
    if (pathNameIndex == 0) {
      // All paths are relative to the root, so there's no common prefix that needs to be stripped.
      // Stripping the root slash does not provide any value, so we can skip doing that.
      return Optional.empty();
    }

    Path anyAbsCellPath = cellRootMap.keySet().iterator().next();
    // We require the returned path to be absolute but subpath() returns a relative path, so it
    // must be turned into an absolute path relative to the root of the fs tree.
    Path relativeToRootCommonPath = anyAbsCellPath.subpath(0, pathNameIndex);
    Path absCommonPath = Paths.get(File.separator).resolve(relativeToRootCommonPath);
    return Optional.of(absCommonPath.toString());
  }

  /**
   * Computes the -oso_prefix parameter to be passed to ld64, so that it relativizes N_OSO path
   * entries. This is useful for creating reproducible builds and ensuring object files are checkout
   * independent.
   *
   * <p>Note that -oso_prefix cannot be used to relativize against an arbitrary path but just strip
   * a common path prefix. Consequently, it cannot represent the cellRootMap if there are non-nested
   * cells.
   *
   * <p>The slight issue arises because cellRootMap can request paths to be relativized in a way
   * that cannot be expressed using prefix stripping (e.g., "/path/to/cell/a" and "/path/to/cell/b"
   * trying to relativize against "/path/to/cell/a" means any paths with a prefix "/path/to/cell/b"
   * have to be _mapped_ to "../b" which is not expressible using the parameter).
   *
   * <p>Instead, the case is handled by relativizing against the common parent of all cells. This is
   * not a problem in practice except for having to adjust the working dir when attaching * a
   * debugger, so it can locate the object files.
   */
  public static Optional<String> computeOsoPrefixForCellRootMap(
      ImmutableMap<Path, Path> cellRootMap) {
    Optional<String> osoPrefix = computeOsoPrefixForCellRootMapWithoutSuffixCheck(cellRootMap);
    if (osoPrefix.isPresent() && !osoPrefix.get().endsWith(File.separator)) {
      // Paths in the object files must be relative, i.e., _not_ starting with the path separator.
      // This means that the -oso_prefix itself _must_ have a trailing separator which will ensure
      // all N_OSO entries do _not_ begin with a separator (i.e., they will be relative and not
      // absolute).
      return Optional.of(osoPrefix.get() + File.separator);
    }

    return osoPrefix;
  }
}
