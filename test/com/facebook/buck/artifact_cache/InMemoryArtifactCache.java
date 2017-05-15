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

import com.facebook.buck.io.BorrowablePath;
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.rules.RuleKey;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Map;

public class InMemoryArtifactCache implements ArtifactCache {

  private final Map<RuleKey, Artifact> artifacts = Maps.newConcurrentMap();

  public int getArtifactCount() {
    return artifacts.size();
  }

  public boolean hasArtifact(RuleKey ruleKey) {
    return artifacts.containsKey(ruleKey);
  }

  @Override
  public CacheResult fetch(RuleKey ruleKey, LazyPath output) {
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
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return Futures.immediateFuture(null);
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
