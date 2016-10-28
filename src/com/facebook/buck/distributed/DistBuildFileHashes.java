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

import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.hashing.FileHashLoader;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Responsible for extracting file hash and {@link RuleKey} information from the {@link ActionGraph}
 * and presenting it as a Thrift data structure.
 */
public class DistBuildFileHashes {
  private final LoadingCache<ProjectFilesystem, BuildJobStateFileHashes> remoteFileHashes;
  private final LoadingCache<ProjectFilesystem, FileHashLoader> fileHashLoaders;
  private final LoadingCache<ProjectFilesystem, DefaultRuleKeyBuilderFactory> ruleKeyFactories;

  private final ListenableFuture<ImmutableList<BuildJobStateFileHashes>> fileHashes;
  private final ListenableFuture<ImmutableMap<BuildRule, RuleKey>>
      ruleKeys;

  public DistBuildFileHashes(
      ActionGraph actionGraph,
      final SourcePathResolver sourcePathResolver,
      final FileHashCache rootCellFileHashCache,
      final Function<? super Path, Integer> cellIndexer,
      ListeningExecutorService executorService,
      final int keySeed) {

    this.remoteFileHashes = CacheBuilder.newBuilder().build(
        new CacheLoader<ProjectFilesystem, BuildJobStateFileHashes>() {
          @Override
          public BuildJobStateFileHashes load(ProjectFilesystem filesystem) throws Exception {
            BuildJobStateFileHashes fileHashes = new BuildJobStateFileHashes();
            fileHashes.setCellIndex(cellIndexer.apply(filesystem.getRootPath()));
            return fileHashes;
          }
        });
    this.fileHashLoaders = CacheBuilder.newBuilder().build(
        new CacheLoader<ProjectFilesystem, FileHashLoader>() {
          @Override
          public FileHashLoader load(ProjectFilesystem key) throws Exception {
            return new RecordingFileHashLoader(
                new StackedFileHashCache(
                    ImmutableList.of(rootCellFileHashCache,
                        DefaultFileHashCache.createDefaultFileHashCache(key))),
                key,
                remoteFileHashes.get(key));
          }
        });
    this.ruleKeyFactories = createRuleKeyFactories(sourcePathResolver, fileHashLoaders, keySeed);
    this.ruleKeys = ruleKeyComputation(actionGraph, this.ruleKeyFactories, executorService);
    this.fileHashes = fileHashesComputation(
        Futures.transform(this.ruleKeys, Functions.constant(null)),
        this.remoteFileHashes,
        executorService);
  }

  public static LoadingCache<ProjectFilesystem, DefaultRuleKeyBuilderFactory>
  createRuleKeyFactories(
      final SourcePathResolver sourcePathResolver,
      final LoadingCache<ProjectFilesystem, ? extends FileHashLoader> fileHashLoaders,
      final int keySeed) {
    return CacheBuilder.newBuilder().build(
        new CacheLoader<ProjectFilesystem, DefaultRuleKeyBuilderFactory>() {
          @Override
          public DefaultRuleKeyBuilderFactory load(ProjectFilesystem key) throws Exception {
            return new DefaultRuleKeyBuilderFactory(
                /* seed */ keySeed,
                fileHashLoaders.get(key),
                sourcePathResolver
            );
          }
        });
  }

  private static ListenableFuture<ImmutableMap<BuildRule, RuleKey>> ruleKeyComputation(
      ActionGraph actionGraph,
      final LoadingCache<ProjectFilesystem, DefaultRuleKeyBuilderFactory> ruleKeyFactories,
      ListeningExecutorService executorService) {
    List<ListenableFuture<Map.Entry<BuildRule, RuleKey>>> ruleKeyEntries = new ArrayList<>();
    for (final BuildRule rule : actionGraph.getNodes()) {
      ruleKeyEntries.add(
          executorService.submit(
              () -> Maps.immutableEntry(
                  rule,
                  ruleKeyFactories.get(rule.getProjectFilesystem()).build(rule))));
    }
    ListenableFuture<List<Map.Entry<BuildRule, RuleKey>>> ruleKeyComputation =
        Futures.allAsList(ruleKeyEntries);
    return Futures.transform(
        ruleKeyComputation,
        new Function<List<Map.Entry<BuildRule, RuleKey>>, ImmutableMap<BuildRule, RuleKey>>() {
          @Override
          public ImmutableMap<BuildRule, RuleKey> apply(List<Map.Entry<BuildRule, RuleKey>> input) {
            return ImmutableMap.copyOf(input);
          }
        },
        executorService);
  }

  private static
  ListenableFuture<ImmutableList<BuildJobStateFileHashes>> fileHashesComputation(
      ListenableFuture<Void> ruleKeyComputationForSideEffect,
      final LoadingCache<ProjectFilesystem, BuildJobStateFileHashes> remoteFileHashes,
      ListeningExecutorService executorService) {
    return Futures.transform(
        ruleKeyComputationForSideEffect,
        new Function<Void, ImmutableList<BuildJobStateFileHashes>>() {
          @Override
          public ImmutableList<BuildJobStateFileHashes> apply(Void input) {
            return ImmutableList.copyOf(remoteFileHashes.asMap().values());
          }
        },
        executorService);
  }

  public List<BuildJobStateFileHashes> getFileHashes()
      throws IOException, InterruptedException {
    try {
      return fileHashes.get();
    } catch (ExecutionException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), IOException.class);
      Throwables.propagateIfInstanceOf(e.getCause(), InterruptedException.class);
      throw new RuntimeException(e.getCause());
    }
  }

  /**
   * Creates a {@link FileHashCache} that returns the hash codes cached on the remote end.
   *
   * @param projectFilesystem filesystem in which the new cache will be rooted. The serialized state
   *                          only contains relative path, therefore this is needed to indicate
   *                          where on the local machine we wish to transplant the files from the
   *                          remote to.
   * @param remoteFileHashes the serialized state.
   * @return the cache.
   */
  public static FileHashCache createFileHashCache(
      ProjectFilesystem projectFilesystem,
      BuildJobStateFileHashes remoteFileHashes) {
    return new RemoteStateBasedFileHashCache(projectFilesystem, remoteFileHashes);
  }

  public static ImmutableMap<Path, BuildJobStateFileHashEntry> indexEntriesByPath(
      final ProjectFilesystem projectFilesystem,
      BuildJobStateFileHashes remoteFileHashes) {
    if (!remoteFileHashes.isSetEntries()) {
      return ImmutableMap.of();
    }
    return FluentIterable.from(remoteFileHashes.entries)
        .filter(input -> !input.isPathIsAbsolute() && !input.isSetArchiveMemberPath())
        .uniqueIndex(
            input -> projectFilesystem.resolve(
                MorePaths.pathWithPlatformSeparators(input.getPath().getPath())));
  }

  public static ImmutableMap<ArchiveMemberPath, BuildJobStateFileHashEntry>
  indexEntriesByArchivePath(
      final ProjectFilesystem projectFilesystem,
      BuildJobStateFileHashes remoteFileHashes) {
    if (!remoteFileHashes.isSetEntries()) {
      return ImmutableMap.of();
    }
    return FluentIterable.from(remoteFileHashes.entries)
        .filter(input -> !input.isPathIsAbsolute() && input.isSetArchiveMemberPath())
        .uniqueIndex(
            input -> ArchiveMemberPath.of(
                projectFilesystem.resolve(
                    MorePaths.pathWithPlatformSeparators(input.getPath().getPath())),
                Paths.get(input.getArchiveMemberPath())
            ));
  }
}
