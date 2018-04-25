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

package com.facebook.buck.core.cell;

import com.facebook.buck.core.cell.name.RelativeCellName;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.config.Config;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;
import org.immutables.value.Value;

@Value.Immutable(builder = false, copy = false)
public abstract class DefaultCellPathResolver extends AbstractCellPathResolver {

  private static final Logger LOG = Logger.get(DefaultCellPathResolver.class);

  static final String REPOSITORIES_SECTION = "repositories";

  @Value.Parameter
  public abstract Path getRoot();

  @Override
  @Value.Parameter
  public abstract ImmutableMap<String, Path> getCellPaths();

  @Value.Lazy
  public ImmutableMap<Path, String> getCanonicalNames() {
    return getCellPaths()
        .entrySet()
        .stream()
        .collect(
            Collectors.collectingAndThen(
                Collectors.toMap(
                    Map.Entry::getValue,
                    Map.Entry::getKey,
                    BinaryOperator.minBy(Comparator.<String>naturalOrder())),
                ImmutableMap::copyOf));
  }

  @Value.Lazy
  public ImmutableMap<RelativeCellName, Path> getPathMapping() {
    return bootstrapPathMapping(getRoot(), getCellPaths());
  }

  @Value.Lazy
  @Override
  public ImmutableSet<Path> getKnownRoots() {
    return super.getKnownRoots();
  }

  public static DefaultCellPathResolver of(Path root, Map<String, ? extends Path> cellPaths) {
    return ImmutableDefaultCellPathResolver.of(root, cellPaths);
  }

  public static DefaultCellPathResolver of(Path root, Config config) {
    return ImmutableDefaultCellPathResolver.of(
        root, getCellPathsFromConfigRepositoriesSection(root, config.get(REPOSITORIES_SECTION)));
  }

  static ImmutableMap<String, Path> getCellPathsFromConfigRepositoriesSection(
      Path root, ImmutableMap<String, String> repositoriesSection) {
    return ImmutableMap.copyOf(
        Maps.transformValues(
            repositoriesSection,
            input ->
                root.resolve(MorePaths.expandHomeDir(root.getFileSystem().getPath(input)))
                    .normalize()));
  }

  /**
   * Helper function to precompute the {@link RelativeCellName} to Path mapping
   *
   * @return Map of cell name to path.
   */
  private static ImmutableMap<RelativeCellName, Path> bootstrapPathMapping(
      Path root, ImmutableMap<String, Path> cellPaths) {
    ImmutableMap.Builder<RelativeCellName, Path> builder = ImmutableMap.builder();
    // Add the implicit empty root cell
    builder.put(RelativeCellName.of(ImmutableList.of()), root);
    HashSet<Path> seenPaths = new HashSet<>();

    ImmutableSortedSet<String> sortedCellNames =
        ImmutableSortedSet.<String>naturalOrder().addAll(cellPaths.keySet()).build();
    for (String cellName : sortedCellNames) {
      Path cellRoot =
          Preconditions.checkNotNull(
              cellPaths.get(cellName),
              "cellName is derived from the map, get() should always return a value.");
      try {
        cellRoot = cellRoot.toRealPath().normalize();
      } catch (IOException e) {
        LOG.warn("cellroot [" + cellRoot + "] does not exist in filesystem");
      }
      if (seenPaths.contains(cellRoot)) {
        continue;
      }
      builder.put(RelativeCellName.of(ImmutableList.of(cellName)), cellRoot);
      seenPaths.add(cellRoot);
    }
    return builder.build();
  }

  public static ImmutableMap<RelativeCellName, Path> bootstrapPathMapping(
      Path root, Config config) {
    return bootstrapPathMapping(
        root, getCellPathsFromConfigRepositoriesSection(root, config.get(REPOSITORIES_SECTION)));
  }

  @Override
  public Optional<Path> getCellPath(Optional<String> cellName) {
    if (cellName.isPresent()) {
      return Optional.ofNullable(getCellPaths().get(cellName.get()));
    } else {
      return Optional.of(getRoot());
    }
  }

  @Override
  public Optional<String> getCanonicalCellName(Path cellPath) {
    if (cellPath.equals(getRoot())) {
      return Optional.empty();
    } else {
      String name = getCanonicalNames().get(cellPath);
      if (name == null) {
        throw new IllegalArgumentException("Unknown cell path: " + cellPath);
      }
      return Optional.of(name);
    }
  }
}
