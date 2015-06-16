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

package com.facebook.buck.rules;

import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.io.ByteStreams;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Map;

public class InMemoryArtifactCache implements ArtifactCache {

  private final Map<RuleKey, byte[]> artifacts = Maps.newConcurrentMap();

  public Optional<byte[]> getArtifact(RuleKey ruleKey) {
    return Optional.fromNullable(artifacts.get(ruleKey));
  }

  public void putArtifact(RuleKey ruleKey, byte[] bytes) {
    artifacts.put(ruleKey, bytes);
  }

  @Override
  public CacheResult fetch(RuleKey ruleKey, File output) {
    byte[] bytes = artifacts.get(ruleKey);
    if (bytes == null) {
      return CacheResult.miss();
    }
    try {
      Files.write(output.toPath(), bytes);
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
    return CacheResult.hit("in-memory");
  }

  @Override
  public void store(ImmutableSet<RuleKey> ruleKeys, File output) {
    for (RuleKey ruleKey : ruleKeys) {
      try (InputStream inputStream = Files.newInputStream(output.toPath())) {
        artifacts.put(ruleKey, ByteStreams.toByteArray(inputStream));
      } catch (IOException e) {
        throw Throwables.propagate(e);
      }
    }
  }

  @Override
  public boolean isStoreSupported() {
    return true;
  }

  @Override
  public void close() throws IOException {
  }

}
