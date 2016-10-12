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
import com.facebook.buck.io.WatchmanDiagnosticCache;
import com.facebook.buck.util.HumanReadableException;
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

public final class CellProvider {
  private final LoadingCache<Path, Cell> cells;

  /**
   * Create a cell provider with a specific cell loader, and optionally a special factory function
   * for the root cell.
   *
   * The indirection for passing in CellProvider allows cells to reference the current CellProvider
   * object.
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
            "Unexpected checked exception thrown from cell loader.",
            e.getCause());
      }
    } catch (UncheckedExecutionException e) {
      Throwables.propagateIfPossible(e.getCause());
      throw e;
    }
  }

  public ImmutableMap<Path, Cell> getLoadedCells() {
    return ImmutableMap.copyOf(cells.asMap());
  }

  /**
   * Create a cell provider at a given root.
   */
  public static CellProvider createForLocalBuild(
      ProjectFilesystem rootFilesystem,
      Watchman watchman,
      BuckConfig rootConfig,
      CellConfig rootCellConfigOverrides,
      KnownBuildRuleTypesFactory knownBuildRuleTypesFactory,
      WatchmanDiagnosticCache watchmanDiagnosticCache) throws IOException {

    DefaultCellPathResolver rootCellCellPathResolver = new DefaultCellPathResolver(
        rootFilesystem.getRootPath(),
        rootConfig.getEntriesForSection(DefaultCellPathResolver.REPOSITORIES_SECTION));

    ImmutableMap<RelativeCellName, Path> transitiveCellPathMapping =
        rootCellCellPathResolver.getTransitivePathMapping();

    ImmutableMap<Path, RawConfig> pathToConfigOverrides;
    try {
      pathToConfigOverrides =
          rootCellConfigOverrides.getOverridesByPath(transitiveCellPathMapping);
    } catch (CellConfig.MalformedOverridesException e) {
      throw new HumanReadableException(e.getMessage());
    }

    ImmutableSet<Path> allRoots = ImmutableSet.copyOf(transitiveCellPathMapping.values());
    return new CellProvider(
        cellProvider -> new CacheLoader<Path, Cell>() {
          @Override
          public Cell load(Path cellPath) throws IOException, InterruptedException {
            cellPath = cellPath.toRealPath().normalize();

            Preconditions.checkState(
                allRoots.contains(cellPath),
                "Cell %s outside of transitive closure of root cell (%s).",
                cellPath,
                allRoots);

            RawConfig configOverrides = Optional.ofNullable(pathToConfigOverrides.get(cellPath))
                .orElse(RawConfig.of(ImmutableMap.of()));
            Config config = Configs.createDefaultConfig(cellPath, configOverrides);
            DefaultCellPathResolver cellPathResolver =
                new DefaultCellPathResolver(cellPath, config);
            ProjectFilesystem cellFilesystem = new ProjectFilesystem(cellPath, config);

            BuckConfig buckConfig = new BuckConfig(
                config,
                cellFilesystem,
                rootConfig.getArchitecture(),
                rootConfig.getPlatform(),
                rootConfig.getEnvironment(),
                cellPathResolver);

            // TODO(13777679): cells in other watchman roots do not work correctly.

            return new Cell(
                cellPathResolver.getKnownRoots(),
                cellFilesystem,
                watchman,
                buckConfig,
                knownBuildRuleTypesFactory,
                cellProvider,
                watchmanDiagnosticCache);
          }
        },
        cellProvider -> {
          try {
            return new Cell(
                rootCellCellPathResolver.getKnownRoots(),
                rootFilesystem,
                watchman,
                rootConfig,
                knownBuildRuleTypesFactory,
                cellProvider,
                watchmanDiagnosticCache);
          } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while loading root cell", e);
          } catch (IOException e) {
            throw new HumanReadableException("Failed to load root cell", e);
          }
        });
  }

  public static CellProvider createForDistributedBuild(
      ImmutableMap<Path, BuckConfig> cellConfigs,
      ImmutableMap<Path, ProjectFilesystem> cellFilesystems,
      KnownBuildRuleTypesFactory knownBuildRuleTypesFactory,
      WatchmanDiagnosticCache watchmanDiagnosticCache) {
    return new CellProvider(
        cellProvider -> new CacheLoader<Path, Cell>() {
          @Override
          public Cell load(Path cellPath) throws Exception {
            ProjectFilesystem cellFilesystem =
                Preconditions.checkNotNull(cellFilesystems.get(cellPath));
            BuckConfig buckConfig = Preconditions.checkNotNull(cellConfigs.get(cellPath));

            return new Cell(
                cellConfigs.keySet(),
                cellFilesystem,
                Watchman.NULL_WATCHMAN,
                buckConfig,
                knownBuildRuleTypesFactory,
                cellProvider,
                watchmanDiagnosticCache
            );
          }
        },
        null);
  }
}
