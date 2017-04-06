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

package com.facebook.buck.artifact_cache;

import com.facebook.buck.io.BorrowablePath;
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.rules.RuleKey;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * A cache that wraps dir caches and remote caches. It allows to store only into remote cache,
 * and internally it stores artifacts fetched from remote cache into local cache. Thus, it never
 * stores locally built artifacts into local cache.
 */
public class RemoteArtifactsInLocalCacheArtifactCache implements ArtifactCache {
  private final ArtifactCache localCache;
  private final ArtifactCache remoteCache;

  public RemoteArtifactsInLocalCacheArtifactCache(
      ArtifactCache localCache,
      ArtifactCache remoteCache) {
    this.localCache = localCache;
    this.remoteCache = remoteCache;

    Preconditions.checkState(
        localCache.getCacheReadMode().isWritable(),
        "Local cache backing remote cache is read-only.");
  }

  @Override
  public CacheResult fetch(RuleKey ruleKey, LazyPath output) {
    CacheResult localResult = localCache.fetch(ruleKey, output);

    if (localResult.getType() == CacheResultType.ERROR ||
        localResult.getType() == CacheResultType.HIT) {
      return localResult;
    }

    // miss
    CacheResult remoteResult = remoteCache.fetch(ruleKey, output);
    if (remoteResult.getType().isSuccess()) {
      // remote cache had artifact, let's propagate it down to dir caches.
      localCache.store(
          ArtifactInfo.builder()
              .addRuleKeys(ruleKey)
              .setMetadata(remoteResult.getMetadata())
              .build(),
          BorrowablePath.notBorrowablePath(output.getUnchecked()));
    }
    return remoteResult;
  }

  @Override
  public ListenableFuture<Void> store(ArtifactInfo info, BorrowablePath output) {
    return remoteCache.store(info, output);
  }

  @Override
  public CacheReadMode getCacheReadMode() {
    return remoteCache.getCacheReadMode();
  }

  @Override
  public void close() {
    localCache.close();
    remoteCache.close();
  }

  @VisibleForTesting
  ArtifactCache getLocalCache() {
    return localCache;
  }

  @VisibleForTesting
  ArtifactCache getRemoteCache() {
    return remoteCache;
  }
}
