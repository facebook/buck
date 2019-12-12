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

package com.facebook.buck.core.build.engine.cache.manager;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.util.concurrent.FakeWeightedListeningExecutorService;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;

public class BuildCacheArtifactFetcherTest {
  private static final RuleKey RULE_KEY = new RuleKey(HashCode.fromLong(42));
  private static final BuildCacheArtifactFetcher FAKE_FETCHER =
      new BuildCacheArtifactFetcher(
          null,
          null,
          new FakeWeightedListeningExecutorService(MoreExecutors.newDirectExecutorService()),
          null,
          null,
          null,
          null);

  @Test
  public void testConvertErrorToSoftErrorListenableFutureCacheResultError() throws Exception {
    CacheResult expected = CacheResult.error("test", ArtifactCacheMode.unknown, "Cache poisoning");
    ListenableFuture<CacheResult> cacheResultListenableFuture =
        FAKE_FETCHER.convertErrorToSoftError(Futures.immediateFuture(expected), RULE_KEY);
    CacheResult actual = Futures.getUnchecked(cacheResultListenableFuture);
    Assert.assertEquals(expected, actual);
  }

  @Test
  public void testConvertErrorToSoftErrorListenableFutureHasIOException() throws Exception {
    ListenableFuture<CacheResult> cacheResultListenableFuture =
        FAKE_FETCHER.convertErrorToSoftError(
            Futures.immediateFailedFuture(new IOException()), RULE_KEY);
    CacheResult result = Futures.getUnchecked(cacheResultListenableFuture);
    Assert.assertEquals(result.getType(), CacheResultType.SOFT_ERROR);
  }

  @Test
  public void testConvertErrorToSoftErrorListenableFutureCacheHit() throws Exception {
    CacheResult expected = CacheResult.hit("test", ArtifactCacheMode.unknown);
    ListenableFuture<CacheResult> cacheResultListenableFuture =
        FAKE_FETCHER.convertErrorToSoftError(Futures.immediateFuture(expected), RULE_KEY);
    CacheResult actual = Futures.getUnchecked(cacheResultListenableFuture);
    Assert.assertEquals(expected, actual);
  }
}
