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
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.rules.keys.config.impl.ConfigRuleKeyConfigurationFactory;
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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
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
                        cellPathResolver);

                RuleKeyConfiguration ruleKeyConfiguration =
                    ConfigRuleKeyConfigurationFactory.create(buckConfig, pluginManager);

                ToolchainProvider toolchainProvider =
                    new DefaultToolchainProvider(
                        pluginManager,
                        environment,
                        buckConfig,
                        cellFilesystem,
                        processExecutor,
                        executableFinder,
                        ruleKeyConfiguration);

                // TODO(13777679): cells in other watchman roots do not work correctly.

                return Cell.of(
                    getKnownRoots(cellPathResolver),
                    canonicalCellName,
                    cellFilesystem,
                    watchman,
                    buckConfig,
                    cellProvider,
                    toolchainProvider,
                    ruleKeyConfiguration);
              }
            },
        cellProvider ->
            createRootCell(
                cellProvider,
                rootCellCellPathResolver,
                rootFilesystem,
                pluginManager,
                rootConfig,
                environment,
                processExecutor,
                executableFinder,
                watchman));
  }

  public static CellProvider createForDistributedBuild(
      DistBuildCellParams rootCell, ImmutableMap<Path, DistBuildCellParams> cellParams) {
    final Map<String, Path> cellPaths =
        cellParams
            .values()
            .stream()
            .filter(p -> p.getCanonicalName().isPresent())
            .collect(
                Collectors.toMap(
                    p -> p.getCanonicalName().get(), p -> p.getFilesystem().getRootPath()));
    ImmutableSet<String> declaredCellNames = ImmutableSet.copyOf(cellPaths.keySet());
    Path rootCellPath = rootCell.getFilesystem().getRootPath();
    DefaultCellPathResolver rootCellResolver = DefaultCellPathResolver.of(rootCellPath, cellPaths);

    return new CellProvider(
        cellProvider ->
            CacheLoader.from(
                cellPath -> {
                  DistBuildCellParams cellParam =
                      Preconditions.checkNotNull(
                          cellParams.get(cellPath),
                          "This should only be called for secondary cells.");
                  Path currentCellRoot = cellParam.getFilesystem().getRootPath();
                  Preconditions.checkState(!currentCellRoot.equals(rootCellPath));
                  CellPathResolver currentCellResolver = rootCellResolver;
                  // The CellPathResolverView is required because it makes the
                  // [RootPath<->CanonicalName] resolver methods non-symmetrical to handle the
                  // fact
                  // that relative main cell from inside a secondary cell resolves actually to
                  // secondary cell. If the DefaultCellPathResolver is used, then it would return
                  // a BuildTarget as if it belonged to the main cell.
                  currentCellResolver =
                      new CellPathResolverView(
                          rootCellResolver, declaredCellNames, currentCellRoot);
                  final BuckConfig configWithResolver =
                      cellParam.getConfig().withCellPathResolver(currentCellResolver);
                  RuleKeyConfiguration ruleKeyConfiguration =
                      ConfigRuleKeyConfigurationFactory.create(
                          configWithResolver, cellParam.getPluginManager());
                  ToolchainProvider toolchainProvider =
                      new DefaultToolchainProvider(
                          cellParam.getPluginManager(),
                          cellParam.getEnvironment(),
                          configWithResolver,
                          cellParam.getFilesystem(),
                          cellParam.getProcessExecutor(),
                          cellParam.getExecutableFinder(),
                          ruleKeyConfiguration);

                  return Cell.of(
                      cellParams.keySet(),
                      // Distributed builds don't care about cell names, use a sentinel value that
                      // will show up if it actually does care about them.
                      cellParam.getCanonicalName(),
                      cellParam.getFilesystem(),
                      WatchmanFactory.NULL_WATCHMAN,
                      configWithResolver,
                      cellProvider,
                      toolchainProvider,
                      ruleKeyConfiguration);
                }),
        cellProvider ->
            createRootCell(
                cellProvider,
                rootCellResolver,
                rootCell.getFilesystem(),
                rootCell.getPluginManager(),
                rootCell.getConfig(),
                rootCell.getEnvironment(),
                rootCell.getProcessExecutor(),
                rootCell.getExecutableFinder(),
                WatchmanFactory.NULL_WATCHMAN));
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

  private static Cell createRootCell(
      CellProvider cellProvider,
      CellPathResolver rootCellCellPathResolver,
      ProjectFilesystem rootFilesystem,
      PluginManager pluginManager,
      BuckConfig rootConfig,
      ImmutableMap<String, String> environment,
      ProcessExecutor processExecutor,
      ExecutableFinder executableFinder,
      Watchman watchman) {
    Preconditions.checkState(
        !rootCellCellPathResolver.getCanonicalCellName(rootFilesystem.getRootPath()).isPresent(),
        "Root cell should be nameless");
    RuleKeyConfiguration ruleKeyConfiguration =
        ConfigRuleKeyConfigurationFactory.create(rootConfig, pluginManager);
    ToolchainProvider toolchainProvider =
        new DefaultToolchainProvider(
            pluginManager,
            environment,
            rootConfig,
            rootFilesystem,
            processExecutor,
            executableFinder,
            ruleKeyConfiguration);
    return Cell.of(
        getKnownRoots(rootCellCellPathResolver),
        Optional.empty(),
        rootFilesystem,
        watchman,
        rootConfig,
        cellProvider,
        toolchainProvider,
        ruleKeyConfiguration);
  }
}
