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
import com.facebook.buck.event.ExperimentEvent;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.cache.FileHashCacheEngine;
import com.facebook.buck.util.cache.HashCodeAndFileType;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

class ComboFileHashCache implements FileHashCacheEngine {

  private final List<FileHashCacheEngine> fileHashCacheEngines;
  private long sha1Mismatches = 0;

  public ComboFileHashCache(
      ValueLoader<HashCodeAndFileType> hashLoader,
      ValueLoader<Long> sizeLoader,
      ProjectFilesystem filesystem) {
    this(
        LoadingCacheFileHashCache.createWithStats(hashLoader, sizeLoader),
        FileSystemMapFileHashCache.createWithStats(hashLoader, sizeLoader, filesystem));
  }

  public ComboFileHashCache(FileHashCacheEngine... engines) {
    this.fileHashCacheEngines = ImmutableList.copyOf(engines);
  }

  @Override
  public void put(Path path, HashCodeAndFileType value) {
    for (FileHashCacheEngine fileHashCacheEngine : fileHashCacheEngines) {
      fileHashCacheEngine.put(path, value);
    }
  }

  @Override
  public void putSize(Path path, long value) {
    for (FileHashCacheEngine fileHashCacheEngine : fileHashCacheEngines) {
      fileHashCacheEngine.putSize(path, value);
    }
  }

  @Override
  public void invalidate(Path path) {
    for (FileHashCacheEngine fileHashCacheEngine : fileHashCacheEngines) {
      fileHashCacheEngine.invalidate(path);
    }
  }

  @Override
  public void invalidateWithParents(Path path) {
    for (FileHashCacheEngine fileHashCacheEngine : fileHashCacheEngines) {
      fileHashCacheEngine.invalidateWithParents(path);
    }
  }

  @Override
  public HashCode get(Path path) throws IOException {
    List<HashCode> hashes =
        fileHashCacheEngines
            .stream()
            .map(
                fhc -> {
                  try {
                    return fhc.get(path);
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                })
            .distinct()
            .collect(Collectors.toList());
    if (hashes.size() > 1) {
      sha1Mismatches++;
    }
    return hashes.get(0); // Return the old one by default.
  }

  @Override
  public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
    List<HashCode> hashes =
        fileHashCacheEngines
            .stream()
            .map(
                fhc -> {
                  try {
                    return fhc.get(archiveMemberPath);
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                })
            .distinct()
            .collect(Collectors.toList());
    if (hashes.size() > 1) {
      sha1Mismatches++;
    }
    return hashes.get(0); // Return the old one by default.
  }

  @Override
  public HashCodeAndFileType getIfPresent(Path path) {
    List<HashCodeAndFileType> hashes =
        fileHashCacheEngines
            .stream()
            .map(fhc -> fhc.getIfPresent(path))
            .distinct()
            .collect(Collectors.toList());
    if (hashes.size() > 1) {
      sha1Mismatches++;
    }
    return hashes.get(0); // Return the old one by default.
  }

  @Override
  public Long getSizeIfPresent(Path path) {
    List<Long> hashes =
        fileHashCacheEngines
            .stream()
            .map(fhc -> fhc.getSizeIfPresent(path))
            .distinct()
            .collect(Collectors.toList());
    if (hashes.size() > 1) {
      sha1Mismatches++;
    }
    return hashes.get(0); // Return the old one by default.
  }

  @Override
  public long getSize(Path relativePath) throws IOException {
    List<Long> sizes =
        fileHashCacheEngines
            .stream()
            .map(
                fhc -> {
                  try {
                    return fhc.getSize(relativePath);
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                })
            .distinct()
            .collect(Collectors.toList());
    if (sizes.size() > 1) {
      sha1Mismatches++;
    }
    return sizes.get(0); // Return the old one by default.
  }

  @Override
  public void invalidateAll() {
    for (FileHashCacheEngine fileHashCacheEngine : fileHashCacheEngines) {
      fileHashCacheEngine.invalidateAll();
    }
  }

  @Override
  public ConcurrentMap<Path, HashCodeAndFileType> asMap() {
    return fileHashCacheEngines
        .get(0)
        .asMap(); // Just return the old one as this is a support method
  }

  @Override
  public List<AbstractBuckEvent> getStatsEvents() {
    List<AbstractBuckEvent> events =
        fileHashCacheEngines
            .stream()
            .map(FileHashCacheEngine::getStatsEvents)
            .flatMap(List::stream)
            .collect(Collectors.toList());
    if (sha1Mismatches > 0) {
      events.add(
          new ExperimentEvent(
              "file_hash_cache_invalidation", "sha1", "mismatches", sha1Mismatches, null));
    }
    sha1Mismatches = 0;
    return events;
  }
}
