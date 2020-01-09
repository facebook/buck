/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.util.cache.impl;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Wraps a collection of {@link ProjectFilesystem}-specific {@link ProjectFileHashCache}s as a
 * single cache, implementing a Chain of Responsibility to find and forward operations to the
 * correct inner cache. As this "multi"-cache is meant to handle paths across different {@link
 * ProjectFilesystem}s, as opposed to paths within the same {@link ProjectFilesystem}, it is a
 * distinct type from {@link ProjectFileHashCache}.
 *
 * <p>This "stacking" approach provides a few appealing properties:
 *
 * <ol>
 *   <li>It makes it easier to module path roots with differing hash cached lifetime requirements.
 *       Hashes of paths from roots watched by watchman can be cached indefinitely, until a watchman
 *       event triggers invalidation. Hashes of paths under roots not watched by watchman, however,
 *       can only be cached for the duration of a single build (as we have no way to know when these
 *       paths are modified). By using separate {@link ProjectFileHashCache}s per path root, we can
 *       construct a new {@link StackedFileHashCache} on each build composed of either persistent or
 *       ephemeral per-root inner caches that properly manager the lifetime of cached hashes from
 *       their root.
 *   <li>Modeling the hash cache around path root and sub-paths also works well with a current
 *       limitation with our watchman events in which they only store relative paths, with no
 *       reference to the path root they originated from. If we stored hashes internally indexed by
 *       absolute path, then we wouldn't know where to anchor the search to resolve the path that a
 *       watchman event refers to (e.g. a watch event for `foo.h` could refer to `/a/b/foo.h` or
 *       `/a/b/c/foo.h`, depending on where the project root is). By indexing hashes by pairs of
 *       project root and sub-path, it's easier to identity paths to invalidate (e.g. `foo.h` would
 *       invalidate (`/a/b/`,`foo.h`) and not (`/a/b/`,`c/foo.h`)).
 *   <li>Since the current implementation of inner caches and callers generally use path root and
 *       sub-path pairs, it allows avoiding any overhead converting to/from absolute paths.
 * </ol>
 */
public class StackedFileHashCache implements FileHashCache {

  /**
   * Used to make sure that for two Paths where one is a sub-path of another, the longer Path
   * appears first in the StackedFileHasCache. This is important for finding matching cells in the
   * `lookup` function.
   */
  static class FileHashCacheComparator implements Comparator<ProjectFileHashCache> {
    @Override
    public int compare(ProjectFileHashCache c1, ProjectFileHashCache c2) {
      return c2.getFilesystem()
          .getRootPath()
          .toString()
          .compareTo(c1.getFilesystem().getRootPath().toString());
    }
  }

  private final ImmutableList<? extends ProjectFileHashCache> caches;

  public StackedFileHashCache(ImmutableList<? extends ProjectFileHashCache> caches) {
    this.caches = ImmutableList.sortedCopyOf(new FileHashCacheComparator(), caches);
  }

  public static StackedFileHashCache createDefaultHashCaches(
      ProjectFilesystem filesystem, FileHashCacheMode fileHashCacheMode) {
    return new StackedFileHashCache(
        ImmutableList.of(
            DefaultFileHashCache.createDefaultFileHashCache(filesystem, fileHashCacheMode),
            DefaultFileHashCache.createBuckOutFileHashCache(filesystem, fileHashCacheMode)));
  }

  /**
   * @return the {@link ProjectFileHashCache} which handles the given relative {@link Path} under
   *     the given {@link ProjectFilesystem}.
   */
  private Optional<? extends ProjectFileHashCache> lookup(ProjectFilesystem filesystem, Path path) {
    for (ProjectFileHashCache cache : caches) {
      // TODO(agallagher): This should check for equal filesystems probably shouldn't be using the
      // root path, but we currently rely on this behavior.
      if (cache.getFilesystem().getRootPath().equals(filesystem.getRootPath())
          && cache.willGet(path)) {
        return Optional.of(cache);
      }
    }
    return Optional.empty();
  }

  private Optional<Pair<ProjectFileHashCache, Path>> lookup(Path path) {
    Preconditions.checkArgument(path.isAbsolute());
    for (ProjectFileHashCache cache : caches) {
      Optional<Path> relativePath = cache.getFilesystem().getPathRelativeToProjectRoot(path);
      if (relativePath.isPresent() && cache.willGet(relativePath.get())) {
        return Optional.of(new Pair<>(cache, relativePath.get()));
      }
    }
    return Optional.empty();
  }

