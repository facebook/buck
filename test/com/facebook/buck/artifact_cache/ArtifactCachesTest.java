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

import static org.junit.Assert.assertThat;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.util.concurrent.MoreExecutors;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.Optional;

public class ArtifactCachesTest {
  @Test
  public void testCreateHttpCacheOnly() throws Exception {
    ArtifactCacheBuckConfig cacheConfig = ArtifactCacheBuckConfigTest.createFromText(
        "[cache]",
        "mode = http");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    ArtifactCache artifactCache = ArtifactCaches.newInstance(
        cacheConfig,
        buckEventBus,
        projectFilesystem,
        Optional.empty(),
        MoreExecutors.newDirectExecutorService());
    assertThat(stripDecorators(artifactCache), Matchers.instanceOf(HttpArtifactCache.class));
  }

  @Test
  public void testCreateDirCacheOnly() throws Exception {
    ArtifactCacheBuckConfig cacheConfig = ArtifactCacheBuckConfigTest.createFromText(
        "[cache]",
        "mode = dir");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    ArtifactCache artifactCache = ArtifactCaches.newInstance(
        cacheConfig,
        buckEventBus,
        projectFilesystem,
        Optional.empty(),
        MoreExecutors.newDirectExecutorService());

    assertThat(stripDecorators(artifactCache), Matchers.instanceOf(DirArtifactCache.class));
  }

  @Test
  public void testCreateBoth() throws Exception {
    ArtifactCacheBuckConfig cacheConfig = ArtifactCacheBuckConfigTest.createFromText(
        "[cache]",
        "mode = dir, http");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    ArtifactCache artifactCache = ArtifactCaches.newInstance(
        cacheConfig,
        buckEventBus,
        projectFilesystem,
        Optional.empty(),
        MoreExecutors.newDirectExecutorService());
    assertThat(stripDecorators(artifactCache), Matchers.instanceOf(MultiArtifactCache.class));
  }

  @Test
  public void testCreateDirCacheOnlyWhenOnBlacklistedWifi() throws Exception {
    ArtifactCacheBuckConfig cacheConfig = ArtifactCacheBuckConfigTest.createFromText(
        "[cache]",
        "mode = dir, http",
        "blacklisted_wifi_ssids = weevil, evilwifi");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    ArtifactCache artifactCache = ArtifactCaches.newInstance(
        cacheConfig,
        buckEventBus,
        projectFilesystem,
        Optional.of("evilwifi"),
        MoreExecutors.newDirectExecutorService());
    assertThat(stripDecorators(artifactCache), Matchers.instanceOf(DirArtifactCache.class));
  }

  private static ArtifactCache stripDecorators(ArtifactCache artifactCache) {
    if (artifactCache instanceof LoggingArtifactCacheDecorator) {
      LoggingArtifactCacheDecorator cacheDecorator = (LoggingArtifactCacheDecorator) artifactCache;
      return stripDecorators(cacheDecorator.getDelegate());
    }
    if (artifactCache instanceof TwoLevelArtifactCacheDecorator) {
      TwoLevelArtifactCacheDecorator cacheDecorator =
          (TwoLevelArtifactCacheDecorator) artifactCache;
      return stripDecorators(cacheDecorator.getDelegate());
    }
    return artifactCache;
  }
}
