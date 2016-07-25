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
import com.facebook.buck.hashing.FileHashLoader;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.DefaultCellPathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TProtocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Saves and restores the state of a build to/from a thrift data structure.
 */
public class DistributedBuildState {

  private final BuildJobState remoteState;
  private final ImmutableBiMap<Integer, Cell> cells;
  private final Map<ProjectFilesystem, BuildJobStateFileHashes> fileHashes;

  private DistributedBuildState(
      BuildJobState remoteState,
      final ImmutableBiMap<Integer, Cell> cells) {
    this.remoteState = remoteState;
    this.cells = cells;
    this.fileHashes = Maps.uniqueIndex(
        remoteState.getFileHashes(),
        new Function<BuildJobStateFileHashes, ProjectFilesystem>() {
          @Override
          public ProjectFilesystem apply(BuildJobStateFileHashes input) {
            int cellIndex = input.getCellIndex();
            Cell cell = Preconditions.checkNotNull(
                cells.get(cellIndex),
                "Unknown cell index %s. Distributed build state dump corrupt?",
                cellIndex);
            return cell.getFilesystem();
          }
        });
  }

  public static BuildJobState dump(
      DistributedBuildCellIndexer distributedBuildCellIndexer,
      DistributedBuildFileHashes fileHashes,
      DistributedBuildTargetGraphCodec targetGraphCodec,
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

  public static DistributedBuildState load(TProtocol protocol, Cell rootCell)
      throws TException, IOException, InterruptedException {
    BuildJobState jobState = new BuildJobState();
    jobState.read(protocol);
    return load(jobState, rootCell);
  }

  @VisibleForTesting
  static DistributedBuildState load(BuildJobState jobState, Cell rootCell)
      throws IOException, InterruptedException {
    return new DistributedBuildState(jobState, createCells(jobState, rootCell));
  }

  private static ImmutableBiMap<Integer, Cell> createCells(BuildJobState remoteState, Cell rootCell)
      throws IOException, InterruptedException {
    ProjectFilesystem rootCellFilesystem = rootCell.getFilesystem();

    ImmutableMap.Builder<Path, BuckConfig> cellConfigs = ImmutableMap.builder();
    ImmutableMap.Builder<Path, ProjectFilesystem> cellFilesystems = ImmutableMap.builder();
    ImmutableMap.Builder<Integer, Path> cellIndex = ImmutableMap.builder();

    for (Map.Entry<Integer, BuildJobStateCell> remoteCellEntry :
        remoteState.getCells().entrySet()) {
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

    LoadingCache<Path, Cell> cellLoader = rootCell.createCellLoaderForDistributedBuild(
        cellConfigs.build(),
        cellFilesystems.build(),
        rootCell.getWatchmanDiagnosticCache());

    return ImmutableBiMap.copyOf(Maps.transformValues(cellIndex.build(), cellLoader));
  }

  private static Config createConfig(BuildJobStateBuckConfig remoteBuckConfig) {
    ImmutableMap<String, ImmutableMap<String, String>> rawConfig = ImmutableMap.copyOf(
        Maps.transformValues(
            remoteBuckConfig.getRawBuckConfig(),
            new Function<List<OrderedStringMapEntry>, ImmutableMap<String, String>>() {
              @Override
              public ImmutableMap<String, String> apply(List<OrderedStringMapEntry> input) {
                ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
                for (OrderedStringMapEntry entry : input) {
                  builder.put(entry.getKey(), entry.getValue());
                }
                return builder.build();
              }
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
    return Preconditions.checkNotNull(cells.get(DistributedBuildCellIndexer.ROOT_CELL_INDEX));
  }

  public TargetGraph createTargetGraph(DistributedBuildTargetGraphCodec codec)
      throws IOException, InterruptedException {
    return codec.createTargetGraph(
        remoteState.getTargetGraph(),
        Functions.forMap(cells));
  }

  public FileHashCache createFileHashLoader(ProjectFilesystem projectFilesystem) {
    BuildJobStateFileHashes remoteFileHashes = Preconditions.checkNotNull(
        fileHashes.get(projectFilesystem),
        "Don't have file hashes for filesystem %s.",
        projectFilesystem);
    return DistributedBuildFileHashes.createFileHashCache(projectFilesystem, remoteFileHashes);
  }

  public FileHashLoader createMaterializingLoader(ProjectFilesystem projectFilesystem) {
    BuildJobStateFileHashes remoteFileHashes = Preconditions.checkNotNull(
        fileHashes.get(projectFilesystem),
        "Don't have file hashes for filesystem %s.",
        projectFilesystem);
    return DistributedBuildFileHashes.createMaterializingLoader(
        projectFilesystem,
        remoteFileHashes);
  }
}
