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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.List;
import javax.annotation.Nullable;

public interface ArtifactCache extends AutoCloseable {
  /**
   * Fetch a cached artifact, keyed by ruleKey, save the artifact to path specified by output, and
   * return true on success.
   *
   * @param target rule for which this is an artifact
   * @param ruleKey cache fetch key
   * @param output Path to store artifact to. Path should not be accessed unless store operation is
   *     guaranteed by the cache, to avoid potential extra disk I/O.
   * @return whether it was a {@link CacheResultType#MISS} (indicating a failure) or some type of
   *     hit.
   */
  ListenableFuture<CacheResult> fetchAsync(
      @Nullable BuildTarget target, RuleKey ruleKey, LazyPath output);

  /** All pending (and future) async fetches will be immediately marked as skipped. */
  void skipPendingAndFutureAsyncFetches();

  /**
   * Store the artifact at path specified by output to cache, such that it can later be fetched
   * using ruleKey as the lookup key. If any internal errors occur, fail silently and continue
   * execution. Store may be performed synchronously or asynchronously.
   *
   * <p>This is a noop if {@link #getCacheReadMode()}} returns {@code READONLY}.
   *
   * @param info information to store with the artifact
   * @param output path to read artifact from. If its borrowable, you may freely move the file into
   *     cache without obtaining a copy of the file.
   * @return {@link ListenableFuture} that completes once the store has finished.
   */
  ListenableFuture<Void> store(ArtifactInfo info, BorrowablePath output);

  /**
   * Store the list of artifacts at path specified by output to cache in passed order, such that it
   * can later be fetched using ruleKey as the lookup key. If any internal errors occur, fail
   * silently and continue execution. Store may be performed synchronously or asynchronously.
   *
   * <p>This is a noop if {@link #getCacheReadMode()}} returns {@code READONLY}.
   *
   * @param artifacts list of artifact info and path to be uploaded to the cache in given order.
   * @return {@link ListenableFuture} that completes once the store has finished.
   */
  default ListenableFuture<Void> store(
      ImmutableList<Pair<ArtifactInfo, BorrowablePath>> artifacts) {
    if (artifacts.isEmpty()) {
      return Futures.immediateFuture(null);
    }

    Pair<ArtifactInfo, BorrowablePath> first = artifacts.get(0);
    ImmutableList<Pair<ArtifactInfo, BorrowablePath>> rest = artifacts.subList(1, artifacts.size());

    return Futures.transformAsync(
        this.store(first.getFirst(), first.getSecond()),
        input -> this.store(rest),
        MoreExecutors.directExecutor());
  }

  /**
   * Check if the cache contains the given artifacts, keyed by ruleKeys, without fetching them, and
   * return a map of results wrapped in a {@link ListenableFuture}. This is supposed to be fast, but
   * best-effort, meaning that there will be false-positives.
   *
   * @param ruleKeys Set of cache fetch keys.
   * @return map of keys to {@link CacheResult} which can be a {@link CacheResultType#MISS} / {@link
   *     CacheResultType#ERROR} (indicating a failure) or {@link CacheResultType#CONTAINS}.
   */
  ListenableFuture<ImmutableMap<RuleKey, CacheResult>> multiContainsAsync(
      ImmutableSet<RuleKey> ruleKeys);

  ListenableFuture<CacheDeleteResult> deleteAsync(List<RuleKey> ruleKeys);

  /**
   * This method must return the same value over the lifetime of this object.
   *
   * @return whether this{@link ArtifactCache} supports storing artifacts.
   */
  CacheReadMode getCacheReadMode();

  @Override
  void close();
}
