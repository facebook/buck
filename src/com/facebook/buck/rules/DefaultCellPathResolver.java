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

package com.facebook.buck.rules;

import com.facebook.buck.config.Config;
import com.facebook.buck.config.Configs;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

public class DefaultCellPathResolver implements CellPathResolver {
  private static final Logger LOG = Logger.get(DefaultCellPathResolver.class);

  public static final String REPOSITORIES_SECTION = "repositories";

  private final Path root;
  private final ImmutableMap<String, Path> cellPaths;
  private final ImmutableMap<Path, String> canonicalNames;

  public DefaultCellPathResolver(Path root, ImmutableMap<String, Path> cellPaths) {
    this.root = root;
    this.cellPaths = cellPaths;
    this.canonicalNames =
        cellPaths
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

  public DefaultCellPathResolver(Path root, Config config) {
    this(root, getCellPathsFromConfigRepositoriesSection(root, config.get(REPOSITORIES_SECTION)));
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
   * Recursively walks configuration files to find all possible {@link Cell} locations.
   *
   * @return MultiMap of Path to cell name. The map will contain multiple names for a path if that
   *     cell is reachable through different paths from the current cell.
   */
  public ImmutableMap<RelativeCellName, Path> getTransitivePathMapping() {
    ImmutableMap.Builder<RelativeCellName, Path> builder = ImmutableMap.builder();
    builder.put(RelativeCellName.of(ImmutableList.of()), root);

    HashSet<Path> seenPaths = new HashSet<>();
    seenPaths.add(root);

    constructFullMapping(builder, seenPaths, RelativeCellName.of(ImmutableList.of()), this);
    return builder.build();
  }

  public Path getRoot() {
    return root;
  }

  private ImmutableMap<String, Path> getPartialMapping() {
    ImmutableSortedSet<String> sortedCellNames =
        ImmutableSortedSet.<String>naturalOrder().addAll(cellPaths.keySet()).build();
    ImmutableMap.Builder<String, Path> rootsMap = ImmutableMap.builder();
    for (String cellName : sortedCellNames) {
      Path cellRoot = Preconditions.checkNotNull(getCellPath(cellName));
      rootsMap.put(cellName, cellRoot);
    }
    return rootsMap.build();
  }

  private static void constructFullMapping(
      ImmutableMap.Builder<RelativeCellName, Path> result,
      Set<Path> pathStack,
      RelativeCellName parentCellPath,
      DefaultCellPathResolver parentStub) {
    ImmutableMap<String, Path> partialMapping = parentStub.getPartialMapping();
    for (Map.Entry<String, Path> entry : partialMapping.entrySet()) {
      Path cellRoot = entry.getValue().normalize();
      try {
        cellRoot = cellRoot.toRealPath().normalize();
      } catch (IOException e) {
        LOG.warn("cellroot [" + cellRoot + "] does not exist in filesystem");
      }

      // Do not recurse into previously visited Cell roots. It's OK for cell references to form
      // cycles as long as the targets don't form a cycle.
      // We intentionally allow for the map to contain entries whose Config objects can't be
      // created. These are still technically reachable and will not cause problems as long as none
      // of the BuildTargets in the build reference them.
      if (pathStack.contains(cellRoot)) {
        continue;
      }
      pathStack.add(cellRoot);

      RelativeCellName relativeCellName = parentCellPath.withAppendedComponent(entry.getKey());
      result.put(relativeCellName, cellRoot);

      Config config;
      try {
        // We don't support overriding repositories from the command line so creating the config
        // with no overrides is OK.
        config = Configs.createDefaultConfig(cellRoot);
      } catch (IOException e) {
        LOG.debug(e, "Error when constructing cell, skipping path %s", cellRoot);
        continue;
      }
      constructFullMapping(
          result, pathStack, relativeCellName, new DefaultCellPathResolver(cellRoot, config));
      pathStack.remove(cellRoot);
    }
  }

  private Path getCellPath(String cellName) {
    Path path = cellPaths.get(cellName);
    if (path == null) {
      throw new HumanReadableException(
          "In cell rooted at %s: cell named '%s' is not defined.", root, cellName);
    }
    return path;
  }

  @Override
  public Path getCellPath(Optional<String> cellName) {
    if (!cellName.isPresent()) {
      return root;
    }
    return getCellPath(cellName.get());
  }

  @Override
  public ImmutableMap<String, Path> getCellPaths() {
    return cellPaths;
  }

  @Override
  public Optional<String> getCanonicalCellName(Path cellPath) {
    if (cellPath.equals(root)) {
      return Optional.empty();
    } else {
      String name = canonicalNames.get(cellPath);
      if (name == null) {
        throw new IllegalArgumentException("Unknown cell path: " + cellPath);
      }
      return Optional.of(name);
    }
  }
}
