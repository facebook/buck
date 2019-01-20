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

package com.facebook.buck.core.build.buildable.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Fake implementation of {@link com.facebook.buck.core.build.buildable.context.BuildableContext}
 * for testing.
 */
public class FakeBuildableContext implements BuildableContext {

  private final Map<String, Object> metadata = new HashMap<>();

  private final Set<Path> artifacts = new HashSet<>();

  @Override
  public void recordArtifact(Path pathToArtifact) {
    artifacts.add(pathToArtifact);
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
