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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.config.Config;
import com.facebook.buck.config.RawConfig;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildJobStateBuckConfig;
import com.facebook.buck.distributed.thrift.BuildJobStateCell;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.OrderedStringMapEntry;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.CellProvider;
import com.facebook.buck.rules.DefaultCellPathResolver;
import com.facebook.buck.rules.KnownBuildRuleTypesFactory;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.annotation.Nonnull;

/**
 * Saves and restores the state of a build to/from a thrift data structure.
 */
public class DistBuildState {

  private final BuildJobState remoteState;
  private final ImmutableBiMap<Integer, Cell> cells;
  private final Map<ProjectFilesystem, BuildJobStateFileHashes> fileHashes;
  private final LoadingCache<ProjectFilesystem, FileHashCache> directFileHashCacheLoder;


  private DistBuildState(
      BuildJobState remoteState,
      final ImmutableBiMap<Integer, Cell> cells) {
    this.remoteState = remoteState;
    this.cells = cells;
    this.fileHashes = Maps.uniqueIndex(
        remoteState.getFileHashes(),
        input -> {
          int cellIndex = input.getCellIndex();
          Cell cell = Preconditions.checkNotNull(
              cells.get(cellIndex),
              "Unknown cell index %s. Distributed build state dump corrupt?",
              cellIndex);
          return cell.getFilesystem();
        });

    this.directFileHashCacheLoder = CacheBuilder.newBuilder()
        .build(new CacheLoader<ProjectFilesystem, FileHashCache>() {
          @Override
          public FileHashCache load(@Nonnull ProjectFilesystem filesystem) {
            FileHashCache cellCache = DefaultFileHashCache.createDefaultFileHashCache(filesystem);
            FileHashCache buckOutCache = DefaultFileHashCache.createBuckOutFileHashCache(
                new ProjectFilesystem(
                    filesystem.getRootPath(),
                    ImmutableSet.of()),
                filesystem.getBuckPaths().getBuckOut());
            return new StackedFileHashCache(
                ImmutableList.of(cellCache, buckOutCache));
          }
        });
  }

  public static BuildJobState dump(
      DistBuildCellIndexer distributedBuildCellIndexer,
      DistBuildFileHashes fileHashes,
      DistBuildTargetGraphCodec targetGraphCodec,
      TargetGraph targetGraph) throws IOException, InterruptedException {
    BuildJobState jobState = new BuildJobState();
    jobState.setFileHashes(fileHashes.getFileHashes());
    jobState.setTargetGraph(
        targetGraphCodec.dump(
            targetGraph.getNodes(),
            distributedBuildCellIndexer));
    jobState.setCells(distributedBuildCellIndexer.getState());
    return jobState;
  }

  public static DistBuildState load(
      BuildJobState jobState,
      Cell rootCell,
      KnownBuildRuleTypesFactory knownBuildRuleTypesFactory) throws IOException {
    ProjectFilesystem rootCellFilesystem = rootCell.getFilesystem();

    ImmutableMap.Builder<Path, BuckConfig> cellConfigs = ImmutableMap.builder();
    ImmutableMap.Builder<Path, ProjectFilesystem> cellFilesystems = ImmutableMap.builder();
    ImmutableMap.Builder<Integer, Path> cellIndex = ImmutableMap.builder();

    for (Map.Entry<Integer, BuildJobStateCell> remoteCellEntry :
        jobState.getCells().entrySet()) {
      BuildJobStateCell remoteCell = remoteCellEntry.getValue();
      Path sandboxPath = rootCellFilesystem.getRootPath().resolve(
          rootCellFilesystem.getBuckPaths().getRemoteSandboxDir());
      rootCellFilesystem.mkdirs(sandboxPath);
      Path cellRoot = Files.createTempDirectory(sandboxPath, remoteCell.getNameHint());
      Config config = createConfig(remoteCell.getConfig());
      ProjectFilesystem projectFilesystem = new ProjectFilesystem(cellRoot, config);
      BuckConfig buckConfig = createBuckConfig(config, projectFilesystem, remoteCell.getConfig());
      cellConfigs.put(cellRoot, buckConfig);
      cellFilesystems.put(cellRoot, projectFilesystem);
      cellIndex.put(remoteCellEntry.getKey(), cellRoot);
    }

    CellProvider cellProvider =
        CellProvider.createForDistributedBuild(
            cellConfigs.build(),
            cellFilesystems.build(),
            knownBuildRuleTypesFactory,
            rootCell.getWatchmanDiagnosticCache());

    ImmutableBiMap<Integer, Cell> cells = ImmutableBiMap.copyOf(
        Maps.transformValues(cellIndex.build(), cellProvider::getCellByPath));
    return new DistBuildState(jobState, cells);
  }

