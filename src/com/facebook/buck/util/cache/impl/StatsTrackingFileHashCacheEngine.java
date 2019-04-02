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

import com.facebook.buck.core.io.ArchiveMemberPath;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.FileHashCacheEvent;
import com.facebook.buck.util.cache.DelegatingFileHashCacheEngine;
import com.facebook.buck.util.cache.FileHashCacheEngine;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class StatsTrackingFileHashCacheEngine extends DelegatingFileHashCacheEngine {

  private long cacheRetrievalAggregatedNanoTime = 0;
  private long cacheInvalidationAggregatedNanoTime = 0;
  private long numberOfInvalidations = 0;
  private long numberOfRetrievals = 0;
  private final String subcategory;

  public StatsTrackingFileHashCacheEngine(FileHashCacheEngine delegate, String subcategory) {
    super(delegate);
    this.subcategory = subcategory;
  }

  @Override
  public void invalidate(Path path) {
    long start = System.nanoTime();
    super.invalidate(path);
    cacheInvalidationAggregatedNanoTime += System.nanoTime() - start;
    numberOfInvalidations++;
  }

  @Override
  public void invalidateWithParents(Path path) {
    long start = System.nanoTime();
    super.invalidateWithParents(path);
    cacheInvalidationAggregatedNanoTime += System.nanoTime() - start;
    numberOfInvalidations++;
  }

  @Override
  public HashCode get(Path path) throws IOException {
    long start = System.nanoTime();
    HashCode sha1 = super.get(path);
    cacheRetrievalAggregatedNanoTime += System.nanoTime() - start;
    numberOfRetrievals++;
    return sha1;
  }

  @Override
  public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
    long start = System.nanoTime();
    HashCode sha1 = super.get(archiveMemberPath);
    cacheRetrievalAggregatedNanoTime += System.nanoTime() - start;
    numberOfRetrievals++;
    return sha1;
  }

  @Override
  public List<AbstractBuckEvent> getStatsEvents() {
    ImmutableList.Builder<AbstractBuckEvent> eventsBuilder =
        ImmutableList.<AbstractBuckEvent>builder().addAll(super.getStatsEvents());
    if (numberOfInvalidations > 0) {
      eventsBuilder.add(
          new FileHashCacheEvent(
              subcategory + ".invalidation",
              cacheInvalidationAggregatedNanoTime,
              cacheInvalidationAggregatedNanoTime,
              numberOfInvalidations));
    }
    if (numberOfRetrievals > 0) {
      eventsBuilder.add(
          new FileHashCacheEvent(
              subcategory + ".retrieval",
              cacheRetrievalAggregatedNanoTime,
              cacheRetrievalAggregatedNanoTime,
              numberOfRetrievals));
    }
    cacheInvalidationAggregatedNanoTime = 0;
    cacheRetrievalAggregatedNanoTime = 0;
    numberOfInvalidations = 0;
    numberOfRetrievals = 0;
    return eventsBuilder.build();
  }
}
