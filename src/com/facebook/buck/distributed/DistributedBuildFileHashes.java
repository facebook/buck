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
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import javax.annotation.concurrent.GuardedBy;

/**
 * Responsible for extracting file hash and {@link RuleKey} information from the {@link ActionGraph}
 * and presenting it as a Thrift data structure.
 */
public class DistributedBuildFileHashes {

  private static final Function<BuildJobStateFileHashEntry, HashCode>
      HASH_CODE_FROM_FILE_HASH_ENTRY =
      new Function<BuildJobStateFileHashEntry, HashCode>() {
        @Override
        public HashCode apply(BuildJobStateFileHashEntry input) {
          return HashCode.fromString(input.getHashCode());
        }
      };

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
        Futures.transform(this.ruleKeys, Functions.<Void>constant(null)),
        this.remoteFileHashes,
        executorService);
  }

  public static LoadingCache<ProjectFilesystem, DefaultRuleKeyBuilderFactory>
  createRuleKeyFactories(
      final SourcePathResolver sourcePathResolver,
      final LoadingCache<ProjectFilesystem, FileHashLoader> fileHashLoaders,
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

  /**
   * Creates a {@link FileHashLoader} which creates the files that are being hashed but does
   * not return a HashCode.
   *
   * @param projectFilesystem filesystem in which the new cache will be rooted. The serialized state
   *                          only contains relative path, therefore this is needed to indicate
   *                          where on the local machine we wish to transplant the files from the
   *                          remote to.
   * @param remoteFileHashes the serialized state.
   * @param provider of the file contents.
   * @return the loader.
   */
  public static FileHashLoader createMaterializingLoader(
      ProjectFilesystem projectFilesystem,
      BuildJobStateFileHashes remoteFileHashes,
      FileContentsProvider provider) {
    return new FileMaterializer(projectFilesystem, remoteFileHashes, provider);
  }

  private static ImmutableMap<Path, BuildJobStateFileHashEntry> indexEntriesByPath(
      final ProjectFilesystem projectFilesystem,
      BuildJobStateFileHashes remoteFileHashes) {
    return FluentIterable.from(remoteFileHashes.entries)
        .filter(new Predicate<BuildJobStateFileHashEntry>() {
          @Override
          public boolean apply(BuildJobStateFileHashEntry input) {
            return !input.isPathIsAbsolute() && !input.isSetArchiveMemberPath();
          }
        })
        .uniqueIndex(
            new Function<BuildJobStateFileHashEntry, Path>() {
              @Override
              public Path apply(BuildJobStateFileHashEntry input) {
                return projectFilesystem.resolve(
                    MorePaths.pathWithPlatformSeparators(input.getPath().getPath()));
              }
            });
  }

  private static ImmutableMap<ArchiveMemberPath, BuildJobStateFileHashEntry>
  indexEntriesByArchivePath(
      final ProjectFilesystem projectFilesystem,
      BuildJobStateFileHashes remoteFileHashes) {
    return FluentIterable.from(remoteFileHashes.entries)
        .filter(new Predicate<BuildJobStateFileHashEntry>() {
          @Override
          public boolean apply(BuildJobStateFileHashEntry input) {
            return !input.isPathIsAbsolute() && input.isSetArchiveMemberPath();
          }
        })
        .uniqueIndex(
            new Function<BuildJobStateFileHashEntry, ArchiveMemberPath>() {
              @Override
              public ArchiveMemberPath apply(BuildJobStateFileHashEntry input) {
                return ArchiveMemberPath.of(
                    projectFilesystem.resolve(
                        MorePaths.pathWithPlatformSeparators(input.getPath().getPath())),
                    Paths.get(input.getArchiveMemberPath())
                );
              }
            });
  }

  private static class FileMaterializer implements FileHashLoader {
    private final Map<Path, BuildJobStateFileHashEntry> remoteFileHashes;
    private final Set<Path> materializedPaths;
    private final FileContentsProvider provider;

    public FileMaterializer(
        final ProjectFilesystem projectFilesystem,
        BuildJobStateFileHashes remoteFileHashes,
        FileContentsProvider provider) {
      this.remoteFileHashes = indexEntriesByPath(projectFilesystem, remoteFileHashes);
      this.materializedPaths = new HashSet<>();
      this.provider = provider;
    }

    private void materializeIfNeeded(Path path) throws IOException {
      if (materializedPaths.contains(path)) {
        return;
      }
      materializedPaths.add(path);

      BuildJobStateFileHashEntry fileHashEntry = remoteFileHashes.get(path);
      if (fileHashEntry == null ||
          fileHashEntry.isIsDirectory() ||
          fileHashEntry.isPathIsAbsolute()) {
        return;
      }

      Optional<InputStream> sourceStreamOptional = provider.getFileContents(fileHashEntry);
      if (!sourceStreamOptional.isPresent()) {
        throw new HumanReadableException(
            String.format(
                "Input source file is missing from stampede. File=[%s]",
                fileHashEntry.toString()));
      }

      Files.createDirectories(path.getParent());
      try (InputStream sourceStream = sourceStreamOptional.get()) {
        Files.copy(sourceStream, path);
      }
    }

    @Override
    public HashCode get(Path path) throws IOException {
      materializeIfNeeded(path);
      return HashCode.fromInt(0);
    }

    @Override
    public long getSize(Path path) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
      materializeIfNeeded(archiveMemberPath.getArchivePath());
      return HashCode.fromInt(0);
    }
  }

  private static class RemoteStateBasedFileHashCache implements FileHashCache {
    private final Map<Path, HashCode> remoteFileHashes;
    private final Map<ArchiveMemberPath, HashCode> remoteArchiveHashes;

    public RemoteStateBasedFileHashCache(
        final ProjectFilesystem projectFilesystem,
        BuildJobStateFileHashes remoteFileHashes) {
      this.remoteFileHashes =
          Maps.transformValues(
              indexEntriesByPath(projectFilesystem, remoteFileHashes),
              HASH_CODE_FROM_FILE_HASH_ENTRY);
      this.remoteArchiveHashes =
          Maps.transformValues(
              indexEntriesByArchivePath(projectFilesystem, remoteFileHashes),
              HASH_CODE_FROM_FILE_HASH_ENTRY);
    }

    @Override
    public HashCode get(Path path) throws IOException {
      return Preconditions.checkNotNull(
          remoteFileHashes.get(path),
          "Path %s not in remote file hash.",
          path);
    }

    @Override
    public long getSize(Path path) throws IOException {
      return 0;
    }

    @Override
    public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
      return Preconditions.checkNotNull(
          remoteArchiveHashes.get(archiveMemberPath),
          "Archive path %s not in remote file hash.",
          archiveMemberPath);
    }

    @Override
    public boolean willGet(Path path) {
      return remoteFileHashes.containsKey(path);
    }

    @Override
    public boolean willGet(ArchiveMemberPath archiveMemberPath) {
      return remoteArchiveHashes.containsKey(archiveMemberPath);
    }

    @Override
    public void invalidate(Path path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void invalidateAll() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void set(Path path, HashCode hashCode) throws IOException {
      throw new UnsupportedOperationException();
    }
  }

  private static class RecordingFileHashLoader implements FileHashLoader {
    private final FileHashLoader delegate;
    private final ProjectFilesystem projectFilesystem;
    @GuardedBy("this")
    private final BuildJobStateFileHashes remoteFileHashes;
    @GuardedBy("this")
    private final Set<Path> seenPaths;
    @GuardedBy("this")
    private final Set<ArchiveMemberPath> seenArchives;

    public RecordingFileHashLoader(
        FileHashLoader delegate,
        ProjectFilesystem projectFilesystem,
        BuildJobStateFileHashes remoteFileHashes) {
      this.delegate = delegate;
      this.projectFilesystem = projectFilesystem;
      this.remoteFileHashes = remoteFileHashes;
      this.seenPaths = new HashSet<>();
      this.seenArchives = new HashSet<>();
    }

    @Override
    public HashCode get(Path path) throws IOException {
      HashCode hashCode = delegate.get(path);
      synchronized (this) {
        if (!seenPaths.contains(path)) {
          seenPaths.add(path);
          record(path, Optional.<String>absent(), hashCode);
        }
      }
      return hashCode;
    }

    @Override
    public long getSize(Path path) throws IOException {
      return delegate.getSize(path);
    }

    private synchronized void record(Path path, Optional<String> memberPath, HashCode hashCode) {
      Optional<Path> pathRelativeToProjectRoot =
          projectFilesystem.getPathRelativeToProjectRoot(path);
      BuildJobStateFileHashEntry fileHashEntry = new BuildJobStateFileHashEntry();
      Path entryKey;
      boolean pathIsAbsolute = !pathRelativeToProjectRoot.isPresent();
      fileHashEntry.setPathIsAbsolute(pathIsAbsolute);
      entryKey = pathIsAbsolute ? path : pathRelativeToProjectRoot.get();
      boolean isDirectory = projectFilesystem.isDirectory(path);
      fileHashEntry.setIsDirectory(isDirectory);
      fileHashEntry.setHashCode(hashCode.toString());
      fileHashEntry.setPath(
          new PathWithUnixSeparators(MorePaths.pathWithUnixSeparators(entryKey)));
      if (memberPath.isPresent()) {
        fileHashEntry.setArchiveMemberPath(memberPath.get().toString());
      }
      if (!isDirectory && !pathIsAbsolute) {
        try {
          // TODO(shivanker, ruibm): Don't read everything in memory right away.
          fileHashEntry.setContents(Files.readAllBytes(path));
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
      remoteFileHashes.addToEntries(fileHashEntry);
    }

    @Override
    public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
      HashCode hashCode = delegate.get(archiveMemberPath);
      synchronized (this) {
        if (!seenArchives.contains(archiveMemberPath)) {
          seenArchives.add(archiveMemberPath);
          record(
              archiveMemberPath.getArchivePath(),
              Optional.of(archiveMemberPath.getMemberPath().toString()),
              hashCode);
        }
      }
      return hashCode;
    }
  }
}
