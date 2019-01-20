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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.actiongraph.ActionGraph;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.google.common.base.Functions;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;

/**
 * Responsible for extracting file hash and {@link RuleKey} information from the {@link ActionGraph}
 * and presenting it as a Thrift data structure.
 */
public class DistBuildFileHashes {
  private static final Logger LOG = Logger.get(DistBuildFileHashes.class);

  // Map<CellIndex, BuildJobStateFileHashes>.
  private final Map<Integer, RecordedFileHashes> remoteFileHashes;
  private final LoadingCache<ProjectFilesystem, DefaultRuleKeyFactory> ruleKeyFactories;

  private final ListenableFuture<ImmutableList<RecordedFileHashes>> fileHashes;
  private final ListenableFuture<ImmutableMap<BuildRule, RuleKey>> ruleKeys;

  public DistBuildFileHashes(
      ActionGraph actionGraph,
      SourcePathResolver sourcePathResolver,
      SourcePathRuleFinder ruleFinder,
      StackedFileHashCache originalHashCache,
      DistBuildCellIndexer cellIndexer,
      ListeningExecutorService executorService,
      RuleKeyConfiguration ruleKeyConfiguration,
      Cell rootCell) {

    this.remoteFileHashes = new HashMap<>();

    StackedFileHashCache recordingHashCache =
        originalHashCache.newDecoratedFileHashCache(
            originalCache -> {
              Path fsRootPath = originalCache.getFilesystem().getRootPath();
              RecordedFileHashes fileHashes =
                  getRemoteFileHashes(cellIndexer.getCellIndex(fsRootPath));
              if (rootCell.getKnownRoots().contains(fsRootPath)) {
                Cell cell = rootCell.getCell(fsRootPath);
                return RecordingProjectFileHashCache.createForCellRoot(
                    originalCache,
                    fileHashes,
                    new DistBuildConfig(cell.getBuckConfig()),
                    cell.getCellPathResolver());
              } else {
                return RecordingProjectFileHashCache.createForNonCellRoot(
                    originalCache, fileHashes);
              }
            });

    this.ruleKeyFactories =
        createRuleKeyFactories(
            sourcePathResolver, ruleFinder, recordingHashCache, ruleKeyConfiguration);
    this.ruleKeys = ruleKeyComputation(actionGraph, this.ruleKeyFactories, executorService);
    this.fileHashes =
        fileHashesComputation(
            Futures.transform(this.ruleKeys, Functions.constant(null)),
            ImmutableList.copyOf(this.remoteFileHashes.values()),
            executorService);
  }

  private RecordedFileHashes getRemoteFileHashes(Integer cellIndex) {
    if (!remoteFileHashes.containsKey(cellIndex)) {
      RecordedFileHashes fileHashes = new RecordedFileHashes(cellIndex);
      remoteFileHashes.put(cellIndex, fileHashes);
    }

    return remoteFileHashes.get(cellIndex);
  }

  public static LoadingCache<ProjectFilesystem, DefaultRuleKeyFactory> createRuleKeyFactories(
      SourcePathResolver sourcePathResolver,
      SourcePathRuleFinder ruleFinder,
      FileHashCache fileHashCache,
      RuleKeyConfiguration ruleKeyConfiguration) {

    return CacheBuilder.newBuilder()
        .build(
            new CacheLoader<ProjectFilesystem, DefaultRuleKeyFactory>() {
              @Override
              public DefaultRuleKeyFactory load(ProjectFilesystem key) {
                // Create a new RuleKeyCache to make computation visit the
                // RecordingProjectFileHashCache
                return new DefaultRuleKeyFactory(
                    new RuleKeyFieldLoader(ruleKeyConfiguration),
                    fileHashCache,
                    sourcePathResolver,
                    ruleFinder);
              }
            });
  }

  private static ListenableFuture<ImmutableMap<BuildRule, RuleKey>> ruleKeyComputation(
      ActionGraph actionGraph,
      LoadingCache<ProjectFilesystem, DefaultRuleKeyFactory> ruleKeyFactories,
      ListeningExecutorService executorService) {
    List<ListenableFuture<Map.Entry<BuildRule, RuleKey>>> ruleKeyEntries = new ArrayList<>();
    for (BuildRule rule : Sets.newLinkedHashSet(actionGraph.getNodes())) {
      ruleKeyEntries.add(
          executorService.submit(
              () ->
                  Maps.immutableEntry(
                      rule, ruleKeyFactories.get(rule.getProjectFilesystem()).build(rule))));
    }
    ListenableFuture<List<Map.Entry<BuildRule, RuleKey>>> ruleKeyComputation =
        Futures.allAsList(ruleKeyEntries);
    return Futures.transform(ruleKeyComputation, ImmutableMap::copyOf, executorService);
  }

