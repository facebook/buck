/*
 * Copyright 2013-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Fake implementation of {@link BuildableContext} for testing.
 */
public class FakeBuildableContext implements BuildableContext {

  private final Map<String, Object> metadata = Maps.newHashMap();

  private final Set<Path> artifacts = Sets.newHashSet();

  @Override
  public void addMetadata(String key, String value) {
    Object oldValue = metadata.put(key, value);
    if (oldValue != null) {
      throw new IllegalStateException(String.format(
          "Duplicate values for key %s: old is %s and new is %s.",
          key,
          oldValue,
          value));
    }
  }

  @Override
  public void addMetadata(String key, Iterable<String> values) {
    metadata.put(key, ImmutableList.copyOf(values));
  }

  @Override
  public void addMetadata(String key, Multimap<String, String> values) {
    metadata.put(key, ImmutableMultimap.copyOf(values));
  }

  @Override
  public void recordArtifact(Path pathToArtifact) {
    artifacts.add(pathToArtifact);
  }

  public ImmutableMap<String, Object> getRecordedMetadata() {
    return ImmutableMap.copyOf(metadata);
  }

  public ImmutableSet<Path> getRecordedArtifacts() {
    return ImmutableSet.copyOf(artifacts);
  }

  public void assertContainsMetadataMapping(String key, String value) {
    assertNotNull(key);
    assertNotNull(value);
    assertTrue(metadata.containsKey(key));
    assertEquals(value, metadata.get(key));
  }
}
