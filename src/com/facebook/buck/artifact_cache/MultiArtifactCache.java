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

package com.facebook.buck.artifact_cache;

import com.facebook.buck.io.LazyPath;
import com.facebook.buck.rules.RuleKey;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nullable;

/**
 * MultiArtifactCache encapsulates a set of ArtifactCache instances such that fetch() succeeds if
 * any of the ArtifactCaches contain the desired artifact, and store() applies to all
 * ArtifactCaches.
 */
public class MultiArtifactCache implements ArtifactCache {
  private final ImmutableList<ArtifactCache> artifactCaches;
  private final boolean isStoreSupported;

  public MultiArtifactCache(ImmutableList<ArtifactCache> artifactCaches) {
    this.artifactCaches = artifactCaches;

    boolean isStoreSupported = false;
    for (ArtifactCache artifactCache : artifactCaches) {
      if (artifactCache.isStoreSupported()) {
        isStoreSupported = true;
        break;
      }
    }
    this.isStoreSupported = isStoreSupported;
  }

  /**
   * Fetch the artifact matching ruleKey and store it to output. If any of the encapsulated
   * ArtifactCaches contains the desired artifact, this method succeeds, and it may store the
   * artifact to one or more of the other encapsulated ArtifactCaches as a side effect.
   */
  @Override
  public CacheResult fetch(RuleKey ruleKey, LazyPath output)
      throws InterruptedException {
    CacheResult cacheResult = CacheResult.miss();
    for (ArtifactCache artifactCache : artifactCaches) {
      cacheResult = artifactCache.fetch(ruleKey, output);
      if (cacheResult.getType().isSuccess()) {
        // Success; terminate search for a cached artifact, and propagate artifact to caches
        // earlier in the search order so that subsequent searches terminate earlier.
        for (ArtifactCache priorArtifactCache : artifactCaches) {
          if (priorArtifactCache.equals(artifactCache)) {
            break;
          }
          // since cache fetch finished, it should be fine to get the path
          Path outputPath = output.getUnchecked();
          priorArtifactCache.store(ImmutableSet.of(ruleKey), cacheResult.getMetadata(), outputPath);
        }
        return cacheResult;
      }
    }
    return cacheResult;
  }

  /**
   * Store the artifact to all encapsulated ArtifactCaches.
   */
  @Override
  public ListenableFuture<Void> store(
      ImmutableSet<RuleKey> ruleKeys,
      ImmutableMap<String, String> metadata,
      Path output)
      throws InterruptedException {
    List<ListenableFuture<Void>> storeFutures =
        Lists.newArrayListWithExpectedSize(artifactCaches.size());

    for (ArtifactCache artifactCache : artifactCaches) {
      storeFutures.add(artifactCache.store(ruleKeys, metadata, output));
    }

    // Aggregate future to ensure all store operations have completed.
    return Futures.transformAsync(
        Futures.allAsList(storeFutures),
        new AsyncFunction<List<Void>, Void>() {
          @Override
          @Nullable
          public ListenableFuture<Void> apply(List<Void> input) throws Exception {
            return null;
          }
        });
  }

  /** @return {@code true} if there is at least one ArtifactCache that supports storing. */
  @Override
  public boolean isStoreSupported() {
    return isStoreSupported;
  }

  @Override
  public void close() {
    Optional<RuntimeException> throwable = Optional.absent();
    for (ArtifactCache artifactCache : artifactCaches) {
      try {
        artifactCache.close();
      } catch (RuntimeException e) {
        throwable = Optional.of(e);
      }
    }
    if (throwable.isPresent()) {
      throw throwable.get();
    }
  }
}
