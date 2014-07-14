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
package com.facebook.buck.cli;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.ArtifactCacheConnectEvent;
import com.facebook.buck.rules.LoggingArtifactCacheDecorator;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.util.concurrent.MoreExecutors;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * An implementation of {@link ArtifactCacheFactory} that returns instances of {@link ArtifactCache}
 * decorated by {@link LoggingArtifactCacheDecorator}.
 */
public class LoggingArtifactCacheFactory implements ArtifactCacheFactory {

  private final BuckEventBus buckEventBus;
  private final List<ArtifactCache> createdArtifactCaches;
  private final ExecutionEnvironment executionEnvironment;

  public LoggingArtifactCacheFactory(
      ExecutionEnvironment executionEnvironment,
      BuckEventBus buckEventBus) {
    this.executionEnvironment = Preconditions.checkNotNull(executionEnvironment);
    this.buckEventBus = Preconditions.checkNotNull(buckEventBus);
    this.createdArtifactCaches = Lists.newArrayList();
  }

  @Override
  public ArtifactCache newInstance(AbstractCommandOptions options)
      throws InterruptedException {
    if (options.isNoCache()) {
      return new NoopArtifactCache();
    } else {
      buckEventBus.post(ArtifactCacheConnectEvent.started());
      ArtifactCache artifactCache = new LoggingArtifactCacheDecorator(buckEventBus)
          .decorate(options.getBuckConfig().createArtifactCache(
                  executionEnvironment.getWifiSsid(),
                  buckEventBus));
      buckEventBus.post(ArtifactCacheConnectEvent.finished());
      createdArtifactCaches.add(artifactCache);
      return artifactCache;
    }
  }

  @Override
  public void closeCreatedArtifactCaches(int timeoutInSeconds) throws InterruptedException {
    ExecutorService cachesTerminationService =
        MoreExecutors.newSingleThreadExecutor("close_artifact_caches");
    for (final ArtifactCache cache : createdArtifactCaches) {
      cachesTerminationService.submit(new Callable<Void>() {
        @Override
        public Void call() throws IOException {
          cache.close();
          return null;
        }
      });
    }

    cachesTerminationService.shutdown();
    cachesTerminationService.awaitTermination(timeoutInSeconds, TimeUnit.SECONDS);
  }
}
