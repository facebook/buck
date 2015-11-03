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

import com.facebook.buck.event.EventKey;
import com.facebook.buck.rules.RuleKey;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

public class DirArtifactCacheEvent {
  public static final ArtifactCacheEvent.CacheMode CACHE_MODE =
      ArtifactCacheEvent.CacheMode.dir;

  private DirArtifactCacheEvent() {}

  public static class DirArtifactCacheEventFactory implements ArtifactCacheEventFactory
  {
    @Override
    public ArtifactCacheEvent.Started newFetchStartedEvent(ImmutableSet<RuleKey> ruleKeys) {
      return new Started(ArtifactCacheEvent.Operation.FETCH, ruleKeys);
    }

    @Override
    public ArtifactCacheEvent.AbstractStarted newStoreStartedEvent(
        ImmutableSet<RuleKey> ruleKeys) {
      return new Started(
          ArtifactCacheEvent.Operation.STORE,
          ruleKeys);
    }

    @Override
    public ArtifactCacheEvent.Finished newStoreFinishedEvent(
        ArtifactCacheEvent.AbstractStarted started) {
      return newFinishedEvent(started, Optional.<CacheResult>absent());
    }

    @Override
    public ArtifactCacheEvent.Finished newFetchFinishedEvent(
        ArtifactCacheEvent.AbstractStarted started, CacheResult cacheResult) {
      return newFinishedEvent(started, Optional.of(cacheResult));
    }

    public Finished newFinishedEvent(
        ArtifactCacheEvent.AbstractStarted started, Optional<CacheResult> cacheResult) {
      return new Finished(
          started.getEventKey(),
          CACHE_MODE,
          started.getOperation(),
          started.getRuleKeys(),
          cacheResult
      );
    }
  }

  public static class Started extends ArtifactCacheEvent.Started {

    public Started(
        ArtifactCacheEvent.Operation operation, ImmutableSet<RuleKey> ruleKeys) {
      super(
          EventKey.unique(),
          CACHE_MODE,
          operation,
          ruleKeys);
    }

    @Override
    public String getEventName() {
      return "DirArtifactCacheEvent.Started";
    }
  }

  public static class Finished extends ArtifactCacheEvent.Finished {
    protected Finished(
        EventKey eventKey,
        ArtifactCacheEvent.CacheMode cacheMode,
        Operation operation,
        ImmutableSet<RuleKey> ruleKeys,
        Optional<CacheResult> cacheResult) {
      super(eventKey, cacheMode, operation, ruleKeys, cacheResult);
    }

    @Override
    public String getEventName() {
      return "DirArtifactCacheEvent.Finished";
    }
  }

}