  public BuildJobState getRemoteState() {
    return remoteState;
  }

  private static Config createConfig(BuildJobStateBuckConfig remoteBuckConfig) {
    ImmutableMap<String, ImmutableMap<String, String>> rawConfig = ImmutableMap.copyOf(
        Maps.transformValues(
            remoteBuckConfig.getRawBuckConfig(),
            input -> {
              ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
              for (OrderedStringMapEntry entry : input) {
                builder.put(entry.getKey(), entry.getValue());
              }
              return builder.build();
            }));
    return new Config(RawConfig.of(rawConfig));
  }

  private static BuckConfig createBuckConfig(
      Config config,
      ProjectFilesystem projectFilesystem,
      BuildJobStateBuckConfig remoteBuckConfig) {

    Architecture remoteArchitecture = Architecture.valueOf(remoteBuckConfig.getArchitecture());
    Architecture localArchitecture = Architecture.detect();
    Preconditions.checkState(
        remoteArchitecture.equals(localArchitecture),
        "Trying to load config with architecture %s on a machine that is %s. " +
            "This is not supported.",
        remoteArchitecture,
        localArchitecture);

    Platform remotePlatform = Platform.valueOf(remoteBuckConfig.getPlatform());
    Platform localPlatform = Platform.detect();
    Preconditions.checkState(
        remotePlatform.equals(localPlatform),
        "Trying to load config with platform %s on a machine that is %s. This is not supported.",
        remotePlatform,
        localPlatform);

    return new BuckConfig(
        config,
        projectFilesystem,
        remoteArchitecture,
        remotePlatform,
        ImmutableMap.copyOf(remoteBuckConfig.getUserEnvironment()),
        new DefaultCellPathResolver(projectFilesystem.getRootPath(), config));
  }

  public ImmutableMap<Integer, Cell> getCells() {
    return cells;
  }

  public Cell getRootCell() {
    return Preconditions.checkNotNull(cells.get(DistBuildCellIndexer.ROOT_CELL_INDEX));
  }

  public TargetGraph createTargetGraph(DistBuildTargetGraphCodec codec) throws IOException {
    return codec.createTargetGraph(
        remoteState.getTargetGraph(),
        Functions.forMap(cells));
  }

  private FileHashCache loadDirectFileHashCache(ProjectFilesystem filesystem) {
    return directFileHashCacheLoder.getUnchecked(filesystem);
  }

  public FileHashCache createRemoteFileHashCache(ProjectFilesystem filesystem) {
    BuildJobStateFileHashes remoteFileHashes = Preconditions.checkNotNull(
        fileHashes.get(filesystem),
        "Don't have file hashes for filesystem %s.",
        filesystem);

    FileHashCache remoteCache =
        DistBuildFileHashes.createFileHashCache(filesystem, remoteFileHashes);

    return new StackedFileHashCache(
        ImmutableList.of(remoteCache, loadDirectFileHashCache(filesystem)));
  }

  public DistBuildFileMaterializer createMaterializingLoader(
      ProjectFilesystem projectFilesystem,
      FileContentsProvider provider) {
    BuildJobStateFileHashes remoteFileHashes = Preconditions.checkNotNull(
        fileHashes.get(projectFilesystem),
        "Don't have file hashes for filesystem %s.",
        projectFilesystem);
    return new DistBuildFileMaterializer(
        projectFilesystem, remoteFileHashes, provider, loadDirectFileHashCache(projectFilesystem));
  }
}
