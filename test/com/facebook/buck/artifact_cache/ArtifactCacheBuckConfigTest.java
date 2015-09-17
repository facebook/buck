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
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

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
        "dir = http",
        "blacklisted_wifi_ssids = yolocoaster");
    assertThat(
        config.isWifiUsableForDistributedCache(Optional.of("yolocoaster")),
        Matchers.is(false));
    assertThat(
        config.isWifiUsableForDistributedCache(Optional.of("swagtastic")),
        Matchers.is(true));

    config = createFromText(
        "[cache]",
        "dir = http");

    assertThat(
        config.isWifiUsableForDistributedCache(Optional.of("yolocoaster")),
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

    assertThat(config.getHttpCacheTimeoutSeconds(), Matchers.is(42));
    assertThat(config.getHttpCacheUrl(), Matchers.equalTo(new URI("http://test.host:1234")));
    assertThat(config.getHttpCacheReadMode(), Matchers.is(true));
  }

  @Test
  public void testDirCacheSettings() throws IOException {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "dir = cache_dir",
        "dir_mode = readonly",
        "dir_max_size = 1022B");

    assertThat(config.getCacheDir(), Matchers.equalTo(Paths.get("cache_dir").toAbsolutePath()));
    assertThat(config.getDirCacheReadMode(), Matchers.is(false));
    assertThat(config.getCacheDirMaxSizeBytes(), Matchers.equalTo(Optional.of(1022L)));
  }

  @Test(expected = HumanReadableException.class)
  public void testMalformedHttpUrl() throws IOException {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "http_url = notaurl");

    config.getHttpCacheUrl();
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

  public static ArtifactCacheBuckConfig createFromText(String... lines) throws IOException {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    StringReader reader = new StringReader(Joiner.on('\n').join(lines));
    return new ArtifactCacheBuckConfig(
        BuckConfigTestUtils.createFromReader(
            reader,
            projectFilesystem,
            Platform.detect(),
            ImmutableMap.copyOf(System.getenv())));
  }

}
