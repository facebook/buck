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

package com.facebook.buck.distributed;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildJobStateBuckConfig;
import com.facebook.buck.distributed.thrift.BuildJobStateCell;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.OrderedStringMapEntry;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.CellProvider;
import com.facebook.buck.rules.DefaultCellPathResolver;
import com.facebook.buck.rules.DistBuildCellParams;
import com.facebook.buck.rules.KnownBuildRuleTypesFactory;
import com.facebook.buck.rules.SdkEnvironment;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.RawConfig;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Saves and restores the state of a build to/from a thrift data structure. */
public class DistBuildState {

  private final BuildJobState remoteState;
  private final ImmutableBiMap<Integer, Cell> cells;
  private final Map<ProjectFilesystem, BuildJobStateFileHashes> fileHashes;

  private DistBuildState(BuildJobState remoteState, final ImmutableBiMap<Integer, Cell> cells) {
    this.remoteState = remoteState;
    this.cells = cells;
    this.fileHashes =
        Maps.uniqueIndex(
            remoteState.getFileHashes(),
            input -> {
              int cellIndex = input.getCellIndex();
              Cell cell =
                  Preconditions.checkNotNull(
                      cells.get(cellIndex),
                      "Unknown cell index %s. Distributed build state dump corrupt?",
                      cellIndex);
              return cell.getFilesystem();
            });
  }

  public static BuildJobState dump(
      DistBuildCellIndexer distributedBuildCellIndexer,
      DistBuildFileHashes fileHashes,
      DistBuildTargetGraphCodec targetGraphCodec,
      TargetGraph targetGraph,
      ImmutableSet<BuildTarget> topLevelTargets)
      throws IOException, InterruptedException {
    Preconditions.checkArgument(topLevelTargets.size() > 0);
    BuildJobState jobState = new BuildJobState();
    jobState.setFileHashes(fileHashes.getFileHashes());
    jobState.setTargetGraph(
        targetGraphCodec.dump(targetGraph.getNodes(), distributedBuildCellIndexer));
    jobState.setCells(distributedBuildCellIndexer.getState());

    for (BuildTarget target : topLevelTargets) {
      jobState.addToTopLevelTargets(target.getFullyQualifiedName());
    }
    return jobState;
  }

  public static DistBuildState load(
      BuckConfig localBuckConfig, // e.g. the slave's .buckconfig
      BuildJobState jobState,
      Cell rootCell,
      KnownBuildRuleTypesFactory knownBuildRuleTypesFactory,
      SdkEnvironment sdkEnvironment,
      ProjectFilesystemFactory projectFilesystemFactory)
      throws InterruptedException, IOException {
    ProjectFilesystem rootCellFilesystem = rootCell.getFilesystem();

    ImmutableMap.Builder<Path, DistBuildCellParams> cellParams = ImmutableMap.builder();
    ImmutableMap.Builder<Integer, Path> cellIndex = ImmutableMap.builder();

    Path sandboxPath =
        rootCellFilesystem
            .getRootPath()
            .resolve(rootCellFilesystem.getBuckPaths().getRemoteSandboxDir());
    rootCellFilesystem.mkdirs(sandboxPath);

    Path uniqueBuildRoot = Files.createTempDirectory(sandboxPath, "build");

    for (Map.Entry<Integer, BuildJobStateCell> remoteCellEntry : jobState.getCells().entrySet()) {
      BuildJobStateCell remoteCell = remoteCellEntry.getValue();

      Path cellRoot = uniqueBuildRoot.resolve(remoteCell.getNameHint());
      Files.createDirectories(cellRoot);

      Config config = createConfigFromRemoteAndOverride(remoteCell.getConfig(), localBuckConfig);
      ProjectFilesystem projectFilesystem =
          projectFilesystemFactory.createProjectFilesystem(cellRoot, config);
      BuckConfig buckConfig =
          createBuckConfigFromRawConfigAndEnv(
              config, projectFilesystem, ImmutableMap.copyOf(localBuckConfig.getEnvironment()));

      Optional<String> cellName =
          remoteCell.getCanonicalName().isEmpty()
              ? Optional.empty()
              : Optional.of(remoteCell.getCanonicalName());
      cellParams.put(cellRoot, DistBuildCellParams.of(buckConfig, projectFilesystem, cellName));
      cellIndex.put(remoteCellEntry.getKey(), cellRoot);
    }

    CellProvider cellProvider =
        CellProvider.createForDistributedBuild(
            cellParams.build(), knownBuildRuleTypesFactory, sdkEnvironment);

    ImmutableBiMap<Integer, Cell> cells =
        ImmutableBiMap.copyOf(Maps.transformValues(cellIndex.build(), cellProvider::getCellByPath));
    return new DistBuildState(jobState, cells);
  }

