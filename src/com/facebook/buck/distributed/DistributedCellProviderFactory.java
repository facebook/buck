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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.CellPathResolverView;
import com.facebook.buck.core.cell.CellProvider;
import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.cell.impl.ImmutableCell;
import com.facebook.buck.core.cell.impl.RootCellFactory;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetFactory;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Creates a {@link CellProvider} to be used in a distributed build. */
public class DistributedCellProviderFactory {
  public static CellProvider create(
      DistBuildCellParams rootCell,
      ImmutableMap<Path, DistBuildCellParams> cellParams,
      CellPathResolver rootCellPathResolver,
      UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory,
      Supplier<TargetConfiguration> targetConfiguration) {
    Map<String, Path> cellPaths =
        cellParams.values().stream()
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
                      Objects.requireNonNull(
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
                      currentCellResolver);
                }),
        cellProvider ->
            RootCellFactory.create(
                cellProvider,
                rootCellResolver,
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
