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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellConfig;
import com.facebook.buck.core.cell.CellName;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.CellPathResolverView;
import com.facebook.buck.core.cell.CellProvider;
import com.facebook.buck.core.cell.InvalidCellOverrideException;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.module.BuckModuleManager;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.ToolchainProviderFactory;
import com.facebook.buck.io.filesystem.EmbeddedCellBuckOutInfo;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.rules.keys.config.impl.ConfigRuleKeyConfigurationFactory;
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

/** Creates a {@link CellProvider} to be used in a local (non-distributed) build. */
public class LocalCellProviderFactory {

  /** Create a cell provider at a given root. */
  public static CellProvider create(
      ProjectFilesystem rootFilesystem,
      BuckConfig rootConfig,
      CellConfig rootCellConfigOverrides,
      ImmutableMap<CellName, Path> cellPathMapping,
      CellPathResolver rootCellCellPathResolver,
      BuckModuleManager moduleManager,
      ToolchainProviderFactory toolchainProviderFactory,
      ProjectFilesystemFactory projectFilesystemFactory) {

    ImmutableMap<Path, RawConfig> pathToConfigOverrides;
    try {
      pathToConfigOverrides = rootCellConfigOverrides.getOverridesByPath(cellPathMapping);
    } catch (InvalidCellOverrideException e) {
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
                              rootFilesystem.resolve(
                                  rootFilesystem.getBuckPaths().getEmbeddedCellsBuckOutBaseDir()),
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
                        target ->
                            BuildTargetParser.INSTANCE.parse(
                                target,
                                BuildTargetPatternParser.fullyQualified(),
                                cellPathResolver));

                RuleKeyConfiguration ruleKeyConfiguration =
                    ConfigRuleKeyConfigurationFactory.create(buckConfig, moduleManager);

                ToolchainProvider toolchainProvider =
                    toolchainProviderFactory.create(
                        buckConfig, cellFilesystem, ruleKeyConfiguration);

                // TODO(13777679): cells in other watchman roots do not work correctly.

                return ImmutableCell.of(
                    cellPathResolver.getKnownRoots(),
                    canonicalCellName,
                    cellProvider,
                    toolchainProvider,
                    ruleKeyConfiguration,
                    cellPathResolver,
                    cellFilesystem,
                    buckConfig);
              }
            },
        cellProvider ->
            RootCellFactory.create(
                cellProvider,
                rootCellCellPathResolver,
                toolchainProviderFactory,
                rootFilesystem,
                moduleManager,
                rootConfig));
  }
}
