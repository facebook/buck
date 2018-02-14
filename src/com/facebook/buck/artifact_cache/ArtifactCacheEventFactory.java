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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;

public interface ArtifactCacheEventFactory {
  ArtifactCacheEvent.Started newFetchStartedEvent(ImmutableSet<RuleKey> ruleKeys);

  ArtifactCacheEvent.Started newContainsStartedEvent(ImmutableSet<RuleKey> ruleKeys);

  ArtifactCacheEvent.Started newStoreStartedEvent(
      ImmutableSet<RuleKey> ruleKeys, ImmutableMap<String, String> metadata);

  ArtifactCacheEvent.Finished newStoreFinishedEvent(ArtifactCacheEvent.Started started);

  ArtifactCacheEvent.Finished newFetchFinishedEvent(
      ArtifactCacheEvent.Started started, CacheResult cacheResult);

  ArtifactCacheEvent.Finished newContainsFinishedEvent(
      ArtifactCacheEvent.Started started, Map<RuleKey, CacheResult> results);
}
