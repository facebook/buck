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

import com.facebook.buck.cli.BuckConfigTestUtils;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.file.Paths;

public class ArtifactCacheBuckConfigTest {

  @Test
  public void testWifiBlacklist() throws IOException {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "mode = http",
        "blacklisted_wifi_ssids = yolocoaster");
    ImmutableSet<HttpCacheEntry> httpCaches = config.getHttpCaches();
    assertThat(httpCaches, Matchers.hasSize(1));
    HttpCacheEntry cacheEntry = FluentIterable.from(httpCaches).get(0);

    assertThat(
        cacheEntry.isWifiUsableForDistributedCache(Optional.of("yolocoaster")),
        Matchers.is(false));
    assertThat(
        cacheEntry.isWifiUsableForDistributedCache(Optional.of("swagtastic")),
        Matchers.is(true));

    config = createFromText(
        "[cache]",
        "mode = http");
    httpCaches = config.getHttpCaches();
    assertThat(httpCaches, Matchers.hasSize(1));
    cacheEntry = FluentIterable.from(httpCaches).get(0);

    assertThat(
        cacheEntry.isWifiUsableForDistributedCache(Optional.of("yolocoaster")),
        Matchers.is(true));
  }

  @Test
  public void testMode() throws IOException {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "mode = http");
    assertThat(
        config.getArtifactCacheModes(),
        Matchers.contains(ArtifactCacheBuckConfig.ArtifactCacheMode.http));

    config = createFromText(
        "[cache]",
        "mode = dir");
    assertThat(
        config.getArtifactCacheModes(),
        Matchers.contains(ArtifactCacheBuckConfig.ArtifactCacheMode.dir));

    config = createFromText(
        "[cache]",
        "mode = dir, http");
    assertThat(
        config.getArtifactCacheModes(),
        Matchers.containsInAnyOrder(
            ArtifactCacheBuckConfig.ArtifactCacheMode.dir,
            ArtifactCacheBuckConfig.ArtifactCacheMode.http));
  }

  @Test
  public void testHttpCacheSettings() throws Exception {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "http_timeout_seconds = 42",
        "http_url = http://test.host:1234",
        "http_mode = readwrite");
    ImmutableSet<HttpCacheEntry> httpCaches = config.getHttpCaches();
    assertThat(httpCaches, Matchers.hasSize(1));
    HttpCacheEntry cacheEntry = FluentIterable.from(httpCaches).get(0);

    assertThat(cacheEntry.getTimeoutSeconds(), Matchers.is(42));
    assertThat(cacheEntry.getUrl(), Matchers.equalTo(new URI("http://test.host:1234")));
    assertThat(
        cacheEntry.getCacheReadMode(),
        Matchers.is(ArtifactCacheBuckConfig.CacheReadMode.readwrite));
  }

  @Test
  public void testDirCacheSettings() throws IOException {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "dir = cache_dir",
        "dir_mode = readonly",
        "dir_max_size = 1022B");

    assertThat(config.getCacheDir(), Matchers.equalTo(Paths.get("cache_dir").toAbsolutePath()));
    assertThat(
        config.getDirCacheReadMode(),
        Matchers.is(ArtifactCacheBuckConfig.CacheReadMode.readonly));
    assertThat(config.getCacheDirMaxSizeBytes(), Matchers.equalTo(Optional.of(1022L)));
  }

  @Test(expected = HumanReadableException.class)
  public void testMalformedHttpUrl() throws IOException {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "http_url = notaurl");

    config.getHttpCaches();
  }

  @Test(expected = HumanReadableException.class)
  public void testMalformedMode() throws IOException {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "dir_mode = notamode");

    config.getDirCacheReadMode();
  }

  @Test
  public void testExpandUserHomeCacheDir() throws IOException {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "dir = ~/cache_dir");
    assertThat(
        "User home cache directory must be expanded.",
        config.getCacheDir(),
        Matchers.equalTo(MorePaths.expandHomeDir(Paths.get("~/cache_dir"))));
  }

  @Test
  public void testNamedHttpCachesOnly() throws IOException {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "http_cache_names = bob, fred",
        "",
        "[cache#bob]",
        "http_url = http://bob.com/",
        "",
        "[cache#fred]",
        "http_url = http://fred.com/",
        "http_timeout_seconds = 42",
        "http_mode = readonly",
        "blacklisted_wifi_ssids = yolo",
        "",
        "[cache#ignoreme]",
        "http_url = http://ignored.com/");

    assertThat(config.getHttpCaches(), Matchers.hasSize(2));

    HttpCacheEntry bobCache = FluentIterable.from(config.getHttpCaches()).get(0);
    assertThat(bobCache.getUrl(), Matchers.equalTo(URI.create("http://bob.com/")));
    assertThat(bobCache.getCacheReadMode(), Matchers.equalTo(
            ArtifactCacheBuckConfig.CacheReadMode.readwrite));
    assertThat(bobCache.getTimeoutSeconds(), Matchers.is(3));

    HttpCacheEntry fredCache = FluentIterable.from(config.getHttpCaches()).get(1);
    assertThat(fredCache.getUrl(), Matchers.equalTo(URI.create("http://fred.com/")));
    assertThat(fredCache.getTimeoutSeconds(), Matchers.is(42));
    assertThat(fredCache.getCacheReadMode(), Matchers.equalTo(
            ArtifactCacheBuckConfig.CacheReadMode.readonly));
    assertThat(fredCache.isWifiUsableForDistributedCache(Optional.of("wsad")), Matchers.is(true));
    assertThat(fredCache.isWifiUsableForDistributedCache(Optional.of("yolo")), Matchers.is(false));
  }

  @Test
  public void testNamedAndLegacyCaches() throws IOException {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "http_timeout_seconds = 42",
        "http_cache_names = bob",
        "",
        "[cache#bob]",
        "http_url = http://bob.com/");

    assertThat(config.getHttpCaches(), Matchers.hasSize(2));

    HttpCacheEntry legacyCache = FluentIterable.from(config.getHttpCaches()).get(0);
    assertThat(legacyCache.getUrl(), Matchers.equalTo(URI.create("http://localhost:8080/")));
    assertThat(legacyCache.getTimeoutSeconds(), Matchers.is(42));

    HttpCacheEntry bobCache = FluentIterable.from(config.getHttpCaches()).get(1);
    assertThat(bobCache.getUrl(), Matchers.equalTo(URI.create("http://bob.com/")));
    assertThat(bobCache.getCacheReadMode(), Matchers.equalTo(
            ArtifactCacheBuckConfig.CacheReadMode.readwrite));
    assertThat(bobCache.getTimeoutSeconds(), Matchers.is(3));
  }

  public static ArtifactCacheBuckConfig createFromText(String... lines) throws IOException {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    StringReader reader = new StringReader(Joiner.on('\n').join(lines));
    return new ArtifactCacheBuckConfig(
        BuckConfigTestUtils.createFromReader(
            reader,
            projectFilesystem,
            Architecture.detect(),
            Platform.detect(),
            ImmutableMap.copyOf(System.getenv())));
  }

}
