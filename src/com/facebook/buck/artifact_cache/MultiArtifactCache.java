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

import com.facebook.buck.io.BorrowablePath;
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.rules.RuleKey;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;

/**
 * MultiArtifactCache encapsulates a set of ArtifactCache instances such that fetch() succeeds if
 * any of the ArtifactCaches contain the desired artifact, and store() applies to all
 * ArtifactCaches.
 */
public class MultiArtifactCache implements ArtifactCache {

  private final ImmutableList<ArtifactCache> artifactCaches;
  private final ImmutableList<ArtifactCache> writableArtifactCaches;
  private final boolean isStoreSupported;
  private static final Predicate<ArtifactCache> WRITABLE_CACHES_ONLY =
      new Predicate<ArtifactCache>() {
        @Override
        public boolean apply(ArtifactCache input) {
          return input.isStoreSupported();
        }
      };

  public MultiArtifactCache(ImmutableList<ArtifactCache> artifactCaches) {
    this.artifactCaches = artifactCaches;
    this.writableArtifactCaches = ImmutableList.copyOf(
        Iterables.filter(artifactCaches, WRITABLE_CACHES_ONLY));
    this.isStoreSupported = this.writableArtifactCaches.size() > 0;
  }

  /**
   * Fetch the artifact matching ruleKey and store it to output. If any of the encapsulated
   * ArtifactCaches contains the desired artifact, this method succeeds, and it may store the
   * artifact to one or more of the other encapsulated ArtifactCaches as a side effect.
   */
  @Override
  public CacheResult fetch(RuleKey ruleKey, LazyPath output) {
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
          BorrowablePath outputPath;
          // allow borrowing the path if no other caches are expected to use it
          if (priorArtifactCache.equals(artifactCaches.get(artifactCaches.size() - 1))) {
            outputPath = BorrowablePath.borrowablePath(output.getUnchecked());
          } else {
            outputPath = BorrowablePath.notBorrowablePath(output.getUnchecked());
          }
          priorArtifactCache.store(
              ArtifactInfo.builder()
                  .addRuleKeys(ruleKey)
                  .setMetadata(cacheResult.getMetadata())
                  .build(),
              outputPath);
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
      ArtifactInfo info,
      BorrowablePath output) {

    List<ListenableFuture<Void>> storeFutures =
        Lists.newArrayListWithExpectedSize(writableArtifactCaches.size());

    for (ArtifactCache artifactCache : writableArtifactCaches) {
      // allow borrowing the path if no other caches are expected to use it
      if (output.canBorrow() &&
          artifactCache.equals(writableArtifactCaches.get(writableArtifactCaches.size() - 1))) {
        output = BorrowablePath.borrowablePath(output.getPath());
      } else {
        output = BorrowablePath.notBorrowablePath(output.getPath());
      }
      storeFutures.add(artifactCache.store(info, output));
    }

    // Aggregate future to ensure all store operations have completed.
    return Futures.transform(
        Futures.allAsList(storeFutures),
        Functions.<Void>constant(null));
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
