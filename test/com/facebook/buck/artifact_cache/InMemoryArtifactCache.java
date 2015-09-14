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

import com.facebook.buck.rules.RuleKey;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.io.ByteStreams;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class InMemoryArtifactCache implements ArtifactCache {

  private final Map<RuleKey, Artifact> artifacts = Maps.newConcurrentMap();

  public boolean hasArtifact(RuleKey ruleKey) {
    return artifacts.containsKey(ruleKey);
  }

  public void putArtifact(RuleKey ruleKey, Artifact artifact) {
    artifacts.put(ruleKey, artifact);
  }

  @Override
  public CacheResult fetch(RuleKey ruleKey, Path output) {
    Artifact artifact = artifacts.get(ruleKey);
    if (artifact == null) {
      return CacheResult.miss();
    }
    try {
      Files.write(output, artifact.data);
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
    return CacheResult.hit("in-memory", artifact.metadata);
  }

  public void store(
      ImmutableSet<RuleKey> ruleKeys,
      ImmutableMap<String, String> metadata,
      byte[] data) {
    Artifact artifact = new Artifact();
    artifact.metadata = metadata;
    artifact.data = data;
    for (RuleKey ruleKey : ruleKeys) {
      artifacts.put(ruleKey, artifact);
    }
  }

  @Override
  public void store(
      ImmutableSet<RuleKey> ruleKeys,
      ImmutableMap<String, String> metadata,
      Path output) {
    try (InputStream inputStream = Files.newInputStream(output)) {
      store(ruleKeys, metadata, ByteStreams.toByteArray(inputStream));
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public boolean isStoreSupported() {
    return true;
  }

  @Override
  public void close() {
  }

  public class Artifact {
    public ImmutableMap<String, String> metadata;
    public byte[] data;
  }

}
