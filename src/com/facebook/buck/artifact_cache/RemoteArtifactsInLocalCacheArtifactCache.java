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
  private final MultiArtifactCache localCaches;
  private final MultiArtifactCache remoteCaches;

  public RemoteArtifactsInLocalCacheArtifactCache(
      MultiArtifactCache localCaches,
      MultiArtifactCache remoteCaches) {
    this.localCaches = localCaches;
    this.remoteCaches = remoteCaches;

    for (ArtifactCache cache : localCaches.getArtifactCaches()) {
      Preconditions.checkArgument(
          cache instanceof DirArtifactCache ||
              (cache instanceof CacheDecorator &&
                  ((CacheDecorator) cache).getDelegate() instanceof DirArtifactCache),
          "MultiArtifactCache localCaches expected to have only DirArtifactCache");
    }
    for (ArtifactCache cache : remoteCaches.getArtifactCaches()) {
      Preconditions.checkArgument(
          cache instanceof AbstractNetworkCache,
          "MultiArtifactCache remoteCaches expected to have only AbstractNetworkCache");
    }
  }

  @Override
  public CacheResult fetch(RuleKey ruleKey, LazyPath output) {
    CacheResult localResult = localCaches.fetch(ruleKey, output);

    if (localResult.getType() == CacheResultType.ERROR ||
        localResult.getType() == CacheResultType.HIT) {
      return localResult;
    }

    // miss
    CacheResult remoteResult = remoteCaches.fetch(ruleKey, output);
    if (remoteResult.getType().isSuccess()) {
      // remote cache had artifact, let's propagate it down to dir caches.
      localCaches.store(
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
    return remoteCaches.store(info, output);
  }

  @Override
  public boolean isStoreSupported() {
    return remoteCaches.isStoreSupported();
  }

  @Override
  public void close() {
    localCaches.close();
    remoteCaches.close();
  }

  @VisibleForTesting
  MultiArtifactCache getLocalCaches() {
    return localCaches;
  }

  @VisibleForTesting
  MultiArtifactCache getRemoteCaches() {
    return remoteCaches;
  }
}
