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

package com.facebook.buck.artifact_cache.config;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.nio.file.Path;
import java.util.Optional;

@BuckStyleValue
public abstract class DirCacheEntry {
  public abstract Optional<String> getName();

  public abstract Path getCacheDir();

  public abstract Optional<Long> getMaxSizeBytes();

  public abstract CacheReadMode getCacheReadMode();

  public static DirCacheEntry of(
      Path cacheDir, Optional<Long> maxSizeBytes, CacheReadMode cacheReadMode) {
    return of(Optional.empty(), cacheDir, maxSizeBytes, cacheReadMode);
  }

  public static DirCacheEntry of(
      Optional<String> name,
      Path cacheDir,
      Optional<Long> maxSizeBytes,
      CacheReadMode cacheReadMode) {
    return ImmutableDirCacheEntry.of(name, cacheDir, maxSizeBytes, cacheReadMode);
  }

  public DirCacheEntry withCacheReadMode(CacheReadMode cacheReadMode) {
    if (getCacheReadMode().equals(cacheReadMode)) {
      return this;
    }
    return ImmutableDirCacheEntry.of(getName(), getCacheDir(), getMaxSizeBytes(), cacheReadMode);
  }
}
