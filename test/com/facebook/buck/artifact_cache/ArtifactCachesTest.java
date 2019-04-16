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

import com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig;
import com.facebook.buck.artifact_cache.config.CacheReadMode;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.TargetConfigurationSerializerForTests;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetFactory;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetFactory;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager;
import com.facebook.buck.support.bgtasks.TaskManagerCommandScope;
import com.facebook.buck.support.bgtasks.TestBackgroundTaskManager;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ArtifactCachesTest {
  @Rule public TemporaryPaths tempDir = new TemporaryPaths();

  private static final BuildId BUILD_ID = new BuildId("test");

  private BackgroundTaskManager bgTaskManager;
  private TaskManagerCommandScope managerScope;
  private UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory;

  @Before
  public void setUp() {
    bgTaskManager = new TestBackgroundTaskManager();
    managerScope = bgTaskManager.getNewScope(BUILD_ID);
    unconfiguredBuildTargetFactory = new ParsingUnconfiguredBuildTargetFactory();
  }

  @After
  public void tearDown() throws InterruptedException {
    bgTaskManager.shutdown(1, TimeUnit.SECONDS);
  }

  @Test
  public void testCreateHttpCacheOnly() throws Exception {
    ArtifactCacheBuckConfig cacheConfig =
        ArtifactCacheBuckConfigTest.createFromText("[cache]", "mode = http");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();
    ArtifactCache artifactCache =
        newArtifactCache(cacheConfig, projectFilesystem, buckEventBus, Optional.empty())
            .newInstance();
    assertThat(stripDecorators(artifactCache), Matchers.instanceOf(HttpArtifactCache.class));
    artifactCache.close();
    managerScope.close();
  }

  @Test
  public void testCreateDirCacheOnly() throws Exception {
    ArtifactCacheBuckConfig cacheConfig =
        ArtifactCacheBuckConfigTest.createFromText("[cache]", "mode = dir");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();
    ArtifactCache artifactCache =
        newArtifactCache(cacheConfig, projectFilesystem, buckEventBus, Optional.empty())
            .newInstance();

    assertThat(stripDecorators(artifactCache), Matchers.instanceOf(DirArtifactCache.class));
    artifactCache.close();
    managerScope.close();
  }

  @Test
  public void testCreateSQLiteCacheOnly() throws Exception {
    ArtifactCacheBuckConfig cacheConfig =
        ArtifactCacheBuckConfigTest.createFromText(
            "[cache]", "mode = sqlite", "sqlite_cache_names = name1");
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(tempDir.getRoot());
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();
    ArtifactCache artifactCache =
        newArtifactCache(cacheConfig, projectFilesystem, buckEventBus, Optional.empty())
            .newInstance();

    assertThat(stripDecorators(artifactCache), Matchers.instanceOf(SQLiteArtifactCache.class));
    artifactCache.close();
    managerScope.close();
  }

  @Test
  public void testCreateMultipleDirCaches() throws Exception {
    ArtifactCacheBuckConfig cacheConfig =
        ArtifactCacheBuckConfigTest.createFromText(
            "[cache]",
            "dir_cache_names = dir1, dir2",
            "[cache#dir1]",
            "dir = dir1",
            "dir_mode = readwrite",
            "[cache#dir2]",
            "dir = dir2",
            "dir_mode = readonly");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();
    ArtifactCache artifactCache =
        stripDecorators(
            newArtifactCache(cacheConfig, projectFilesystem, buckEventBus, Optional.empty())
                .newInstance());

    assertThat(artifactCache, Matchers.instanceOf(MultiArtifactCache.class));

    MultiArtifactCache multiArtifactCache = (MultiArtifactCache) artifactCache;
    assertThat(multiArtifactCache.getArtifactCaches().size(), Matchers.equalTo(2));

    ArtifactCache c1 = stripDecorators(multiArtifactCache.getArtifactCaches().get(0));
    ArtifactCache c2 = stripDecorators(multiArtifactCache.getArtifactCaches().get(1));
    assertThat(c1, Matchers.instanceOf(DirArtifactCache.class));
    assertThat(c2, Matchers.instanceOf(DirArtifactCache.class));

    DirArtifactCache dir1 = (DirArtifactCache) c1;
    assertThat(dir1.getCacheDir(), Matchers.equalTo(Paths.get("dir1").toAbsolutePath()));
    assertThat(dir1.getCacheReadMode(), Matchers.equalTo(CacheReadMode.READWRITE));

    DirArtifactCache dir2 = (DirArtifactCache) c2;
    assertThat(dir2.getCacheDir(), Matchers.equalTo(Paths.get("dir2").toAbsolutePath()));
    assertThat(dir2.getCacheReadMode(), Matchers.equalTo(CacheReadMode.READONLY));
    artifactCache.close();
    managerScope.close();
  }

  @Test
  public void testCreateMultipleSQLiteCaches() throws Exception {
    ArtifactCacheBuckConfig cacheConfig =
        ArtifactCacheBuckConfigTest.createFromText(
            "[cache]",
            "mode = sqlite",
            "sqlite_cache_names = name1, name2, name3",
            "[cache#name1]",
            "[cache#name2]",
            "sqlite_mode = readwrite",
            "[cache#name3]",
            "sqlite_mode = readonly");
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(tempDir.getRoot());
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();
    ArtifactCache artifactCache =
        stripDecorators(
            newArtifactCache(cacheConfig, projectFilesystem, buckEventBus, Optional.empty())
                .newInstance());

    assertThat(artifactCache, Matchers.instanceOf(MultiArtifactCache.class));

    MultiArtifactCache multiArtifactCache = (MultiArtifactCache) artifactCache;
    assertThat(multiArtifactCache.getArtifactCaches().size(), Matchers.equalTo(3));

    ArtifactCache c1 = stripDecorators(multiArtifactCache.getArtifactCaches().get(0));
    ArtifactCache c2 = stripDecorators(multiArtifactCache.getArtifactCaches().get(1));
    ArtifactCache c3 = stripDecorators(multiArtifactCache.getArtifactCaches().get(2));
    assertThat(c1, Matchers.instanceOf(SQLiteArtifactCache.class));
    assertThat(c2, Matchers.instanceOf(SQLiteArtifactCache.class));
    assertThat(c3, Matchers.instanceOf(SQLiteArtifactCache.class));

    SQLiteArtifactCache cache1 = (SQLiteArtifactCache) c1;
    assertThat(cache1.getCacheReadMode(), Matchers.equalTo(CacheReadMode.READWRITE));

    SQLiteArtifactCache cache2 = (SQLiteArtifactCache) c2;
    assertThat(cache2.getCacheReadMode(), Matchers.equalTo(CacheReadMode.READWRITE));

    SQLiteArtifactCache cache3 = (SQLiteArtifactCache) c3;
    assertThat(cache3.getCacheReadMode(), Matchers.equalTo(CacheReadMode.READONLY));

    artifactCache.close();
    managerScope.close();
  }

  @Test
  public void testCreateBoth() throws Exception {
    ArtifactCacheBuckConfig cacheConfig =
        ArtifactCacheBuckConfigTest.createFromText("[cache]", "mode = dir, http");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();
    ArtifactCache artifactCache =
        newArtifactCache(cacheConfig, projectFilesystem, buckEventBus, Optional.empty())
            .newInstance();
    assertThat(stripDecorators(artifactCache), Matchers.instanceOf(MultiArtifactCache.class));
    artifactCache.close();
    managerScope.close();
  }

  @Test
  public void testCreateDirCacheOnlyWhenOnBlacklistedWifi() throws Exception {
    ArtifactCacheBuckConfig cacheConfig =
        ArtifactCacheBuckConfigTest.createFromText(
            "[cache]", "mode = dir, http", "blacklisted_wifi_ssids = weevil, evilwifi");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();
    ArtifactCache artifactCache =
        newArtifactCache(cacheConfig, projectFilesystem, buckEventBus, Optional.of("evilwifi"))
            .newInstance();
    assertThat(stripDecorators(artifactCache), Matchers.instanceOf(DirArtifactCache.class));
    artifactCache.close();
    managerScope.close();
  }

  @Test
  public void testCreateRemoteCacheOnly() throws Exception {
    ArtifactCacheBuckConfig cacheConfig =
        ArtifactCacheBuckConfigTest.createFromText("[cache]", "mode = dir, http");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();
    ArtifactCache artifactCache =
        newArtifactCache(cacheConfig, projectFilesystem, buckEventBus, Optional.empty())
            .remoteOnlyInstance(false, false);
    assertThat(stripDecorators(artifactCache), Matchers.instanceOf(HttpArtifactCache.class));
    artifactCache.close();
    managerScope.close();
  }

  @Test
  public void testCreateLocalCacheOnly() throws Exception {
    ArtifactCacheBuckConfig cacheConfig =
        ArtifactCacheBuckConfigTest.createFromText("[cache]", "mode = dir, http");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();
    ArtifactCache artifactCache =
        newArtifactCache(cacheConfig, projectFilesystem, buckEventBus, Optional.empty())
            .localOnlyInstance(false, false);
    assertThat(stripDecorators(artifactCache), Matchers.instanceOf(DirArtifactCache.class));
    artifactCache.close();
    managerScope.close();
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
    if (artifactCache instanceof RetryingCacheDecorator) {
      RetryingCacheDecorator cacheDecorator = (RetryingCacheDecorator) artifactCache;
      return stripDecorators(cacheDecorator.getDelegate());
    }
    return artifactCache;
  }

  private ArtifactCaches newArtifactCache(
      ArtifactCacheBuckConfig cacheConfig,
      ProjectFilesystem projectFilesystem,
      BuckEventBus buckEventBus,
      Optional<String> wifiSsid) {
    CellPathResolver cellPathResolver = TestCellPathResolver.get(projectFilesystem);
    return new ArtifactCaches(
        cacheConfig,
        buckEventBus,
        target -> unconfiguredBuildTargetFactory.create(cellPathResolver, target),
        TargetConfigurationSerializerForTests.create(cellPathResolver),
        projectFilesystem,
        wifiSsid,
        MoreExecutors.newDirectExecutorService(),
        MoreExecutors.newDirectExecutorService(),
        MoreExecutors.newDirectExecutorService(),
        MoreExecutors.newDirectExecutorService(),
        managerScope,
        "test://",
        "hostname",
        ClientCertificateHandler.fromConfiguration(cacheConfig));
  }
}
