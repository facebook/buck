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

import com.facebook.buck.artifact_cache.config.CacheReadMode;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.util.types.Unit;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Implementation of {@link ArtifactCache} using SQLite.
 *
 * <p>Cache entries are either metadata or content. All metadata contains a mapping to a content
 * entry. Content entries with sufficiently small content will have their artifacts inlined into the
 * database for improved performance.
 */
public class SQLiteArtifactCache implements ArtifactCache {

  private final CacheReadMode cacheMode;

  SQLiteArtifactCache(CacheReadMode cacheMode) {
    this.cacheMode = cacheMode;
  }

  @Override
  public ListenableFuture<CacheResult> fetchAsync(
      @Nullable BuildTarget target, RuleKey ruleKey, LazyPath output) {
    throw new RuntimeException("SQLiteArtifactCache is deprecated and unimplemented");
  }

  @Override
  public void skipPendingAndFutureAsyncFetches() {
    // Async requests are not supported by SQLiteArtifactCache, so do nothing
  }

  @Override
  public ListenableFuture<Unit> store(ArtifactInfo info, BorrowablePath content) {
    throw new RuntimeException("SQLiteArtifactCache is deprecated and unimplemented");
  }

  @Override
  public ListenableFuture<CacheDeleteResult> deleteAsync(List<RuleKey> ruleKeys) {
    throw new RuntimeException("Delete operation is not yet supported");
  }

  @Override
  public CacheReadMode getCacheReadMode() {
    return cacheMode;
  }

  @Override
  public void close() {}
}
