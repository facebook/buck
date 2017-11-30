/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.io.WatchmanFactory;
import com.facebook.buck.io.filesystem.EmbeddedCellBuckOutInfo;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.rules.keys.impl.ConfigRuleKeyConfigurationFactory;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.toolchain.impl.DefaultToolchainProvider;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.config.RawConfig;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheLoader;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.pf4j.PluginManager;

public class CellProviderFactory {

  /** Create a cell provider at a given root. */
  public static CellProvider createForLocalBuild(
      ProjectFilesystem rootFilesystem,
      Watchman watchman,
      BuckConfig rootConfig,
      CellConfig rootCellConfigOverrides,
      PluginManager pluginManager,
      ImmutableMap<String, String> environment,
      ProcessExecutor processExecutor,
      ExecutableFinder executableFinder,
      ProjectFilesystemFactory projectFilesystemFactory) {

    DefaultCellPathResolver rootCellCellPathResolver =
        DefaultCellPathResolver.of(rootFilesystem.getRootPath(), rootConfig.getConfig());

    ImmutableMap<RelativeCellName, Path> cellPathMapping =
        rootCellCellPathResolver.getPathMapping();

    ImmutableMap<Path, RawConfig> pathToConfigOverrides;
    try {
      pathToConfigOverrides = rootCellConfigOverrides.getOverridesByPath(cellPathMapping);
    } catch (CellConfig.MalformedOverridesException e) {
      throw new HumanReadableException(e.getMessage());
    }

    ImmutableSet<Path> allRoots = ImmutableSet.copyOf(cellPathMapping.values());
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

                Optional<EmbeddedCellBuckOutInfo> embeddedCellBuckOutInfo = Optional.empty();
                Optional<String> canonicalCellName =
                    cellPathResolver.getCanonicalCellName(normalizedCellPath);
                if (rootConfig.isEmbeddedCellBuckOutEnabled() && canonicalCellName.isPresent()) {
                  embeddedCellBuckOutInfo =
                      Optional.of(
                          EmbeddedCellBuckOutInfo.of(
                              rootFilesystem.resolve(rootFilesystem.getBuckPaths().getBuckOut()),
                              canonicalCellName.get()));
                }
                ProjectFilesystem cellFilesystem =
                    projectFilesystemFactory.createProjectFilesystem(
                        normalizedCellPath, config, embeddedCellBuckOutInfo);

                BuckConfig buckConfig =
                    new BuckConfig(
                        config,
                        cellFilesystem,
                        rootConfig.getArchitecture(),
                        rootConfig.getPlatform(),
                        rootConfig.getEnvironment(),
                        cellPathResolver);

                ToolchainProvider toolchainProvider =
                    new DefaultToolchainProvider(
                        pluginManager,
                        environment,
                        buckConfig,
                        cellFilesystem,
                        processExecutor,
                        executableFinder);

                // TODO(13777679): cells in other watchman roots do not work correctly.

                return Cell.of(
                    getKnownRoots(cellPathResolver),
                    canonicalCellName,
                    cellFilesystem,
                    watchman,
                    buckConfig,
                    cellProvider,
                    toolchainProvider,
                    ConfigRuleKeyConfigurationFactory.create(buckConfig));
              }
            },
        cellProvider -> {
          Preconditions.checkState(
              !rootCellCellPathResolver
                  .getCanonicalCellName(rootFilesystem.getRootPath())
                  .isPresent(),
              "Root cell should be nameless");
          ToolchainProvider toolchainProvider =
              new DefaultToolchainProvider(
                  pluginManager,
                  environment,
                  rootConfig,
                  rootFilesystem,
                  processExecutor,
                  executableFinder);
          return Cell.of(
              getKnownRoots(rootCellCellPathResolver),
              Optional.empty(),
              rootFilesystem,
              watchman,
              rootConfig,
              cellProvider,
              toolchainProvider,
              ConfigRuleKeyConfigurationFactory.create(rootConfig));
        });
  }

  public static CellProvider createForDistributedBuild(
      ImmutableMap<Path, DistBuildCellParams> cellParams) {
    return new CellProvider(
        cellProvider ->
            CacheLoader.from(
                cellPath -> {
                  DistBuildCellParams cellParam =
                      Preconditions.checkNotNull(cellParams.get(cellPath));

                  ToolchainProvider toolchainProvider =
                      new DefaultToolchainProvider(
                          cellParam.getPluginManager(),
                          cellParam.getEnvironment(),
                          cellParam.getConfig(),
                          cellParam.getFilesystem(),
                          cellParam.getProcessExecutor(),
                          cellParam.getExecutableFinder());

                  return Cell.of(
                      cellParams.keySet(),
                      // Distributed builds don't care about cell names, use a sentinel value that
                      // will show up if it actually does care about them.
                      cellParam.getCanonicalName(),
                      cellParam.getFilesystem(),
                      WatchmanFactory.NULL_WATCHMAN,
                      cellParam.getConfig(),
                      cellProvider,
                      toolchainProvider,
                      ConfigRuleKeyConfigurationFactory.create(cellParam.getConfig()));
                }),
        null);
  }

  private static ImmutableSet<Path> getKnownRoots(CellPathResolver resolver) {
    return ImmutableSet.<Path>builder()
        .addAll(resolver.getCellPaths().values())
        .add(
            resolver
                .getCellPath(Optional.empty())
                .orElseThrow(() -> new AssertionError("Root cell path should always be known.")))
        .build();
  }
}
