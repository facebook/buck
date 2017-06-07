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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.config.CellConfig;
import com.facebook.buck.config.Config;
import com.facebook.buck.config.Configs;
import com.facebook.buck.config.RawConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.immutables.value.Value;

public final class CellProvider {
  private final LoadingCache<Path, Cell> cells;

  /**
   * Create a cell provider with a specific cell loader, and optionally a special factory function
   * for the root cell.
   *
   * <p>The indirection for passing in CellProvider allows cells to reference the current
   * CellProvider object.
   */
  private CellProvider(
      Function<CellProvider, CacheLoader<Path, Cell>> cellCacheLoader,
      @Nullable Function<CellProvider, Cell> rootCellLoader) {
    this.cells = CacheBuilder.newBuilder().build(cellCacheLoader.apply(this));
    if (rootCellLoader != null) {
      Cell rootCell = rootCellLoader.apply(this);
      cells.put(rootCell.getRoot(), rootCell);
    }
  }

  public Cell getCellByPath(Path path) {
    try {
      return cells.get(path);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof IOException) {
        throw new HumanReadableException(e.getCause(), "Failed to load Cell at: %s", path);
      } else if (e.getCause() instanceof InterruptedException) {
        throw new RuntimeException("Interrupted while loading Cell: " + path, e);
      } else {
        throw new IllegalStateException(
            "Unexpected checked exception thrown from cell loader.", e.getCause());
      }
    } catch (UncheckedExecutionException e) {
      Throwables.throwIfUnchecked(e.getCause());
      throw e;
    }
  }

  public ImmutableMap<Path, Cell> getLoadedCells() {
    return ImmutableMap.copyOf(cells.asMap());
  }

  /** Create a cell provider at a given root. */
  public static CellProvider createForLocalBuild(
      ProjectFilesystem rootFilesystem,
      Watchman watchman,
      BuckConfig rootConfig,
      CellConfig rootCellConfigOverrides,
      KnownBuildRuleTypesFactory knownBuildRuleTypesFactory)
      throws IOException {

    DefaultCellPathResolver rootCellCellPathResolver =
        new DefaultCellPathResolver(rootFilesystem.getRootPath(), rootConfig.getConfig());

    ImmutableMap<RelativeCellName, Path> transitiveCellPathMapping =
        rootCellCellPathResolver.getTransitivePathMapping();

    ImmutableMap<Path, RawConfig> pathToConfigOverrides;
    try {
      pathToConfigOverrides = rootCellConfigOverrides.getOverridesByPath(transitiveCellPathMapping);
    } catch (CellConfig.MalformedOverridesException e) {
      throw new HumanReadableException(e.getMessage());
    }

    ImmutableSet<Path> allRoots = ImmutableSet.copyOf(transitiveCellPathMapping.values());
    return new CellProvider(
        cellProvider ->
            new CacheLoader<Path, Cell>() {
              @Override
              public Cell load(Path cellPath) throws IOException, InterruptedException {
                Path normalizedCellPath = cellPath.toRealPath().normalize();

                Preconditions.checkState(
                    allRoots.contains(normalizedCellPath),
                    "Cell %s outside of transitive closure of root cell (%s).",
                    normalizedCellPath,
                    allRoots);

                RawConfig configOverrides =
                    Optional.ofNullable(pathToConfigOverrides.get(normalizedCellPath))
                        .orElse(RawConfig.of(ImmutableMap.of()));
                Config config = Configs.createDefaultConfig(normalizedCellPath, configOverrides);

                ImmutableMap<String, Path> cellMapping =
                    DefaultCellPathResolver.getCellPathsFromConfigRepositoriesSection(
                        cellPath, config.get(DefaultCellPathResolver.REPOSITORIES_SECTION));

                // The cell should only contain a subset of cell mappings of the root cell.
                cellMapping.forEach(
                    (name, path) -> {
                      Path pathInRootResolver = rootCellCellPathResolver.getCellPaths().get(name);
                      if (pathInRootResolver == null) {
                        throw new HumanReadableException(
                            "In the config of %s:  %s.%s must exist in the root cell's cell mappings.",
                            cellPath.toString(),
                            DefaultCellPathResolver.REPOSITORIES_SECTION,
                            name);
                      } else if (!pathInRootResolver.equals(path)) {
                        throw new HumanReadableException(
                            "In the config of %s:  %s.%s must point to the same directory as the root "
                                + "cell's cell mapping: (root) %s != (current) %s",
                            cellPath.toString(),
                            DefaultCellPathResolver.REPOSITORIES_SECTION,
                            name,
                            pathInRootResolver,
                            path);
                      }
                    });

                CellPathResolver cellPathResolver =
                    new CellPathResolverView(
                        rootCellCellPathResolver, cellMapping.keySet(), cellPath);

                ProjectFilesystem cellFilesystem =
                    new ProjectFilesystem(normalizedCellPath, config);

                BuckConfig buckConfig =
                    new BuckConfig(
                        config,
                        cellFilesystem,
                        rootConfig.getArchitecture(),
                        rootConfig.getPlatform(),
                        rootConfig.getEnvironment(),
                        cellPathResolver);

                // TODO(13777679): cells in other watchman roots do not work correctly.

                return new Cell(
                    getKnownRoots(cellPathResolver),
                    cellPathResolver.getCanonicalCellName(normalizedCellPath),
                    cellFilesystem,
                    watchman,
                    buckConfig,
                    knownBuildRuleTypesFactory,
                    cellProvider);
              }
            },
        cellProvider -> {
          Preconditions.checkState(
              !rootCellCellPathResolver
                  .getCanonicalCellName(rootFilesystem.getRootPath())
                  .isPresent(),
              "Root cell should be nameless");
          return new Cell(
              getKnownRoots(rootCellCellPathResolver),
              Optional.empty(),
              rootFilesystem,
              watchman,
              rootConfig,
              knownBuildRuleTypesFactory,
              cellProvider);
        });
  }

  public static CellProvider createForDistributedBuild(
      ImmutableMap<Path, DistBuildCellParams> cellParams,
      KnownBuildRuleTypesFactory knownBuildRuleTypesFactory) {
    return new CellProvider(
        cellProvider ->
            CacheLoader.from(
                cellPath -> {
                  DistBuildCellParams cellParam =
                      Preconditions.checkNotNull(cellParams.get(cellPath));
                  return new Cell(
                      cellParams.keySet(),
                      // Distributed builds don't care about cell names, use a sentinel value that will
                      // show up if it actually does care about them.
                      cellParam.getCanonicalName(),
                      cellParam.getFilesystem(),
                      Watchman.NULL_WATCHMAN,
                      cellParam.getConfig(),
                      knownBuildRuleTypesFactory,
                      cellProvider);
                }),
        null);
  }

  @Value.Immutable(copy = false)
  @BuckStyleTuple
  interface AbstractDistBuildCellParams {
    BuckConfig getConfig();

    ProjectFilesystem getFilesystem();

    Optional<String> getCanonicalName();
  }

  private static ImmutableSet<Path> getKnownRoots(CellPathResolver resolver) {
    return ImmutableSet.<Path>builder()
        .addAll(resolver.getCellPaths().values())
        .add(resolver.getCellPath(Optional.empty()))
        .build();
  }
}
