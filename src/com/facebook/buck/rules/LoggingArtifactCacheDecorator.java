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

import com.facebook.buck.event.BuckEventBus;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Decorator for wrapping a {@link ArtifactCache} to log a {@link ArtifactCacheEvent} for the start
 * and finish of each event.
 */
public class LoggingArtifactCacheDecorator {
  private final BuckEventBus eventBus;

  public LoggingArtifactCacheDecorator(BuckEventBus eventBus) {
    this.eventBus = eventBus;
  }

  public final ArtifactCache decorate(final ArtifactCache delegate) {
    return new ArtifactCache() {
      @Override
      public CacheResult fetch(RuleKey ruleKey, Path output)
          throws InterruptedException {
        ArtifactCacheEvent.Started started = ArtifactCacheEvent.started(
            ArtifactCacheEvent.Operation.FETCH,
            ImmutableSet.of(ruleKey));
        eventBus.post(started);
        CacheResult fetchResult = delegate.fetch(ruleKey, output);
        eventBus.post(ArtifactCacheEvent.finished(
                started,
                fetchResult));
        return fetchResult;
      }

      @Override
      public void store(
          ImmutableSet<RuleKey> ruleKeys,
          ImmutableMap<String, String> metadata,
          Path output)
          throws InterruptedException {
        ArtifactCacheEvent.Started started = ArtifactCacheEvent.started(
            ArtifactCacheEvent.Operation.STORE,
            ruleKeys);
        eventBus.post(started);
        delegate.store(ruleKeys, metadata, output);
        eventBus.post(ArtifactCacheEvent.finished(started));
      }

      @Override
      public boolean isStoreSupported() {
        return delegate.isStoreSupported();
      }

      @Override
      public void close() throws IOException {
        delegate.close();
      }
    };
  }
}
