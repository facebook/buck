/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.artifact_cache.config.CacheReadMode;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import javax.annotation.Nullable;

public class DummyArtifactCache extends NoopArtifactCache {

  @Nullable public RuleKey storeKey;

  public void reset() {
    storeKey = null;
  }

  @Override
  public ListenableFuture<CacheResult> fetchAsync(RuleKey ruleKey, LazyPath output) {
    return Futures.immediateFuture(
        ruleKey.equals(storeKey)
            ? CacheResult.hit("cache", ArtifactCacheMode.http)
            : CacheResult.miss());
  }

  @Override
  public ListenableFuture<Void> store(ArtifactInfo info, BorrowablePath output) {
    storeKey = Iterables.getFirst(info.getRuleKeys(), null);
    return Futures.immediateFuture(null);
  }

  @Override
  public ListenableFuture<ImmutableMap<RuleKey, CacheResult>> multiContainsAsync(
      ImmutableSet<RuleKey> ruleKeys) {
    RuleKey storedKeyInstance = storeKey;
    return Futures.immediateFuture(
        Maps.toMap(
            ruleKeys,
            ruleKey ->
                ruleKey.equals(storedKeyInstance)
                    ? CacheResult.contains("cache", ArtifactCacheMode.http)
                    : CacheResult.miss()));
  }

  @Override
  public CacheReadMode getCacheReadMode() {
    return CacheReadMode.READWRITE;
  }
}
