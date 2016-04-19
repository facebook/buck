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
import com.google.common.base.Function;
import com.google.common.base.Optional;
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
import com.google.common.io.ByteSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nonnull;

public class DefaultFileHashCache implements ProjectFileHashCache {

  private final ProjectFilesystem projectFilesystem;

  @VisibleForTesting
  final LoadingCache<Path, HashCodeAndFileType> loadingCache;

  public DefaultFileHashCache(ProjectFilesystem projectFilesystem) {
    this.projectFilesystem = projectFilesystem;

    this.loadingCache = CacheBuilder.newBuilder()
        .build(new CacheLoader<Path, HashCodeAndFileType>() {
          @Override
          public HashCodeAndFileType load(@Nonnull Path path) throws Exception {
            return getHashCodeAndFileType(path);
          }
        });
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

  private HashCode getFileHashCode(final Path path) throws IOException {
    ByteSource source =
        new ByteSource() {
          @Override
          public InputStream openStream() throws IOException {
            if (!path.isAbsolute()) {
              return projectFilesystem.newFileInputStream(path);
            } else {
              return Files.newInputStream(path);
            }
          }
        };
    return source.hash(Hashing.sha1());
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
    Preconditions.checkState(!projectFilesystem.isIgnored(relativePath.get()));
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
        !projectFilesystem.isIgnored(relativePath.get()));
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
  }

  @Override
  public void invalidateAll() {
    loadingCache.invalidateAll();
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
                      new Function<Path, Path>() {
                        @Override
                        public Path apply(Path input) {
                          return path.relativize(input);
                        }
                      })));
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
