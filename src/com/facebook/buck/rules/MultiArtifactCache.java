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

package com.facebook.buck.rules;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.io.IOException;

/**
 * MultiArtifactCache encapsulates a set of ArtifactCache instances such that fetch() succeeds if
 * any of the ArtifactCaches contain the desired artifact, and store() applies to all
 * ArtifactCaches.
 */
public class MultiArtifactCache implements ArtifactCache {
  private final ImmutableList<ArtifactCache> artifactCaches;
  private final boolean isStoreSupported;

  public MultiArtifactCache(ImmutableList<ArtifactCache> artifactCaches) {
    this.artifactCaches = Preconditions.checkNotNull(artifactCaches);

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
  public CacheResult fetch(RuleKey ruleKey, File output) {
    for (ArtifactCache artifactCache : artifactCaches) {
      CacheResult cacheResult = artifactCache.fetch(ruleKey, output);
      if (cacheResult.isSuccess()) {
        // Success; terminate search for a cached artifact, and propagate artifact to caches
        // earlier in the search order so that subsequent searches terminate earlier.
        for (ArtifactCache priorArtifactCache : artifactCaches) {
          if (priorArtifactCache.equals(artifactCache)) {
            break;
          }
          priorArtifactCache.store(ruleKey, output);
        }
        return cacheResult;
      }
    }
    return CacheResult.MISS;
  }

  /**
   * Store the artifact to all encapsulated ArtifactCaches.
   */
  @Override
  public void store(RuleKey ruleKey, File output) {
    for (ArtifactCache artifactCache : artifactCaches) {
      artifactCache.store(ruleKey, output);
    }
  }

  /** @return {@code true} if there is at least one ArtifactCache that supports storing. */
  @Override
  public boolean isStoreSupported() {
    return isStoreSupported;
  }

  @Override
  public void close() throws IOException {
    // TODO(user): It's possible for this to be interrupted before it gets to call close() on all
    // the individual caches. This is acceptable for now since every ArtifactCache.close() is a
    // no-op in every cache except CassandraArtifactCache.
    for (ArtifactCache artifactCache : artifactCaches) {
      artifactCache.close();
    }
  }
}
