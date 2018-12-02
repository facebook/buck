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
import com.facebook.buck.util.cache.FileHashCacheEngine;
import com.facebook.buck.util.cache.HashCodeAndFileType;
import com.facebook.buck.util.cache.JarHashCodeAndFileType;
import com.facebook.buck.util.filesystem.FileSystemMap;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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

  // Use constants instead of enums to save memory on data object

  static final byte FILE_TYPE_FILE = 0;
  static final byte FILE_TYPE_SYMLINK = 1;
  static final byte FILE_TYPE_DIRECTORY = 2;

  // TODO(cjhopman): Should we make these recycleable? We only ever need one per path, so we could
  // just hold on to them and reuse them. Might be nice if the FileSystemMap closes/notifies them
  // so that we can drop reference to data.
  // (sergeyb): Please do not add any more fields to this data structure or at least make sure they
  // are optimized for memory footprint
  private final class Data {
    private final Path path;

    // One of FILE_TYPE consts
    private final byte fileType;

    private @Nullable ImmutableMap<Path, HashCode> jarContentsHashes = null;
    private volatile @Nullable HashCodeAndFileType hashCodeAndFileType = null;
    private volatile long size = -1;

    private Data(Path path) {
      this.fileType = loadType(path);
      this.path = path;
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
      switch (fileType) {
        case FILE_TYPE_FILE:
        case FILE_TYPE_SYMLINK:
          HashCode loadedValue = fileHashLoader.load(path);
          if (isArchive(path)) {
            return JarHashCodeAndFileType.ofArchive(
                loadedValue, new DefaultJarContentHasher(filesystem, path));
          }
          return HashCodeAndFileType.ofFile(loadedValue);
        case FILE_TYPE_DIRECTORY:
          HashCodeAndFileType loadedDirValue = dirHashLoader.load(path);
          Preconditions.checkState(loadedDirValue.getType() == HashCodeAndFileType.TYPE_DIRECTORY);
          return loadedDirValue;
      }
      throw new RuntimeException();
    }

    private long loadSize() {
      return sizeLoader.load(path);
    }

    public void set(HashCodeAndFileType value) {
      // TODO(cjhopman): should this verify filetype?
      this.hashCodeAndFileType = value;
    }

    public void setSize(long size) {
      this.size = size;
    }

    public long getSize() {
      if (!isCacheableFileType() || size == -1) {
        size = loadSize();
      }
      return size;
    }

    private boolean isCacheableFileType() {
      return fileType == FILE_TYPE_FILE;
    }

    HashCodeAndFileType getHashCodeAndFileType() {
      if (hashCodeAndFileType == null) {
        HashCodeAndFileType codeAndType = loadHashCodeAndFileType();
        if (!isCacheableFileType()) {
          return codeAndType;
        }
        hashCodeAndFileType = codeAndType;
      }
      return hashCodeAndFileType;
    }

    ImmutableMap<Path, HashCode> getJarContentsHashes() {
      if (jarContentsHashes == null) {
        ImmutableMap<Path, HashCode> jarCaches = loadJarContentsHashes();
        if (!isCacheableFileType()) {
          return jarCaches;
        }
        jarContentsHashes = jarCaches;
      }
      return jarContentsHashes;
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
    this.fileSystemMap = new FileSystemMap<>(Data::new, filesystem);
  }

  private byte loadType(Path path) {
    try {
      BasicFileAttributes attrs = filesystem.readAttributes(path, BasicFileAttributes.class);
      if (attrs.isDirectory()) {
        return FILE_TYPE_DIRECTORY;
      } else if (attrs.isSymbolicLink()) {
        return FILE_TYPE_SYMLINK;
      } else if (attrs.isRegularFile()) {
        return FILE_TYPE_FILE;
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
  public HashCode get(Path path) {
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
  public long getSize(Path relativePath) {
    return fileSystemMap.get(relativePath).getSize();
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
        .map(data -> data.getSize())
        .orElse(null);
  }

  @Override
  public ConcurrentMap<Path, HashCodeAndFileType> asMap() {
    return new ConcurrentHashMap<>(
        Maps.transformValues(
            fileSystemMap.asMap(), v -> Objects.requireNonNull(v).getHashCodeAndFileType()));
  }

  @Override
  public List<AbstractBuckEvent> getStatsEvents() {
    return Collections.emptyList();
  }
}
