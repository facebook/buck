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
import com.facebook.buck.distributed.thrift.PathWithUnixSeparators;
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
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

/**
 * Responsible for extracting file hash and {@link RuleKey} information from the {@link ActionGraph}
 * and presenting it as a Thrift data structure.
 */
public class DistributedBuildFileHashes {

  private final LoadingCache<ProjectFilesystem, BuildJobStateFileHashes> remoteFileHashes;
  private final LoadingCache<ProjectFilesystem, FileHashLoader> fileHashLoaders;
  private final LoadingCache<ProjectFilesystem, DefaultRuleKeyBuilderFactory> ruleKeyFactories;

  private final ListenableFuture<ImmutableList<BuildJobStateFileHashes>> fileHashes;
  private final ListenableFuture<ImmutableMap<BuildRule, RuleKey>>
      ruleKeys;

  public DistributedBuildFileHashes(
      ActionGraph actionGraph,
      final SourcePathResolver sourcePathResolver,
      final FileHashCache rootCellFileHashCache,
      ListeningExecutorService executorService,
      final int keySeed) {

    this.remoteFileHashes = CacheBuilder.newBuilder().build(
        new CacheLoader<ProjectFilesystem, BuildJobStateFileHashes>() {
          @Override
          public BuildJobStateFileHashes load(ProjectFilesystem filesystem) throws Exception {
            BuildJobStateFileHashes fileHashes = new BuildJobStateFileHashes();
            fileHashes.setFileSystemRootName(filesystem.getRootPath().toString());
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
    this.ruleKeyFactories = CacheBuilder.newBuilder().build(
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
    this.ruleKeys = ruleKeyComputation(actionGraph, this.ruleKeyFactories, executorService);
    this.fileHashes = fileHashesComputation(
        Futures.transform(this.ruleKeys, Functions.<Void>constant(null)),
        this.remoteFileHashes,
        executorService);
  }

  private static ListenableFuture<ImmutableMap<BuildRule, RuleKey>> ruleKeyComputation(
      ActionGraph actionGraph,
      final LoadingCache<ProjectFilesystem, DefaultRuleKeyBuilderFactory> ruleKeyFactories,
      ListeningExecutorService executorService) {
    List<ListenableFuture<Map.Entry<BuildRule, RuleKey>>> ruleKeyEntries = new ArrayList<>();
    for (final BuildRule rule : actionGraph.getNodes()) {
      ruleKeyEntries.add(
          executorService.submit(
              new Callable<Map.Entry<BuildRule, RuleKey>>() {
                @Override
                public Map.Entry<BuildRule, RuleKey> call() throws Exception {
                  return Maps.immutableEntry(
                      rule,
                      ruleKeyFactories.get(rule.getProjectFilesystem()).build(rule));
                }
              }));
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

  public ImmutableMap<BuildRule, RuleKey> getRuleKeys() throws IOException, InterruptedException {
    try {
      return ruleKeys.get();
    } catch (ExecutionException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), IOException.class);
      Throwables.propagateIfInstanceOf(e.getCause(), InterruptedException.class);
      throw new RuntimeException(e.getCause());
    }
  }

  private static class RecordingFileHashLoader implements FileHashLoader {
    private final FileHashLoader delegate;
    private final ProjectFilesystem projectFilesystem;
    private final BuildJobStateFileHashes remoteFileHashes;
    private final Set<Path> seenPaths;

    public RecordingFileHashLoader(
        FileHashLoader delegate,
        ProjectFilesystem projectFilesystem,
        BuildJobStateFileHashes remoteFileHashes) {
      this.delegate = delegate;
      this.projectFilesystem = projectFilesystem;
      this.remoteFileHashes = remoteFileHashes;
      this.seenPaths = new HashSet<>();
    }

    @Override
    public HashCode get(Path path) throws IOException {
      HashCode hashCode = delegate.get(path);
      record(path, hashCode);
      return hashCode;
    }

    @Override
    public long getSize(Path path) throws IOException {
      return delegate.getSize(path);
    }

    private void record(Path path, HashCode hashCode) {
      if (seenPaths.contains(path)) {
        return;
      }
      seenPaths.add(path);

      Optional<Path> pathRelativeToProjectRoot =
          projectFilesystem.getPathRelativeToProjectRoot(path);
      BuildJobStateFileHashEntry fileHashEntry = new BuildJobStateFileHashEntry();
      Path entryKey;
      if (pathRelativeToProjectRoot.isPresent()) {
        entryKey = pathRelativeToProjectRoot.get();
        fileHashEntry.setPathIsAbsolute(false);
      } else {
        entryKey = path;
        fileHashEntry.setPathIsAbsolute(true);
      }
      fileHashEntry.setIsDirectory(projectFilesystem.isDirectory(path));
      fileHashEntry.setHashCode(hashCode.toString());
      fileHashEntry.setPath(
          new PathWithUnixSeparators(MorePaths.pathWithUnixSeparators(entryKey)));
      remoteFileHashes.addToEntries(fileHashEntry);
    }

    @Override
    public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
      // Ensure the content hash for the entire archive is present. When materializing state the
      // archive file will be pulled first and the hashes for the entries can be calculated the
      // usual way.
      get(archiveMemberPath.getArchivePath());

      return delegate.get(archiveMemberPath);
    }
  }
}
