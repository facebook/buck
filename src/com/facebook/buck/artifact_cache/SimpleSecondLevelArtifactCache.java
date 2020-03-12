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
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.counters.CounterRegistry;
import com.facebook.buck.counters.SamplingCounter;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Second-level artifact cache that passes everything through to a given ArtifactCache with a SHA-1
 * content key.
 */
public class SimpleSecondLevelArtifactCache implements SecondLevelArtifactCache {
  private final ArtifactCache delegate;
  private final ProjectFilesystem projectFilesystem;
  private final SamplingCounter secondLevelHashComputationTimeMs;

  SimpleSecondLevelArtifactCache(
      ArtifactCache delegate, ProjectFilesystem projectFilesystem, BuckEventBus buckEventBus) {
    this.projectFilesystem = projectFilesystem;
    this.delegate = delegate;

    secondLevelHashComputationTimeMs =
        new SamplingCounter(
            TwoLevelArtifactCacheDecorator.COUNTER_CATEGORY,
            "second_level_hash_computation_time_ms",
            ImmutableMap.of());
    buckEventBus.post(
        new CounterRegistry.AsyncCounterRegistrationEvent(
            ImmutableList.of(secondLevelHashComputationTimeMs)));
  }

  @Override
  public ListenableFuture<CacheResult> fetchAsync(
      @Nullable BuildTarget target, String contentKey, LazyPath output) {
    return delegate.fetchAsync(target, new RuleKey(contentKey), output);
  }

  @Override
  public ListenableFuture<String> storeAsync(ArtifactInfo info, BorrowablePath output) {
    // Assume our info has no RuleKey yet since we want to contain the content key generation to
    // this second level cache.

    String contentKey;
    try {
      contentKey = computeSha1(output);
    } catch (IOException e) {
      throw new RuntimeException("Cannot compute SHA1 of " + output.getPath());
    }

    return Futures.transform(
        delegate.store(
            ArtifactInfo.builder()
                .addRuleKeys(new RuleKey(contentKey))
                .setBuildTarget(info.getBuildTarget())
                .setBuildTimeMs(info.getBuildTimeMs())
                .build(),
            output),
        __ -> contentKey,
        MoreExecutors.directExecutor());
  }

  @Nonnull
  private String computeSha1(BorrowablePath output) throws IOException {
    long hashComputationStart = System.currentTimeMillis();
    String hashCode = projectFilesystem.computeSha1(output.getPath()) + "2c00";
    long hashComputationEnd = System.currentTimeMillis();
    secondLevelHashComputationTimeMs.addSample(hashComputationEnd - hashComputationStart);
    return hashCode;
  }

  @Override
  public void close() {
    delegate.close();
  }
}
