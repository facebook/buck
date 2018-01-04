/*
 * Copyright 2017-present Facebook, Inc.
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
package com.facebook.buck.util.cache.impl;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.FileSystemMap;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.PathFragments;
import com.facebook.buck.util.cache.FileHashCacheEngine;
import com.facebook.buck.util.cache.HashCodeAndFileType;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * LimitedFileHashCacheEngine is a FileHashCacheEngine backed by a FileSystemMap that only caches
 * values for files (not directories) that are not symlinks. That means it can be relied upon to
 * return the correct value for symlinks and directories that contain symlinks even if the symlink
 * targets change.
 */
// TODO(cjhopman): The underlying FileSystemMap actually properly invalidates directories (as long
// as there's no symlinks involved). And so this could, conceivably cache values for directory
// entries.
class LimitedFileHashCacheEngine implements FileHashCacheEngine {
  private enum FileType {
    FILE,
    SYMLINK,
    DIRECTORY,
  }

  // TODO(cjhopman): Should we make these recycleable? We only ever need one per path, so we could
  // just hold on to them and reuse them. Might be nice if the FileSystemMap closes/notifies them
  // so that we can drop reference to data.
  private final class Data {
    private final PathFragment path;
    private final FileType fileType;

    private final Supplier<ImmutableMap<Path, HashCode>> jarContentsHashes;
    private volatile Supplier<HashCodeAndFileType> hashCodeAndFileType;
    private volatile Supplier<Long> size;

    private Data(PathFragment path) {
      this.fileType = loadType(PathFragments.fragmentToPath(path));
      this.path = path;
      this.size = cachingIfCacheable(this::loadSize);
      this.hashCodeAndFileType = cachingIfCacheable(this::loadHashCodeAndFileType);
      this.jarContentsHashes = cachingIfCacheable(this::loadJarContentsHashes);
    }

    private <T> Supplier<T> cachingIfCacheable(Supplier<T> loader) {
      if (isCacheableFileType()) {
        return MoreSuppliers.memoize(loader);
      }
      return loader;
    }

    private ImmutableMap<Path, HashCode> loadJarContentsHashes() {
      try {
        return new DefaultJarContentHasher(filesystem, path)
            .getContentHashes()
            .entrySet()
            .stream()
            .collect(
                ImmutableMap.toImmutableMap(
                    entry -> entry.getKey(), entry -> entry.getValue().getHashCode()));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    private HashCodeAndFileType loadHashCodeAndFileType() {
      Path asPath = PathFragments.fragmentToPath(path);
      switch (fileType) {
        case FILE:
        case SYMLINK:
          HashCode loadedValue = fileHashLoader.load(asPath);
          if (isArchive(asPath)) {
            return HashCodeAndFileType.ofArchive(
                loadedValue, new DefaultJarContentHasher(filesystem, path));
          }
          return HashCodeAndFileType.ofFile(loadedValue);
        case DIRECTORY:
          HashCodeAndFileType loadedDirValue = dirHashLoader.load(asPath);
          Preconditions.checkState(loadedDirValue.getType() == HashCodeAndFileType.Type.DIRECTORY);
          return loadedDirValue;
      }
      throw new RuntimeException();
    }

    private long loadSize() {
      return sizeLoader.load(PathFragments.fragmentToPath(path));
    }

    public void set(HashCodeAndFileType value) {
      // TODO(cjhopman): should this verify filetype?
      this.hashCodeAndFileType = cachingIfCacheable(() -> value);
    }

    public void setSize(long size) {
      this.size = cachingIfCacheable(() -> size);
    }

    private boolean isCacheableFileType() {
      return fileType == FileType.FILE;
    }

    HashCodeAndFileType getHashCodeAndFileType() {
      return hashCodeAndFileType.get();
    }

    ImmutableMap<Path, HashCode> getJarContentsHashes() {
      return jarContentsHashes.get();
    }
  }

  private final ProjectFilesystem filesystem;
  private final ValueLoader<HashCode> fileHashLoader;
  private final ValueLoader<HashCodeAndFileType> dirHashLoader;
  private final ValueLoader<Long> sizeLoader;
  private final FileSystemMap<Data> fileSystemMap;

  public LimitedFileHashCacheEngine(
      ProjectFilesystem filesystem,
      ValueLoader<HashCode> fileHashLoader,
      ValueLoader<HashCodeAndFileType> dirHashLoader,
      ValueLoader<Long> sizeLoader) {
    this.filesystem = filesystem;
    this.fileHashLoader = fileHashLoader;
    this.dirHashLoader = dirHashLoader;
    this.sizeLoader = sizeLoader;
    this.fileSystemMap = new FileSystemMap<>(Data::new);
  }

  private FileType loadType(Path path) {
    try {
      BasicFileAttributes attrs = filesystem.readAttributes(path, BasicFileAttributes.class);
      if (attrs.isDirectory()) {
        return FileType.DIRECTORY;
      } else if (attrs.isSymbolicLink()) {
        return FileType.SYMLINK;
      } else if (attrs.isRegularFile()) {
        return FileType.FILE;
      }
      throw new RuntimeException("Unrecognized file type at " + path);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void put(Path path, HashCodeAndFileType value) {
    fileSystemMap.get(path).set(value);
  }

  @Override
  public void putSize(Path path, long value) {
    fileSystemMap.get(path).setSize(value);
  }

  @Override
  public void invalidate(Path path) {
    fileSystemMap.remove(path);
  }

  @Override
  public void invalidateWithParents(Path path) {
    invalidate(path);
  }

  @Override
  public HashCode get(Path path) throws IOException {
    return fileSystemMap.get(path).getHashCodeAndFileType().getHashCode();
  }

  @Override
  public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
    Path relativeFilePath = archiveMemberPath.getArchivePath().normalize();
    Preconditions.checkState(isArchive(relativeFilePath), relativeFilePath + " is not an archive.");
    Data data = fileSystemMap.get(relativeFilePath);
    Path memberPath = archiveMemberPath.getMemberPath();
    HashCode hashCode = data.getJarContentsHashes().get(memberPath);
    if (hashCode == null) {
      throw new NoSuchFileException(archiveMemberPath.toString());
    }
    return hashCode;
  }

  private static boolean isArchive(Path path) {
    return path.getFileName().toString().endsWith(".jar");
  }

  @Override
  public long getSize(Path relativePath) throws IOException {
    return fileSystemMap.get(relativePath).size.get();
  }

  @Override
  public void invalidateAll() {
    fileSystemMap.removeAll();
  }

  @Override
  @Nullable
  public HashCodeAndFileType getIfPresent(Path path) {
    return Optional.ofNullable(fileSystemMap.getIfPresent(path))
        .map(Data::getHashCodeAndFileType)
        .orElse(null);
  }

  @Override
  @Nullable
  public Long getSizeIfPresent(Path path) {
    return Optional.ofNullable(fileSystemMap.getIfPresent(path))
        .map(data -> data.size.get())
        .orElse(null);
  }

  @Override
  public ConcurrentMap<Path, HashCodeAndFileType> asMap() {
    return new ConcurrentHashMap<>(
        Maps.transformValues(
            fileSystemMap.asMap(), v -> Preconditions.checkNotNull(v).getHashCodeAndFileType()));
  }

  @Override
  public List<AbstractBuckEvent> getStatsEvents() {
    return Collections.emptyList();
  }
}
