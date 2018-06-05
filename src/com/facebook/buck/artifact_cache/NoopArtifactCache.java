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

import com.facebook.buck.artifact_cache.config.CacheReadMode;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;
import javax.annotation.Nullable;

public class NoopArtifactCache implements ArtifactCache {
  @Override
  public ListenableFuture<CacheResult> fetchAsync(
      @Nullable BuildTarget target, RuleKey ruleKey, LazyPath output) {
    // Do nothing.
    return Futures.immediateFuture(CacheResult.miss());
  }

  @Override
  public void skipPendingAndFutureAsyncFetches() {
    // Do nothing.
  }

  @Override
  public ListenableFuture<Void> store(ArtifactInfo info, BorrowablePath output) {
    return Futures.immediateFuture(null);
  }

  @Override
  public ListenableFuture<Void> store(ImmutableList<Pair<ArtifactInfo, BorrowablePath>> artifacts) {
    return Futures.immediateFuture(null);
  }

  @Override
  public ListenableFuture<ImmutableMap<RuleKey, CacheResult>> multiContainsAsync(
      ImmutableSet<RuleKey> ruleKeys) {
    return Futures.immediateFuture(Maps.toMap(ruleKeys, k -> CacheResult.miss()));
  }

  @Override
  public ListenableFuture<CacheDeleteResult> deleteAsync(List<RuleKey> ruleKeys) {
    ImmutableList<String> cacheNames = ImmutableList.of(NoopArtifactCache.class.getSimpleName());
    return Futures.immediateFuture(CacheDeleteResult.builder().setCacheNames(cacheNames).build());
  }

  @Override
  public CacheReadMode getCacheReadMode() {
    return CacheReadMode.READONLY;
  }

  @Override
  public void close() {
    // Nothing to complete - do nothing.
  }

  /** Factory class for NoopArtifactCache. */
  public static class NoopArtifactCacheFactory implements ArtifactCacheFactory {

    @Override
    public ArtifactCache newInstance() {
      return new NoopArtifactCache();
    }

    @Override
    public ArtifactCache newInstance(
        boolean distributedBuildModeEnabled, boolean isDownloadHeavyBuild) {
      return new NoopArtifactCache();
    }

    @Override
    public ArtifactCache remoteOnlyInstance(
        boolean distributedBuildModeEnabled, boolean isDownloadHeavyBuild) {
      return new NoopArtifactCache();
    }

    @Override
    public ArtifactCache localOnlyInstance(
        boolean distributedBuildModeEnabled, boolean isDownloadHeavyBuild) {
      return new NoopArtifactCache();
    }

    @Override
    public ArtifactCacheFactory cloneWith(BuckConfig newConfig) {
      return new NoopArtifactCacheFactory();
    }
  }
}
