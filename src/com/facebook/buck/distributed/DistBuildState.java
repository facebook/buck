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

import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.LOCAL_TARGET_GRAPH_SERIALIZATION;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildJobStateBuckConfig;
import com.facebook.buck.distributed.thrift.BuildJobStateCell;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.OrderedStringMapEntry;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.CellProvider;
import com.facebook.buck.rules.DefaultCellPathResolver;
import com.facebook.buck.rules.DistBuildCellParams;
import com.facebook.buck.rules.DistributedCellProviderFactory;
import com.facebook.buck.rules.KnownBuildRuleTypesProvider;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.RawConfig;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.pf4j.PluginManager;

/** Saves and restores the state of a build to/from a thrift data structure. */
public class DistBuildState {
  private static final Logger LOG = Logger.get(DistBuildState.class);

  private final BuildJobState remoteState;
  private final ImmutableBiMap<Integer, Cell> cells;
  private final Map<ProjectFilesystem, BuildJobStateFileHashes> fileHashes;

  private DistBuildState(BuildJobState remoteState, ImmutableBiMap<Integer, Cell> cells) {
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

  /** Creates a serializable {@link BuildJobState} object from the given parameters. */
  public static BuildJobState dump(
      DistBuildCellIndexer distributedBuildCellIndexer,
      DistBuildFileHashes fileHashes,
      DistBuildTargetGraphCodec targetGraphCodec,
      TargetGraph targetGraph,
      ImmutableSet<BuildTarget> topLevelTargets)
      throws IOException, InterruptedException {
    return dump(
        distributedBuildCellIndexer,
        fileHashes,
        targetGraphCodec,
        targetGraph,
        topLevelTargets,
        Optional.empty());
  }

  /**
   * Creates a serializable {@link BuildJobState} object from the given parameters. Also records
   * relevant statistics in the {@link ClientStatsTracker} if one is provided.
   */
  public static BuildJobState dump(
      DistBuildCellIndexer distributedBuildCellIndexer,
      DistBuildFileHashes fileHashes,
      DistBuildTargetGraphCodec targetGraphCodec,
      TargetGraph targetGraph,
      ImmutableSet<BuildTarget> topLevelTargets,
      Optional<ClientStatsTracker> clientStatsTracker)
      throws IOException, InterruptedException {
    Preconditions.checkArgument(topLevelTargets.size() > 0);
    BuildJobState jobState = new BuildJobState();

    LOG.info("Setting file hashes..");
    jobState.setFileHashes(fileHashes.getFileHashes());
    LOG.info("Finished setting file hashes.");

    LOG.info("Setting cells..");
    jobState.setCells(distributedBuildCellIndexer.getState());
    LOG.info("Finished setting cells.");

    clientStatsTracker.ifPresent(tracker -> tracker.startTimer(LOCAL_TARGET_GRAPH_SERIALIZATION));
    jobState.setTargetGraph(
        targetGraphCodec.dump(targetGraph.getNodes(), distributedBuildCellIndexer));
    clientStatsTracker.ifPresent(tracker -> tracker.stopTimer(LOCAL_TARGET_GRAPH_SERIALIZATION));

    for (BuildTarget target : topLevelTargets) {
      jobState.addToTopLevelTargets(target.getFullyQualifiedName());
    }

    return jobState;
  }

  public static DistBuildState load(
      BuckConfig localBuckConfig, // e.g. the slave's .buckconfig
      BuildJobState jobState,
      Cell rootCell,
      ImmutableMap<String, String> environment,
      ProcessExecutor processExecutor,
      ExecutableFinder executableFinder,
      PluginManager pluginManager,
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

    DistBuildCellParams rootCellParams = null;
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
      DistBuildCellParams currentCellParams =
          DistBuildCellParams.of(
              buckConfig,
              projectFilesystem,
              cellName,
              environment,
              processExecutor,
              executableFinder,
              pluginManager);
      cellParams.put(cellRoot, currentCellParams);
      cellIndex.put(remoteCellEntry.getKey(), cellRoot);

      if (remoteCellEntry.getKey() == DistBuildCellIndexer.ROOT_CELL_INDEX) {
        rootCellParams = currentCellParams;
      }
    }

    CellProvider cellProvider =
        DistributedCellProviderFactory.create(
            Preconditions.checkNotNull(rootCellParams), cellParams.build());

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
        DefaultCellPathResolver.of(projectFilesystem.getRootPath(), rawConfig));
  }

  public ImmutableMap<Integer, Cell> getCells() {
    return cells;
  }

  public Cell getRootCell() {
    return Preconditions.checkNotNull(cells.get(DistBuildCellIndexer.ROOT_CELL_INDEX));
  }

  public TargetGraphAndBuildTargets createTargetGraph(
      DistBuildTargetGraphCodec codec, KnownBuildRuleTypesProvider knownBuildRuleTypesProvider)
      throws InterruptedException {
    return codec.createTargetGraph(
        remoteState.getTargetGraph(),
        key -> Preconditions.checkNotNull(cells.get(key)),
        knownBuildRuleTypesProvider);
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
      ProjectFileHashCache decoratedCache,
      FileContentsProvider provider,
      ListeningExecutorService executorService)
      throws IOException {
    BuildJobStateFileHashes remoteFileHashes = fileHashes.get(decoratedCache.getFilesystem());
    if (remoteFileHashes == null) {
      // Roots that have no BuildJobStateFileHashes are deemed as not being Cells and don't get
      // decorated.
      return decoratedCache;
    }

    MaterializerDummyFileHashCache materializer =
        new MaterializerDummyFileHashCache(
            decoratedCache, remoteFileHashes, provider, executorService);

    // Create all symlinks and touch all other files.
    DistBuildConfig remoteConfig = new DistBuildConfig(getRootCell().getBuckConfig());
    boolean materializeAllFilesDuringPreload = !remoteConfig.materializeSourceFilesOnDemand();
    materializer.preloadAllFiles(materializeAllFilesDuringPreload);

    return materializer;
  }

  /** The RootCell of the Remote machine. */
  public BuckConfig getRemoteRootCellConfig() {
    return getRootCell().getBuckConfig();
  }
}
