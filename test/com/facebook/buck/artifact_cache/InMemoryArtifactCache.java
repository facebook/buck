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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class InMemoryArtifactCache implements ArtifactCache {
  private final Map<RuleKey, Artifact> artifacts = Maps.newConcurrentMap();
  private final ListeningExecutorService service =
      MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());

  public int getArtifactCount() {
    return artifacts.size();
  }

  public boolean hasArtifact(RuleKey ruleKey) {
    return artifacts.containsKey(ruleKey);
  }

  @Override
  public ListenableFuture<CacheResult> fetchAsync(
      BuildTarget target, RuleKey ruleKey, LazyPath output) {
    return service.submit(() -> fetch(ruleKey, output));
  }

  @Override
  public void skipPendingAndFutureAsyncFetches() {
    // Async requests are not supported by InMemoryArtifactCache, so do nothing
  }

  private CacheResult fetch(RuleKey ruleKey, LazyPath output) {
    Artifact artifact = artifacts.get(ruleKey);
    if (artifact == null) {
      return CacheResult.miss();
    }
    try {
      Files.write(output.get(), artifact.data);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return CacheResult.hit(
        "in-memory", ArtifactCacheMode.dir, artifact.metadata, artifact.data.length);
  }

  public void store(ArtifactInfo info, byte[] data) {
    Artifact artifact = new Artifact();
    artifact.metadata = info.getMetadata();
    artifact.data = data;
    for (RuleKey ruleKey : info.getRuleKeys()) {
      artifacts.put(ruleKey, artifact);
    }
  }

  @Override
  public ListenableFuture<Void> store(ArtifactInfo info, BorrowablePath output) {
    try (InputStream inputStream = Files.newInputStream(output.getPath())) {
      store(info, ByteStreams.toByteArray(inputStream));
      if (output.canBorrow()) {
        Files.delete(output.getPath());
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return Futures.immediateFuture(null);
  }

  @Override
  public ListenableFuture<ImmutableMap<RuleKey, CacheResult>> multiContainsAsync(
      ImmutableSet<RuleKey> ruleKeys) {
    return Futures.immediateFuture(
        Maps.toMap(
            ruleKeys,
            ruleKey ->
                artifacts.containsKey(ruleKey)
                    ? CacheResult.contains("in-memory", ArtifactCacheMode.dir)
                    : CacheResult.miss()));
  }

  @Override
  public ListenableFuture<CacheDeleteResult> deleteAsync(List<RuleKey> ruleKeys) {
    ruleKeys.forEach(artifacts::remove);

    ImmutableList<String> cacheNames =
        ImmutableList.of(InMemoryArtifactCache.class.getSimpleName());
    return Futures.immediateFuture(CacheDeleteResult.builder().setCacheNames(cacheNames).build());
  }

  @Override
  public CacheReadMode getCacheReadMode() {
    return CacheReadMode.READWRITE;
  }

  @Override
  public void close() {}

  public boolean isEmpty() {
    return artifacts.isEmpty();
  }

  public static class Artifact {
    public ImmutableMap<String, String> metadata;
    public byte[] data;
  }
}