  @Override
  public void invalidate(Path path) {
    for (ProjectFileHashCache cache : caches) {
      Optional<Path> relativePath = cache.getFilesystem().getPathRelativeToProjectRoot(path);
      if (relativePath.isPresent() && cache.willGet(relativePath.get())) {
        cache.invalidate(relativePath.get());
      }
    }
  }

  @Override
  public void invalidateAll() {
    for (ProjectFileHashCache cache : caches) {
      cache.invalidateAll();
    }
  }

  @Override
  public HashCode get(Path path) throws IOException {
    Optional<Pair<ProjectFileHashCache, Path>> found = lookup(path);
    if (!found.isPresent()) {
      throw new NoSuchFileException(path.toString());
    }
    return found.get().getFirst().get(found.get().getSecond());
  }

  @Override
  public long getSize(Path path) throws IOException {
    Optional<Pair<ProjectFileHashCache, Path>> found = lookup(path);
    if (!found.isPresent()) {
      throw new NoSuchFileException(path.toString());
    }
    return found.get().getFirst().getSize(found.get().getSecond());
  }

  @Override
  public HashCode getForArchiveMember(Path relativeArchivePath, Path memberPath)
      throws IOException {
    Optional<Pair<ProjectFileHashCache, Path>> found = lookup(relativeArchivePath);
    if (!found.isPresent()) {
      throw new NoSuchFileException(relativeArchivePath.toString());
    }
    return found.get().getFirst().getForArchiveMember(found.get().getSecond(), memberPath);
  }

  @Override
  public void set(Path path, HashCode hashCode) throws IOException {
    Optional<Pair<ProjectFileHashCache, Path>> found = lookup(path);
    if (found.isPresent()) {
      found.get().getFirst().set(found.get().getSecond(), hashCode);
    }
  }

  @Override
  public FileHashCacheVerificationResult verify() throws IOException {
    ImmutableList.Builder<String> verificationErrors = ImmutableList.builder();
    int cachesExamined = 1;
    int filesExamined = 0;

    for (ProjectFileHashCache cache : caches) {
      FileHashCache.FileHashCacheVerificationResult result = cache.verify();
      cachesExamined += result.getCachesExamined();
      filesExamined += result.getFilesExamined();
      verificationErrors.addAll(result.getVerificationErrors());
    }

    return FileHashCacheVerificationResult.of(
        cachesExamined, filesExamined, verificationErrors.build());
  }

  @Override
  public Stream<Map.Entry<Path, HashCode>> debugDump() {
    return caches.stream().flatMap(ProjectFileHashCache::debugDump);
  }

  @Override
  public HashCode get(ProjectFilesystem filesystem, Path path) throws IOException {
    return lookup(filesystem, path)
        .orElseThrow(() -> new NoSuchFileException(filesystem.resolve(path).toString()))
        .get(path);
  }

  @Override
  public HashCode getForArchiveMember(
      ProjectFilesystem filesystem, Path relativeArchivePath, Path memberPath) throws IOException {
    return lookup(filesystem, relativeArchivePath)
        .orElseThrow(
            () -> new NoSuchFileException(filesystem.resolve(relativeArchivePath).toString()))
        .getForArchiveMember(relativeArchivePath, memberPath);
  }

  @Override
  public long getSize(ProjectFilesystem filesystem, Path path) throws IOException {
    return lookup(filesystem, path)
        .orElseThrow(() -> new NoSuchFileException(filesystem.resolve(path).toString()))
        .getSize(path);
  }

  @Override
  public void set(ProjectFilesystem filesystem, Path path, HashCode hashCode) throws IOException {
    Optional<? extends ProjectFileHashCache> cache = lookup(filesystem, path);
    if (cache.isPresent()) {
      cache.get().set(path, hashCode);
    }
  }

  public StackedFileHashCache newDecoratedFileHashCache(
      Function<ProjectFileHashCache, ProjectFileHashCache> decorateDelegate) {
    ImmutableList.Builder<ProjectFileHashCache> decoratedCaches = ImmutableList.builder();
    for (ProjectFileHashCache cache : caches) {
      decoratedCaches.add(decorateDelegate.apply(cache));
    }

    return new StackedFileHashCache(decoratedCaches.build());
  }

  public ImmutableList<? extends ProjectFileHashCache> getCaches() {
    return caches;
  }
}
