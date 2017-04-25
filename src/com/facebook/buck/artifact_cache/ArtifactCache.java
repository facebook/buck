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
import com.google.common.util.concurrent.ListenableFuture;

public interface ArtifactCache extends AutoCloseable {
  /**
   * Fetch a cached artifact, keyed by ruleKey, save the artifact to path specified by output, and
   * return true on success.
   *
   * @param ruleKey cache fetch key
   * @param output Path to store artifact to. Path should not be accessed unless store operation is
   *     guaranteed by the cache, to avoid potential extra disk I/O.
   * @return whether it was a {@link CacheResultType#MISS} (indicating a failure) or some type of
   *     hit.
   */
  CacheResult fetch(RuleKey ruleKey, LazyPath output);

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
   * This method must return the same value over the lifetime of this object.
   *
   * @return whether this{@link ArtifactCache} supports storing artifacts.
   */
  CacheReadMode getCacheReadMode();

  @Override
  void close();
}
