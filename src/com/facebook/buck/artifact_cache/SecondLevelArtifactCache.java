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

package com.facebook.buck.artifact_cache;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.google.common.util.concurrent.ListenableFuture;
import javax.annotation.Nullable;

/**
 * An ArtifactCache-lite meant to be used by the TwoLevelArtifactCacheDecorator for second-level
 * artifact caching.
 */
public interface SecondLevelArtifactCache {
  /**
   * Fetch a cached artifact, save the artifact to path specified by output, and return true on
   * success.
   *
   * @param target rule for which this is an artifact
   * @param contentKey cache fetch key
   * @param output Path to store artifact to. Path should not be accessed unless store operation is
   *     guaranteed by the cache, to avoid potential extra disk I/O.
   * @return whether it was a {@link CacheResultType#MISS} (indicating a failure) or some type of
   *     hit.
   */
  ListenableFuture<CacheResult> fetchAsync(
      @Nullable BuildTarget target, String contentKey, LazyPath output);

  /**
   * Store the artifact at path specified by output to cache, such that it can later be fetched
   * using contentKey as the lookup key. If any internal errors occur, fail silently and continue
   * execution. Store may be performed synchronously or asynchronously.
   *
   * @param info information to store with the artifact
   * @param output path to read artifact from. If its borrowable, you may freely move the file into
   *     cache without obtaining a copy of the file.
   * @return some content-key to be used for fetching.
   */
  ListenableFuture<String> storeAsync(ArtifactInfo info, BorrowablePath output);
}