  private static ListenableFuture<ImmutableList<RecordedFileHashes>> fileHashesComputation(
      ListenableFuture<Void> ruleKeyComputationForSideEffect,
      ImmutableList<RecordedFileHashes> remoteFileHashes,
      ListeningExecutorService executorService) {
    ListenableFuture<ImmutableList<RecordedFileHashes>> asyncHashes =
        Futures.transform(
            ruleKeyComputationForSideEffect,
            input -> ImmutableList.copyOf(remoteFileHashes),
            executorService);
    Futures.addCallback(
        asyncHashes,
        new FutureCallback<ImmutableList<RecordedFileHashes>>() {
          @Override
          public void onSuccess(@Nullable ImmutableList<RecordedFileHashes> result) {
            LOG.info("Finished Stampede FileHashComputation successfully.");
          }

          @Override
          public void onFailure(Throwable t) {
            LOG.warn("Failed to compute FileHashes for Stampede.");
          }
        },
        MoreExecutors.directExecutor());
    return asyncHashes;
  }

  public List<BuildJobStateFileHashes> getFileHashes() throws IOException, InterruptedException {
    try {
      ImmutableList<BuildJobStateFileHashes> hashes =
          fileHashes
              .get()
              .stream()
              .map(recordedHash -> recordedHash.getRemoteFileHashes())
              .filter(x -> x.getEntriesSize() > 0)
              .collect(ImmutableList.toImmutableList());
      checkNoDuplicates(hashes);
      return hashes;
    } catch (ExecutionException e) {
      Throwables.throwIfInstanceOf(e.getCause(), IOException.class);
      Throwables.throwIfInstanceOf(e.getCause(), InterruptedException.class);
      throw new RuntimeException(e.getCause());
    }
  }

  public ListenableFuture<?> getFileHashesComputationFuture() {
    return fileHashes;
  }

  private void checkNoDuplicates(ImmutableList<BuildJobStateFileHashes> hashes) {
    for (BuildJobStateFileHashes hash : hashes) {
      if (hash.isSetEntries()) {
        Maps.uniqueIndex(hash.entries, entry -> entry.getPath().getPath());
      }
    }
  }

  /** Creates a {@link FileHashCache} that returns the hash codes cached on the remote end. */
  public static ProjectFileHashCache createFileHashCache(
      ProjectFileHashCache decoratedFileHashCache, BuildJobStateFileHashes remoteFileHashes) {
    return new RemoteStateBasedFileHashCache(decoratedFileHashCache, remoteFileHashes);
  }

  public static ImmutableMap<Path, BuildJobStateFileHashEntry> indexEntriesByPath(
      ProjectFilesystem projectFilesystem, BuildJobStateFileHashes remoteFileHashes) {
    if (!remoteFileHashes.isSetEntries()) {
      return ImmutableMap.of();
    }
    return FluentIterable.from(remoteFileHashes.entries)
        .filter(input -> !input.isPathIsAbsolute() && !input.isSetArchiveMemberPath())
        .uniqueIndex(
            input ->
                projectFilesystem.resolve(
                    MorePaths.pathWithPlatformSeparators(input.getPath().getPath())));
  }

  public static ImmutableMap<ArchiveMemberPath, BuildJobStateFileHashEntry>
      indexEntriesByArchivePath(
          ProjectFilesystem projectFilesystem, BuildJobStateFileHashes remoteFileHashes) {
    if (!remoteFileHashes.isSetEntries()) {
      return ImmutableMap.of();
    }
    return FluentIterable.from(remoteFileHashes.entries)
        .filter(input -> !input.isPathIsAbsolute() && input.isSetArchiveMemberPath())
        .uniqueIndex(
            input ->
                ArchiveMemberPath.of(
                    projectFilesystem.resolve(
                        MorePaths.pathWithPlatformSeparators(input.getPath().getPath())),
                    Paths.get(input.getArchiveMemberPath())));
  }

  public void cancel() {
    this.fileHashes.cancel(true);
    this.ruleKeys.cancel(true);
  }
}
