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

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.util.Map;
import java.util.Set;

/**
 * Fake implementation of {@link BuildableContext} for testing.
 */
public class FakeBuildableContext implements BuildableContext {

  private final Map<String, String> metadata = Maps.newHashMap();

  private final Set<String> artifacts = Sets.newHashSet();

  @Override
  public void addMetadata(String key, String value) {
    String oldValue = metadata.put(key, value);
    if (oldValue != null) {
      throw new IllegalStateException(String.format(
          "Duplicate values for key %s: old is %s and new is %s.",
          key,
          oldValue,
          value));
    }
  }

  @Override
  public void recordArtifact(String pathToArtifact) {
    artifacts.add(pathToArtifact);
  }
}
