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
import com.facebook.buck.util.FileSystemMap;
import com.facebook.buck.util.PathFragments;
import com.facebook.buck.util.cache.FileHashCacheEngine;
import com.facebook.buck.util.cache.HashCodeAndFileType;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;

class FileSystemMapFileHashCache implements FileHashCacheEngine {
  private final FileSystemMap<HashCodeAndFileType> loadingCache;
  private final FileSystemMap<Long> sizeCache;

  private FileSystemMapFileHashCache(
      ValueLoader<HashCodeAndFileType> hashLoader, ValueLoader<Long> sizeLoader) {
    this.loadingCache =
        new FileSystemMap<>(fragment -> hashLoader.load(PathFragments.fragmentToPath(fragment)));
    this.sizeCache =
        new FileSystemMap<>(fragment -> sizeLoader.load(PathFragments.fragmentToPath(fragment)));
  }

  public static FileHashCacheEngine createWithStats(
      ValueLoader<HashCodeAndFileType> hashLoader, ValueLoader<Long> sizeLoader) {
    return new StatsTrackingFileHashCacheEngine(
        new FileSystemMapFileHashCache(hashLoader, sizeLoader), "new");
  }

  @Override
  public void put(Path path, HashCodeAndFileType value) {
    loadingCache.put(path, value);
  }

  @Override
  public void putSize(Path path, long value) {
    sizeCache.put(path, value);
  }

  @Override
  public void invalidate(Path path) {
    loadingCache.remove(path);
    sizeCache.remove(path);
  }

  @Override
  public void invalidateWithParents(Path path) {
    invalidate(path);
  }

  @Override
  public HashCode get(Path path) throws IOException {
    return loadingCache.get(path.normalize()).getHashCode();
  }

  @Override
  public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
    Path relativeFilePath = archiveMemberPath.getArchivePath().normalize();
    HashCodeAndFileType fileHashCodeAndFileType = loadingCache.get(relativeFilePath);
    Path memberPath = archiveMemberPath.getMemberPath();
    HashCodeAndFileType memberHashCodeAndFileType =
        fileHashCodeAndFileType.getContents().get(memberPath);
    if (memberHashCodeAndFileType == null) {
      throw new NoSuchFileException(archiveMemberPath.toString());
    }
    return memberHashCodeAndFileType.getHashCode();
  }

  @Override
  public long getSize(Path relativePath) throws IOException {
    return sizeCache.get(relativePath.normalize());
  }

  @Override
  public void invalidateAll() {
    loadingCache.removeAll();
    sizeCache.removeAll();
  }

  @Override
  @Nullable
  public HashCodeAndFileType getIfPresent(Path path) {
    return loadingCache.getIfPresent(path);
  }

  @Override
  @Nullable
  public Long getSizeIfPresent(Path path) {
    return sizeCache.getIfPresent(path);
  }

  @Override
  public ConcurrentMap<Path, HashCodeAndFileType> asMap() {
    return new ConcurrentHashMap<>(loadingCache.asMap());
  }

  @Override
  public List<AbstractBuckEvent> getStatsEvents() {
    return Collections.emptyList();
  }
}
