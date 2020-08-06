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

package com.facebook.buck.core.cell.impl;

import com.facebook.buck.core.cell.AbstractCellPathResolver;
import com.facebook.buck.core.cell.CellName;
import com.facebook.buck.core.cell.NewCellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.util.config.Config;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;
import org.immutables.value.Value;

@BuckStyleValue
public abstract class DefaultCellPathResolver extends AbstractCellPathResolver {

  private static final Logger LOG = Logger.get(DefaultCellPathResolver.class);

  public static final String REPOSITORIES_SECTION = "repositories";

  public abstract AbsPath getRoot();

  @Override
  public abstract ImmutableMap<String, AbsPath> getCellPathsByRootCellExternalName();

  @Override
  public abstract CellNameResolver getCellNameResolver();

  @Override
  public abstract NewCellPathResolver getNewCellPathResolver();

  /** This gives the names as they are specified in the root cell. */
  @Value.Lazy
  public ImmutableMap<Path, String> getExternalNamesInRootCell() {
    return getCellPathsByRootCellExternalName().entrySet().stream()
        .collect(
            Collectors.collectingAndThen(
                Collectors.toMap(
                    t -> t.getValue().getPath(),
                    Map.Entry::getKey,
                    BinaryOperator.minBy(Comparator.<String>naturalOrder())),
                ImmutableMap::copyOf));
  }

  @Value.Lazy
  public ImmutableMap<CellName, AbsPath> getPathMapping() {
    return bootstrapPathMapping(getRoot(), getCellPathsByRootCellExternalName());
  }

  @Value.Lazy
  @Override
  public ImmutableSortedSet<AbsPath> getKnownRoots() {
    return super.getKnownRoots();
  }

  private static ImmutableMap<String, AbsPath> sortCellPaths(Map<String, AbsPath> cellPaths) {
    return cellPaths.entrySet().stream()
        .sorted(Comparator.comparing(Map.Entry::getValue, AbsPath.comparator()))
        // TODO(nga): this does not sort anything
        .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  public static DefaultCellPathResolver create(
      AbsPath root,
      Map<String, AbsPath> cellPaths,
      CellNameResolver cellNameResolver,
      NewCellPathResolver newCellPathResolver) {
    return ImmutableDefaultCellPathResolver.of(
        root, sortCellPaths(cellPaths), cellNameResolver, newCellPathResolver);
  }

  /**
   * Creates a DefaultCellPathResolver using the mappings in the provided {@link Config}. This is
   * the preferred way to create a DefaultCellPathResolver.
   */
  public static DefaultCellPathResolver create(AbsPath root, Config config) {
    NewCellPathResolver newCellPathResolver = CellMappingsFactory.create(root, config);
    CellNameResolver cellNameResolver =
        CellMappingsFactory.createCellNameResolver(root, config, newCellPathResolver);
    return ImmutableDefaultCellPathResolver.create(
        root,
        sortCellPaths(
            getCellPathsFromConfigRepositoriesSection(root, config.get(REPOSITORIES_SECTION))),
        cellNameResolver,
        newCellPathResolver);
  }

  static ImmutableMap<String, AbsPath> getCellPathsFromConfigRepositoriesSection(
      AbsPath root, ImmutableMap<String, String> repositoriesSection) {
    return ImmutableMap.copyOf(
        Maps.transformValues(
            repositoriesSection,
            input ->
                root.resolve(MorePaths.expandHomeDir(root.getFileSystem().getPath(input)))
                    .normalize()));
  }

  /**
   * Helper function to precompute the {@link CellName} to Path mapping
   *
   * @return Map of cell name to path.
   */
  private static ImmutableMap<CellName, AbsPath> bootstrapPathMapping(
      AbsPath root, ImmutableMap<String, AbsPath> cellPaths) {
    ImmutableMap.Builder<CellName, AbsPath> builder = ImmutableMap.builder();
    // Add the implicit empty root cell
    builder.put(CellName.ROOT_CELL_NAME, root);
    HashSet<AbsPath> seenPaths = new HashSet<>();

    ImmutableSortedSet<String> sortedCellNames =
        ImmutableSortedSet.<String>naturalOrder().addAll(cellPaths.keySet()).build();
    for (String cellName : sortedCellNames) {
      AbsPath cellRoot =
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

  public static ImmutableMap<CellName, AbsPath> bootstrapPathMapping(AbsPath root, Config config) {
    return bootstrapPathMapping(
        root, getCellPathsFromConfigRepositoriesSection(root, config.get(REPOSITORIES_SECTION)));
  }

  @Override
  public Optional<Path> getCellPath(Optional<String> cellName) {
    if (cellName.isPresent()) {
      return Optional.ofNullable(getCellPathsByRootCellExternalName().get(cellName.get()))
          .map(AbsPath::getPath);
    } else {
      return Optional.of(getRoot().getPath());
    }
  }

  @Override
  public Optional<String> getCanonicalCellName(Path cellPath) {
    if (cellPath.equals(getRoot().getPath())) {
      return Optional.empty();
    } else {
      String name = getExternalNamesInRootCell().get(cellPath);
      if (name == null) {
        throw new IllegalArgumentException("Unknown cell path: " + cellPath);
      }
      return Optional.of(name);
    }
  }
}
