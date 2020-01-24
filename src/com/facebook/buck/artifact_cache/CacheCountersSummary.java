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

package com.facebook.buck.artifact_cache;

import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.log.views.JsonViews;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Utility class to help outputting the information to the machine-readable log. It helps in the
 * serialization & deserialization process.
 */
@BuckStyleValue
@JsonDeserialize(as = ImmutableCacheCountersSummary.class)
public abstract class CacheCountersSummary {

  @JsonView(JsonViews.MachineReadableLog.class)
  public abstract ImmutableMap<ArtifactCacheMode, AtomicInteger> getCacheHitsPerMode();

  @JsonView(JsonViews.MachineReadableLog.class)
  public abstract ImmutableMap<ArtifactCacheMode, AtomicInteger> getCacheErrorsPerMode();

  @JsonView(JsonViews.MachineReadableLog.class)
  public abstract ImmutableMap<ArtifactCacheMode, AtomicLong> getCacheBytesPerMode();

  @JsonView(JsonViews.MachineReadableLog.class)
  public abstract int getTotalCacheHits();

  @JsonView(JsonViews.MachineReadableLog.class)
  public abstract int getTotalCacheErrors();

  @JsonView(JsonViews.MachineReadableLog.class)
  public abstract int getTotalCacheMisses();

  @JsonView(JsonViews.MachineReadableLog.class)
  public abstract int getTotalCacheIgnores();

  @JsonView(JsonViews.MachineReadableLog.class)
  public abstract long getTotalCacheBytes();

  @JsonView(JsonViews.MachineReadableLog.class)
  public abstract int getTotalCacheLocalKeyUnchangedHits();

  @JsonView(JsonViews.MachineReadableLog.class)
  public abstract AtomicInteger getSuccessUploadCount();

  @JsonView(JsonViews.MachineReadableLog.class)
  public abstract AtomicInteger getFailureUploadCount();

  public static CacheCountersSummary of(
      Map<ArtifactCacheMode, ? extends AtomicInteger> cacheHitsPerMode,
      Map<ArtifactCacheMode, ? extends AtomicInteger> cacheErrorsPerMode,
      Map<ArtifactCacheMode, ? extends AtomicLong> cacheBytesPerMode,
      int totalCacheHits,
      int totalCacheErrors,
      int totalCacheMisses,
      int totalCacheIgnores,
      long totalCacheBytes,
      int totalCacheLocalKeyUnchangedHits,
      AtomicInteger successUploadCount,
      AtomicInteger failureUploadCount) {
    return ImmutableCacheCountersSummary.of(
        cacheHitsPerMode,
        cacheErrorsPerMode,
        cacheBytesPerMode,
        totalCacheHits,
        totalCacheErrors,
        totalCacheMisses,
        totalCacheIgnores,
        totalCacheBytes,
        totalCacheLocalKeyUnchangedHits,
        successUploadCount,
        failureUploadCount);
  }
}
