/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.cell.impl;

import com.facebook.buck.core.cell.AbstractCellPathResolver;
import com.facebook.buck.core.cell.CellName;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.Configs;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
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
  public ImmutableMap<CellName, Path> getPathMapping() {
    return bootstrapPathMapping(getRoot(), getCellPaths());
  }

  @Value.Lazy
  @Override
  public ImmutableSortedSet<Path> getKnownRoots() {
    return super.getKnownRoots();
  }

  private static ImmutableMap<String, ? extends Path> sortCellPaths(
      Map<String, ? extends Path> cellPaths) {
    return cellPaths
        .entrySet()
        .stream()
        .sorted(Comparator.comparing(Map.Entry::getValue))
        .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  public static DefaultCellPathResolver of(Path root, Map<String, ? extends Path> cellPaths) {
    return ImmutableDefaultCellPathResolver.of(root, sortCellPaths(cellPaths));
  }

  public static DefaultCellPathResolver of(Path root, Config config) {
    try {
      return ImmutableDefaultCellPathResolver.of(
          root,
          sortCellPaths(
              getCellPathsRecursively(root, config)));
    } catch (IOException error) {
      throw new HumanReadableException(error.getMessage());
    }
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

  static ImmutableMap<String, Path> getCellPathsRecursively(final Path root, final Config rootConfig) throws IOException {
    ImmutableMap<String, Path> solution = ImmutableMap.copyOf(
        Maps.transformValues(
            rootConfig.get(REPOSITORIES_SECTION),
            input ->
                root.resolve(MorePaths.expandHomeDir(root.getFileSystem().getPath(input)))
                    .normalize()));

    final Queue<Path> pending = new LinkedList<>();
    final HashSet<Path> visited = new HashSet<>();

    pending.addAll(solution.values());

    while (!pending.isEmpty()) {
      final Path path = pending.remove();

      visited.add(path);

      final Path configFilePath = Configs.getMainConfigurationFile(path);

      final ImmutableMap<String, String> repositoriesSection =
          Configs.parseConfigFile(configFilePath)
              .getOrDefault(REPOSITORIES_SECTION, ImmutableMap.of());

      final ImmutableMap<String, Path> cellPaths =
          getCellPathsFromConfigRepositoriesSection(path, repositoriesSection);

      for (final Map.Entry<String, Path> cellPath : cellPaths.entrySet()) {
        if (solution.containsKey(cellPath.getKey())) {
          final Path existingPath = solution.get(cellPath.getKey());

          if (!cellPath.getValue().equals(existingPath)) {
            throw new HumanReadableException(
                "repositories.%s has conflicting mappings %s and %s",
                cellPath.getKey(),
                existingPath,
                cellPath.getValue());
          }
        } else {
          if (!visited.contains(cellPath.getValue())) {
            pending.add(cellPath.getValue());
          }

          solution = new ImmutableMap.Builder<String, Path>()
              .putAll(solution)
              .put(cellPath)
              .build();
        }
      }
    }

    return solution;
  }

  /**
   * Helper function to precompute the {@link CellName} to Path mapping
   *
   * @return Map of cell name to path.
   */
  private static ImmutableMap<CellName, Path> bootstrapPathMapping(
      Path root, ImmutableMap<String, Path> cellPaths) {
    ImmutableMap.Builder<CellName, Path> builder = ImmutableMap.builder();
    // Add the implicit empty root cell
    builder.put(CellName.ROOT_CELL_NAME, root);
    HashSet<Path> seenPaths = new HashSet<>();

    ImmutableSortedSet<String> sortedCellNames =
        ImmutableSortedSet.<String>naturalOrder().addAll(cellPaths.keySet()).build();

    for (String cellName : sortedCellNames) {
      Path cellRoot =
          Objects.requireNonNull(
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
      builder.put(CellName.of(cellName), cellRoot);
      seenPaths.add(cellRoot);
    }
    return builder.build();
  }

  public static ImmutableMap<CellName, Path> bootstrapPathMapping(Path root, Config config) throws IOException {
    return bootstrapPathMapping(
        root, getCellPathsRecursively(root, config));
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
