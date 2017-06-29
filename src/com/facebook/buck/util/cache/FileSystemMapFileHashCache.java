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

package com.facebook.buck.util.cache;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.FileHashCacheEvent;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.util.FileSystemMap;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;

class FileSystemMapFileHashCache implements FileHashCacheEngine {

  private final FileSystemMap<HashCodeAndFileType> loadingCache;
  private final FileSystemMap<Long> sizeCache;

  private long cacheRetrievalAggregatedNanoTime = 0;
  private long cacheInvalidationAggregatedNanoTime = 0;
  private long numberOfInvalidations = 0;
  private long numberOfRetrievals = 0;

  public FileSystemMapFileHashCache(
      ValueLoader<HashCodeAndFileType> hashLoader, ValueLoader<Long> sizeLoader) {
    this.loadingCache = new FileSystemMap<>(hashLoader::load);
    this.sizeCache = new FileSystemMap<>(sizeLoader::load);
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
    long start = System.nanoTime();
    loadingCache.remove(path);
    sizeCache.remove(path);
    cacheInvalidationAggregatedNanoTime += System.nanoTime() - start;
    numberOfInvalidations++;
  }

  @Override
  public void invalidateWithParents(Path path) {
    invalidate(path);
  }

  @Override
  public HashCode get(Path path) throws IOException {
    long start = System.nanoTime();
    HashCode sha1 = loadingCache.get(path.normalize()).getHashCode();
    cacheRetrievalAggregatedNanoTime += System.nanoTime() - start;
    numberOfRetrievals++;
    return sha1;
  }

  @Override
  public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
    long start = System.nanoTime(); // NOPMD
    Path relativeFilePath = archiveMemberPath.getArchivePath().normalize();
    HashCodeAndFileType fileHashCodeAndFileType = loadingCache.get(relativeFilePath);
    Path memberPath = archiveMemberPath.getMemberPath();
    HashCodeAndFileType memberHashCodeAndFileType =
        fileHashCodeAndFileType.getContents().get(memberPath);
    if (memberHashCodeAndFileType == null) {
      throw new NoSuchFileException(archiveMemberPath.toString());
    }
    HashCode sha1 = memberHashCodeAndFileType.getHashCode();
    cacheRetrievalAggregatedNanoTime += System.nanoTime() - start;
    numberOfRetrievals++;
    return sha1;
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
    List<AbstractBuckEvent> events = new LinkedList<>();
    if (numberOfInvalidations > 0) {
      events.add(
          new FileHashCacheEvent(
              "new.invalidation",
              cacheInvalidationAggregatedNanoTime,
              cacheInvalidationAggregatedNanoTime,
              numberOfInvalidations));
    }
    if (numberOfRetrievals > 0) {
      events.add(
          new FileHashCacheEvent(
              "new.retrieval",
              cacheRetrievalAggregatedNanoTime,
              cacheRetrievalAggregatedNanoTime,
              numberOfRetrievals));
    }
    cacheInvalidationAggregatedNanoTime = 0;
    cacheRetrievalAggregatedNanoTime = 0;
    numberOfInvalidations = 0;
    numberOfRetrievals = 0;
    return events;
  }
}
