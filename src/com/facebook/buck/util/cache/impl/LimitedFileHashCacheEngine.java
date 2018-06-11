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

  static final byte ARCHIVE_TYPE_UNDEFINED = 0;
  static final byte ARCHIVE_TYPE_NOT_ARCHIVE = 1;
  static final byte ARCHIVE_TYPE_JAR = 2;

  // TODO(cjhopman): Should we make these recycleable? We only ever need one per path, so we could
  // just hold on to them and reuse them. Might be nice if the FileSystemMap closes/notifies them
  // so that we can drop reference to data.
  // (sergeyb): Please do not add any more fields to this data structure or at least make sure they
  // are optimized for memory footprint
  private static final class Data {
    // One of FILE_TYPE consts
    private volatile byte fileType;
    // One of ARCHIVE_TYPE consts
    private volatile byte archiveType = ARCHIVE_TYPE_UNDEFINED;
    private @Nullable ImmutableMap<Path, HashCode> jarContentsHashes = null;
    private volatile @Nullable HashCode hashCode = null;
    private volatile long size = -1;

    private Data(byte fileType) {
      this.fileType = fileType;
    }
  }

  private final ProjectFilesystem filesystem;
  private final ValueLoader<HashCode> fileHashLoader;
  private final ValueLoader<HashCode> dirHashLoader;
  private final ValueLoader<Long> sizeLoader;
  private final FileSystemMap<Data> fileSystemMap;

  public LimitedFileHashCacheEngine(
      ProjectFilesystem filesystem,
      ValueLoader<HashCode> fileHashLoader,
      ValueLoader<HashCode> dirHashLoader,
      ValueLoader<Long> sizeLoader) {
    this.filesystem = filesystem;
    this.fileHashLoader = fileHashLoader;
    this.dirHashLoader = dirHashLoader;
    this.sizeLoader = sizeLoader;
    this.fileSystemMap = new FileSystemMap<>(path -> new Data(loadType(path)), filesystem);
  }

  private ImmutableMap<Path, HashCode> loadJarContentsHashes(Path path) {
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

  private long getSizeWithLoad(Path path, Data data) {
    if (data.size == -1) {
      long size = sizeLoader.load(path);
      if (!isCacheableFileType(data)) {
        return size;
      }
      data.size = size;
    }
    return data.size;
  }

  private ImmutableMap<Path, HashCode> getJarContentsHashesWithLoad(Path path, Data data) {
    if (data.jarContentsHashes == null) {
      ImmutableMap<Path, HashCode> jarCaches = loadJarContentsHashes(path);
      if (!isCacheableFileType(data)) {
        return jarCaches;
      }
      data.jarContentsHashes = jarCaches;
    }
    return data.jarContentsHashes;
  }

  private HashCodeAndFileType getHashCodeAndFileTypeWithLoad(Path path, Data data) {
    HashCode hashCode = getHashCodeWithLoad(path, data);

    if (data.fileType == FILE_TYPE_DIRECTORY) {
      return HashCodeAndFileType.ofDirectory(hashCode);
    }
    // Load archive type lazily the first time the function is called for that path
    // this avoids expensive calls to isArchive() which uses Path translation
    if (data.archiveType == ARCHIVE_TYPE_UNDEFINED) {
      data.archiveType = isArchive(path) ? ARCHIVE_TYPE_JAR : ARCHIVE_TYPE_NOT_ARCHIVE;
    }
    if (data.archiveType == ARCHIVE_TYPE_JAR) {
      return JarHashCodeAndFileType.ofArchive(
          hashCode, new DefaultJarContentHasher(filesystem, path));
    }
    return HashCodeAndFileType.ofFile(hashCode);
  }

  private HashCode getHashCodeWithLoad(Path path, Data data) {
    HashCode code = data.hashCode;
    if (code == null) {
      code = loadHashCode(path, data);
      if (isCacheableFileType(data)) {
        data.hashCode = code;
      }
    }
    return code;
  }

  private boolean isCacheableFileType(Data data) {
    return data.fileType == FILE_TYPE_FILE;
  }

  private HashCode loadHashCode(Path path, Data data) {
    if (data.fileType == FILE_TYPE_DIRECTORY) {
      return dirHashLoader.load(path);
    }
    return fileHashLoader.load(path);
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
    Data data = fileSystemMap.get(path);
    data.hashCode = value.getHashCode();
    data.fileType = value.getType();
  }

  @Override
  public void putSize(Path path, long value) {
    fileSystemMap.get(path).size = value;
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
    return getHashCodeWithLoad(path, fileSystemMap.get(path));
  }

  @Override
  public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
    Path relativeFilePath = archiveMemberPath.getArchivePath().normalize();
    Preconditions.checkState(isArchive(relativeFilePath), relativeFilePath + " is not an archive.");
    Data data = fileSystemMap.get(relativeFilePath);
    Path memberPath = archiveMemberPath.getMemberPath();
    HashCode hashCode = getJarContentsHashesWithLoad(relativeFilePath, data).get(memberPath);
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
    return getSizeWithLoad(relativePath, fileSystemMap.get(relativePath));
  }

  @Override
  public void invalidateAll() {
    fileSystemMap.removeAll();
  }

  @Override
  @Nullable
  public HashCode getIfPresent(Path path) {
    Data data = fileSystemMap.getIfPresent(path);
    if (data == null) {
      return null;
    }
    return getHashCodeWithLoad(path, data);
  }

  @Override
  @Nullable
  public Long getSizeIfPresent(Path path) {
    Data data = fileSystemMap.getIfPresent(path);
    if (data == null) {
      return null;
    }
    return getSizeWithLoad(path, data);
  }

  @Override
  public ConcurrentMap<Path, HashCodeAndFileType> asMap() {
    return new ConcurrentHashMap<>(
        Maps.transformEntries(
            fileSystemMap.asMap(), (k, v) -> getHashCodeAndFileTypeWithLoad(k, v)));
  }

  @Override
  public List<AbstractBuckEvent> getStatsEvents() {
    return Collections.emptyList();
  }
}
