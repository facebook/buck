/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.BorrowablePath;
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.RuleKey;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.List;

public class RetryingNetworkCache implements ArtifactCache {

  private static final Logger LOG = Logger.get(RetryingNetworkCache.class);

  private final ArtifactCache delegate;
  private final int maxFetchRetries;
  private final BuckEventBus buckEventBus;
  private final ArtifactCacheMode cacheMode;

  public RetryingNetworkCache(
      ArtifactCacheMode cacheMode,
      ArtifactCache delegate,
      int maxFetchRetries,
      BuckEventBus buckEventBus) {
    Preconditions.checkArgument(maxFetchRetries > 0);

    this.cacheMode = cacheMode;
    this.delegate = delegate;
    this.maxFetchRetries = maxFetchRetries;
    this.buckEventBus = buckEventBus;
  }

  @Override
  public CacheResult fetch(RuleKey ruleKey, LazyPath output) {
    List<String> allCacheErrors = new ArrayList<>();
    CacheResult lastCacheResult = null;
    for (int retryCount = 0; retryCount < maxFetchRetries; retryCount++) {
      CacheResult cacheResult = delegate.fetch(ruleKey, output);
      if (cacheResult.getType() != CacheResultType.ERROR) {
        return cacheResult;
      }
      cacheResult.cacheError().ifPresent(allCacheErrors::add);
      LOG.debug(
          "Failed to fetch %s after %d/%d attempts, exception: %s",
          ruleKey, retryCount + 1, maxFetchRetries, cacheResult);
      lastCacheResult = cacheResult;
    }
    String msg = String.join("\n", allCacheErrors);
    buckEventBus.post(
        ConsoleEvent.warning(
            "Failed to fetch %s over %s after %d attempts.",
            ruleKey, cacheMode.name(), maxFetchRetries));
    Preconditions.checkNotNull(
        lastCacheResult,
        "One error should have happened, therefore lastCacheResult should be non null.");
    return CacheResult.builder().from(lastCacheResult).setCacheError(msg).build();
  }

  @VisibleForTesting
  protected ArtifactCache getDelegate() {
    return delegate;
  }

  @Override
  public ListenableFuture<Void> store(ArtifactInfo info, BorrowablePath output) {
    return delegate.store(info, output);
  }

  @Override
  public CacheReadMode getCacheReadMode() {
    return delegate.getCacheReadMode();
  }

  @Override
  public void close() {
    delegate.close();
  }
}
