/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.util.cache;

import com.facebook.buck.hashing.PathHashing;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nonnull;

public class DefaultFileHashCache implements ProjectFileHashCache {

  private static final boolean SHOULD_CHECK_IGNORED_PATHS =
      Boolean.getBoolean("buck.DefaultFileHashCache.check_ignored_paths");

  private final ProjectFilesystem projectFilesystem;
  private final Optional<Path> buckOutPath;

  @VisibleForTesting
  final LoadingCache<Path, HashCodeAndFileType> loadingCache;

  @VisibleForTesting
  final LoadingCache<Path, Long> sizeCache;

  @VisibleForTesting
  DefaultFileHashCache(
      ProjectFilesystem projectFilesystem,
      Optional<Path> buckOutPath) {
    this.projectFilesystem = projectFilesystem;
    this.buckOutPath = buckOutPath;

    this.loadingCache =
        CacheBuilder.newBuilder().build(
            new CacheLoader<Path, HashCodeAndFileType>() {
              @Override
              public HashCodeAndFileType load(@Nonnull Path path) throws Exception {
                return getHashCodeAndFileType(path);
              }
            });

    this.sizeCache =
        CacheBuilder.newBuilder().build(
            new CacheLoader<Path, Long>() {
              @Override
              public Long load(@Nonnull Path path) throws Exception {
                return getPathSize(path);
              }
            });
  }

  public static FileHashCache createBuckOutFileHashCache(
      ProjectFilesystem projectFilesystem,
      Path buckOutPath) {
    return new DefaultFileHashCache(projectFilesystem, Optional.of(buckOutPath));
  }

  public static FileHashCache createDefaultFileHashCache(ProjectFilesystem projectFilesystem) {
    return new DefaultFileHashCache(projectFilesystem, Optional.empty());
  }

  private HashCodeAndFileType getHashCodeAndFileType(Path path) throws IOException {
    if (projectFilesystem.isDirectory(path)) {
      return getDirHashCode(path);
    } else if (path.toString().endsWith(".jar")) {
      return HashCodeAndFileType.ofArchive(
          getFileHashCode(path),
          projectFilesystem,
          path);
    }

    return HashCodeAndFileType.ofFile(getFileHashCode(path));
  }

  private HashCode getFileHashCode(Path path) throws IOException {
    return projectFilesystem.computeSha1(path).asHashCode();
  }

  private long getPathSize(Path path) throws IOException {
    long size = 0;
    for (Path child : projectFilesystem.getFilesUnderPath(path)) {
      size += projectFilesystem.getFileSize(child);
    }
    return size;
  }

  private HashCodeAndFileType getDirHashCode(Path path) throws IOException {
    Hasher hasher = Hashing.sha1().newHasher();
    ImmutableSet<Path> children =
        PathHashing.hashPath(hasher, this, projectFilesystem, path);
    return HashCodeAndFileType.ofDirectory(hasher.hash(), children);
  }

  public Path resolvePath(Path path) {
    Preconditions.checkState(path.isAbsolute());
    Optional<Path> relativePath = projectFilesystem.getPathRelativeToProjectRoot(path);
    if (SHOULD_CHECK_IGNORED_PATHS) {
      Preconditions.checkState(!isIgnored(relativePath.get()));
    }
    return relativePath.get();
  }

  @Override
  public boolean willGet(Path path) {
    Optional<Path> relativePath = projectFilesystem.getPathRelativeToProjectRoot(path);
    if (!relativePath.isPresent()) {
      return false;
    }
    return loadingCache.getIfPresent(relativePath.get()) != null ||
        (projectFilesystem.exists(relativePath.get()) &&
        !isIgnored(relativePath.get()));
  }

  private boolean isIgnored(Path path) {
    if (buckOutPath.isPresent()) {
      return path.startsWith(buckOutPath.get());
    }
    return projectFilesystem.isIgnored(path);
  }

  @Override
  public boolean willGet(ArchiveMemberPath archiveMemberPath) {
    return willGet(archiveMemberPath.getArchivePath());
  }

  @Override
  public void invalidate(Path rawPath) {
    Path path = resolvePath(rawPath);
    HashCodeAndFileType cached = loadingCache.getIfPresent(path);
    if (cached != null) {
      for (Path child : cached.getChildren()) {
        loadingCache.invalidate(path.resolve(child));
      }
      loadingCache.invalidate(path);
    }
    sizeCache.invalidate(path);
  }

  @Override
  public void invalidateAll() {
    loadingCache.invalidateAll();
    sizeCache.invalidateAll();
  }

  /**
   * @return The {@link com.google.common.hash.HashCode} of the contents of path.
   */
  @Override
  public HashCode get(Path rawPath) throws IOException {
    Path path = resolvePath(rawPath);
    HashCode sha1;
    try {
      sha1 = loadingCache.get(path.normalize()).getHashCode();
    } catch (ExecutionException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), IOException.class);
      throw Throwables.propagate(e.getCause());
    }
    return Preconditions.checkNotNull(sha1, "Failed to find a HashCode for %s.", path);
  }

  @Override
  public long getSize(Path rawPath) throws IOException {
    Path path = resolvePath(rawPath);
    try {
      return sizeCache.get(path.normalize());
    } catch (ExecutionException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), IOException.class);
      throw Throwables.propagate(e.getCause());
    }
  }

  @Override
  public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
    Preconditions.checkState(archiveMemberPath.isAbsolute());

    Path absoluteFilePath = archiveMemberPath.getArchivePath();
    Path relativeFilePath = resolvePath(absoluteFilePath).normalize();

    try {
      HashCodeAndFileType fileHashCodeAndFileType = loadingCache.get(relativeFilePath);

      Path memberPath = archiveMemberPath.getMemberPath();
      HashCodeAndFileType memberHashCodeAndFileType =
          fileHashCodeAndFileType.getContents().get(memberPath);
      if (memberHashCodeAndFileType == null) {
        throw new NoSuchFileException(archiveMemberPath.toString());
      }

      return memberHashCodeAndFileType.getHashCode();
    } catch (ExecutionException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), IOException.class);
      throw Throwables.propagate(e.getCause());
    }
  }

  @Override
  public ProjectFilesystem getFilesystem() {
    return projectFilesystem;
  }

  @Override
  public void set(Path rawPath, HashCode hashCode) throws IOException {
    final Path path = resolvePath(rawPath);
    HashCodeAndFileType value;

    if (projectFilesystem.isDirectory(path)) {
      value = HashCodeAndFileType.ofDirectory(
          hashCode,
          ImmutableSet.copyOf(
              FluentIterable.from(projectFilesystem.getFilesUnderPath(path))
                  .transform(
                      path::relativize)));
    } else if (rawPath.toString().endsWith(".jar")) {
      value = HashCodeAndFileType.ofArchive(
          hashCode,
          projectFilesystem,
          projectFilesystem.getPathRelativeToProjectRoot(path).get());

    } else {
      value = HashCodeAndFileType.ofFile(hashCode);
    }

    loadingCache.put(path, value);
  }

}