  public BuildJobState getRemoteState() {
    return remoteState;
  }

  public static Config createConfigFromRemoteAndOverride(
      BuildJobStateBuckConfig remoteBuckConfig, BuckConfig overrideBuckConfig) {

    ImmutableMap<String, ImmutableMap<String, String>> rawConfig =
        ImmutableMap.copyOf(
            Maps.transformValues(
                remoteBuckConfig.getRawBuckConfig(),
                input -> {
                  ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
                  for (OrderedStringMapEntry entry : input) {
                    builder.put(entry.getKey(), entry.getValue());
                  }
                  return builder.build();
                }));

    RawConfig.Builder rawConfigBuilder = RawConfig.builder();
    rawConfigBuilder.putAll(rawConfig);

    rawConfigBuilder.putAll(overrideBuckConfig.getConfig().getRawConfig());
    return new Config(rawConfigBuilder.build());
  }

  private static BuckConfig createBuckConfigFromRawConfigAndEnv(
      Config rawConfig,
      ProjectFilesystem projectFilesystem,
      ImmutableMap<String, String> environment) {
    return new BuckConfig(
        rawConfig,
        projectFilesystem,
        Architecture.detect(),
        Platform.detect(),
        ImmutableMap.copyOf(environment),
        new DefaultCellPathResolver(projectFilesystem.getRootPath(), rawConfig));
  }

  public ImmutableMap<Integer, Cell> getCells() {
    return cells;
  }

  public Cell getRootCell() {
    return Preconditions.checkNotNull(cells.get(DistBuildCellIndexer.ROOT_CELL_INDEX));
  }

  public TargetGraphAndBuildTargets createTargetGraph(DistBuildTargetGraphCodec codec)
      throws IOException {
    return codec.createTargetGraph(remoteState.getTargetGraph(), Functions.forMap(cells));
  }

  public ProjectFileHashCache createRemoteFileHashCache(ProjectFileHashCache decoratedCache) {
    BuildJobStateFileHashes remoteFileHashes = fileHashes.get(decoratedCache.getFilesystem());
    if (remoteFileHashes == null) {
      // Roots that have no BuildJobStateFileHashes are deemed as not being Cells and don't get
      // decorated.
      return decoratedCache;
    }

    ProjectFileHashCache remoteCache =
        DistBuildFileHashes.createFileHashCache(decoratedCache, remoteFileHashes);
    return remoteCache;
  }

  public ProjectFileHashCache createMaterializerAndPreload(
      ProjectFileHashCache decoratedCache, FileContentsProvider provider) throws IOException {
    BuildJobStateFileHashes remoteFileHashes = fileHashes.get(decoratedCache.getFilesystem());
    if (remoteFileHashes == null) {
      // Roots that have no BuildJobStateFileHashes are deemed as not being Cells and don't get
      // decorated.
      return decoratedCache;
    }

    MaterializerDummyFileHashCache materializer =
        new MaterializerDummyFileHashCache(decoratedCache, remoteFileHashes, provider);

    // Create all symlinks and touch all other files.
    DistBuildConfig remoteConfig = new DistBuildConfig(getRootCell().getBuckConfig());
    boolean materializeAllFilesDuringPreload = !remoteConfig.materializeSourceFilesOnDemand();
    materializer.preloadAllFiles(materializeAllFilesDuringPreload);

    return materializer;
  }
}
