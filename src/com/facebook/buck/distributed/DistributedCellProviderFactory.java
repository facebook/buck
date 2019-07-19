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

package com.facebook.buck.distributed;

import com.facebook.buck.core.cell.CellNameResolver;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.CellPathResolverView;
import com.facebook.buck.core.cell.CellProvider;
import com.facebook.buck.core.cell.NewCellPathResolver;
import com.facebook.buck.core.cell.impl.CellMappingsFactory;
import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.cell.impl.ImmutableCell;
import com.facebook.buck.core.cell.impl.RootCellFactory;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.ImmutableCanonicalCellName;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.impl.DefaultToolchainProvider;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.rules.keys.config.impl.ConfigRuleKeyConfigurationFactory;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheLoader;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Creates a {@link CellProvider} to be used in a distributed build. */
public class DistributedCellProviderFactory {
  public static CellProvider create(
      DistBuildCellParams rootCell,
      ImmutableMap<Path, DistBuildCellParams> cellParams,
      CellPathResolver rootCellPathResolver,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory,
      Supplier<TargetConfiguration> targetConfiguration) {
    Map<String, Path> cellPaths =
        cellParams.values().stream()
            .filter(p -> p.getCanonicalName().isPresent())
            .collect(
                Collectors.toMap(
                    p -> p.getCanonicalName().get(), p -> p.getFilesystem().getRootPath()));

    // We do this really odd thing where we allow secondary cell's to refer to the main cell only if
    // the main cell has a non-empty alias for itself and the secondary cell uses the same alias.
    // TODO(cjhopman): This is quite wrong and should be fixed to actually use the correct mappings
    // like a non-stampede build would.
    Optional<ImmutableMap<String, String>> rootCellRepositories =
        rootCell.getConfig().getSection(DefaultCellPathResolver.REPOSITORIES_SECTION);
    if (rootCellRepositories.isPresent()) {
      for (Map.Entry<String, String> entry : rootCellRepositories.get().entrySet()) {
        if (entry.getValue().equals(".")) {
          cellPaths.put(entry.getKey(), rootCell.getFilesystem().getRootPath());
        }
      }
    }

    ImmutableSet<String> declaredCellNames = ImmutableSet.copyOf(cellPaths.keySet());
    Path rootCellPath = rootCell.getFilesystem().getRootPath();
    NewCellPathResolver newCellPathResolver =
        CellMappingsFactory.create(rootCellPath, rootCell.getConfig().getConfig());

    CellNameResolver rootCellNameResolver =
        CellMappingsFactory.createCellNameResolver(
            rootCellPath, rootCell.getConfig().getConfig(), newCellPathResolver);

    // TODO(cjhopman): I have no idea how this is supposed to work. We don't actually use this
    // resolver in the Cell, but we do use it in secondary cells... This doesn't match the resolver
    // that is passed in to this function.
    DefaultCellPathResolver overriddenCellPathResolver =
        DefaultCellPathResolver.create(
            rootCellPath, cellPaths, rootCellNameResolver, newCellPathResolver);

    return new CellProvider(
        cellProvider ->
            CacheLoader.from(
                cellPath -> {
                  DistBuildCellParams cellParam =
                      Objects.requireNonNull(
                          cellParams.get(cellPath),
                          "This should only be called for secondary cells.");
                  Path currentCellRoot = cellParam.getFilesystem().getRootPath();
                  Preconditions.checkState(!currentCellRoot.equals(rootCellPath));
                  CellPathResolver currentCellResolver;
                  // The CellPathResolverView is required because it makes the
                  // [RootPath<->CanonicalName] resolver methods non-symmetrical to handle the
                  // fact
                  // that relative main cell from inside a secondary cell resolves actually to
                  // secondary cell. If the DefaultCellPathResolver is used, then it would return
                  // a BuildTarget as if it belonged to the main cell.

                  CellNameResolver cellNameResolver =
                      CellMappingsFactory.createCellNameResolver(
                          newCellPathResolver.getCellPath(
                              new ImmutableCanonicalCellName(cellParam.getCanonicalName())),
                          cellParam.getConfig().getConfig(),
                          newCellPathResolver);

                  currentCellResolver =
                      new CellPathResolverView(
                          overriddenCellPathResolver,
                          cellNameResolver,
                          declaredCellNames,
                          currentCellRoot);
                  CellPathResolver cellPathResolverForParser = currentCellResolver;
                  BuckConfig configWithResolver =
                      cellParam
                          .getConfig()
                          .withBuildTargetParser(
                              buildTargetName ->
                                  unconfiguredBuildTargetFactory.create(
                                      cellPathResolverForParser, buildTargetName));
                  RuleKeyConfiguration ruleKeyConfiguration =
                      ConfigRuleKeyConfigurationFactory.create(
                          configWithResolver, cellParam.getBuckModuleManager());
                  ToolchainProvider toolchainProvider =
                      new DefaultToolchainProvider(
                          cellParam.getPluginManager(),
                          cellParam.getEnvironment(),
                          configWithResolver,
                          cellParam.getFilesystem(),
                          cellParam.getProcessExecutor(),
                          cellParam.getExecutableFinder(),
                          ruleKeyConfiguration,
                          targetConfiguration);

                  return ImmutableCell.of(
                      ImmutableSortedSet.copyOf(cellParams.keySet()),
                      // Distributed builds don't care about cell names, use a sentinel value that
                      // will show up if it actually does care about them.
                      cellParam.getCanonicalName(),
                      cellParam.getFilesystem(),
                      configWithResolver,
                      cellProvider,
                      toolchainProvider,
                      currentCellResolver,
                      newCellPathResolver,
                      cellNameResolver);
                }),
        cellProvider ->
            RootCellFactory.create(
                overriddenCellPathResolver.getKnownRoots(),
                cellProvider,
                newCellPathResolver,
                rootCellPathResolver,
                rootCell.getFilesystem(),
                rootCell.getBuckModuleManager(),
                rootCell.getPluginManager(),
                rootCell.getConfig(),
                rootCell.getEnvironment(),
                rootCell.getProcessExecutor(),
                rootCell.getExecutableFinder(),
                targetConfiguration));
  }
}
