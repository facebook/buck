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

import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellConfig;
import com.facebook.buck.core.cell.CellName;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.CellPathResolverView;
import com.facebook.buck.core.cell.CellProvider;
import com.facebook.buck.core.cell.InvalidCellOverrideException;
import com.facebook.buck.core.cell.NewCellPathResolver;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.module.BuckModuleManager;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.ToolchainProviderFactory;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.EmbeddedCellBuckOutInfo;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.rules.keys.config.impl.ConfigRuleKeyConfigurationFactory;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.config.RawConfig;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheLoader;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.util.Optional;

/** Creates a {@link CellProvider} to be used in a local (non-distributed) build. */
public class LocalCellProviderFactory {

  /** Create a cell provider at a given root. */
  public static CellProvider create(
      ProjectFilesystem rootFilesystem,
      BuckConfig rootConfig,
      CellConfig rootCellConfigOverrides,
      ImmutableMap<CellName, AbsPath> cellPathMapping,
      CellPathResolver rootCellCellPathResolver,
      BuckModuleManager moduleManager,
      ToolchainProviderFactory toolchainProviderFactory,
      ProjectFilesystemFactory projectFilesystemFactory,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory) {

    ImmutableMap<AbsPath, RawConfig> pathToConfigOverrides;
    try {
      pathToConfigOverrides = rootCellConfigOverrides.getOverridesByPath(cellPathMapping);
    } catch (InvalidCellOverrideException e) {
      throw new HumanReadableException(e.getMessage());
    }

    ImmutableSet<AbsPath> allRoots = ImmutableSet.copyOf(cellPathMapping.values());

    NewCellPathResolver newCellPathResolver =
        CellMappingsFactory.create(rootFilesystem.getRootPath(), rootConfig.getConfig());

    return new CellProvider(
        newCellPathResolver,
        cellProvider ->
            new CacheLoader<AbsPath, Cell>() {
              @Override
              public Cell load(AbsPath cellPath) throws IOException {
                AbsPath normalizedCellPath = cellPath.toRealPath().normalize();

                Preconditions.checkState(
                    allRoots.contains(normalizedCellPath),
                    "Cell %s outside of transitive closure of root cell (%s).",
                    normalizedCellPath,
                    allRoots);

                RawConfig configOverrides =
                    Optional.ofNullable(pathToConfigOverrides.get(normalizedCellPath))
                        .orElse(RawConfig.of(ImmutableMap.of()));
                Config config =
                    Configs.createDefaultConfig(normalizedCellPath.getPath(), configOverrides);

                ImmutableMap<String, AbsPath> cellMapping =
                    DefaultCellPathResolver.getCellPathsFromConfigRepositoriesSection(
                        cellPath, config.get(DefaultCellPathResolver.REPOSITORIES_SECTION));

                // The cell should only contain a subset of cell mappings of the root cell.
                cellMapping.forEach(
                    (name, path) -> {
                      AbsPath pathInRootResolver =
                          rootCellCellPathResolver.getCellPathsByRootCellExternalName().get(name);
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
                CellNameResolver cellNameResolver =
                    CellMappingsFactory.createCellNameResolver(
                        cellPath, config, newCellPathResolver);

                CellPathResolver cellPathResolver =
                    new CellPathResolverView(
                        rootCellCellPathResolver,
                        cellNameResolver,
                        cellMapping.keySet(),
                        cellPath.getPath());

                Optional<EmbeddedCellBuckOutInfo> embeddedCellBuckOutInfo = Optional.empty();
                CanonicalCellName canonicalCellName =
                    cellPathResolver
                        .getNewCellPathResolver()
                        .getCanonicalCellName(normalizedCellPath.getPath());
                if (rootConfig.getView(BuildBuckConfig.class).isEmbeddedCellBuckOutEnabled()
                    && canonicalCellName.getLegacyName().isPresent()) {
                  embeddedCellBuckOutInfo =
                      Optional.of(
                          EmbeddedCellBuckOutInfo.of(
                              rootFilesystem.getRootPath().getPath(),
                              rootFilesystem.getBuckPaths(),
                              canonicalCellName));
                }
                ProjectFilesystem cellFilesystem =
                    projectFilesystemFactory.createProjectFilesystem(
                        canonicalCellName,
                        normalizedCellPath,
                        config,
                        embeddedCellBuckOutInfo,
                        BuckPaths.getBuckOutIncludeTargetConfigHashFromRootCellConfig(
                            rootConfig.getConfig()));

                BuckConfig buckConfig =
                    new BuckConfig(
                        config,
                        cellFilesystem,
                        rootConfig.getArchitecture(),
                        rootConfig.getPlatform(),
                        rootConfig.getEnvironment(),
                        buildTargetName ->
                            unconfiguredBuildTargetFactory.create(
                                buildTargetName, cellPathResolver.getCellNameResolver()));

                RuleKeyConfiguration ruleKeyConfiguration =
                    ConfigRuleKeyConfigurationFactory.create(buckConfig, moduleManager);

                ToolchainProvider toolchainProvider =
                    toolchainProviderFactory.create(
                        buckConfig, cellFilesystem, ruleKeyConfiguration);

                // TODO(13777679): cells in other watchman roots do not work correctly.

                return ImmutableCellImpl.of(
                    cellPathResolver.getKnownRoots().stream()
                        .collect(ImmutableSortedSet.toImmutableSortedSet(AbsPath.comparator())),
                    canonicalCellName,
                    cellFilesystem,
                    buckConfig,
                    cellProvider,
                    toolchainProvider,
                    cellPathResolver,
                    newCellPathResolver,
                    cellNameResolver);
              }
            },
        cellProvider ->
            RootCellFactory.create(
                cellProvider,
                newCellPathResolver,
                rootCellCellPathResolver,
                toolchainProviderFactory,
                rootFilesystem,
                moduleManager,
                rootConfig));
  }
}
