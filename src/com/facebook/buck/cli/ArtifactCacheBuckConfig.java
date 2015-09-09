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

package com.facebook.buck.cli;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.DirArtifactCache;
import com.facebook.buck.rules.HttpArtifactCache;
import com.facebook.buck.rules.MultiArtifactCache;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.unit.SizeUnit;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import com.squareup.okhttp.ConnectionPool;
import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Response;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Represents configuration specific to the {@link ArtifactCache}s.
 */
public class ArtifactCacheBuckConfig {
  private static final String DEFAULT_CACHE_DIR = "buck-cache";
  private static final String DEFAULT_DIR_CACHE_MODE = CacheMode.readwrite.name();
  private static final String DEFAULT_HTTP_URL = "http://localhost:8080";
  private static final String DEFAULT_HTTP_CACHE_MODE = CacheMode.readwrite.name();
  private static final String DEFAULT_HTTP_CACHE_TIMEOUT_SECONDS = "3";

  private final BuckConfig buckConfig;
  private final ProjectFilesystem projectFilesystem;

  public ArtifactCacheBuckConfig(
      BuckConfig buckConfig,
      ProjectFilesystem projectFilesystem) {
    this.buckConfig = buckConfig;
    this.projectFilesystem = projectFilesystem;
  }

  public Path getCacheDir() {
    String cacheDir = buckConfig.getValue("cache", "dir").or(DEFAULT_CACHE_DIR);
    Path pathToCacheDir =
        buckConfig.resolvePathThatMayBeOutsideTheProjectFilesystem(Paths.get(cacheDir));
    return Preconditions.checkNotNull(pathToCacheDir);
  }

  public ArtifactCache createArtifactCache(
      Optional<String> currentWifiSsid,
      BuckEventBus buckEventBus) {
    ImmutableList<String> modes = getArtifactCacheModes();
    if (modes.isEmpty()) {
      return new NoopArtifactCache();
    }
    ImmutableList.Builder<ArtifactCache> builder = ImmutableList.builder();
    boolean useDistributedCache = isWifiUsableForDistributedCache(currentWifiSsid);
    try {
      for (String mode : modes) {
        switch (ArtifactCacheNames.valueOf(mode)) {
          case dir:
            ArtifactCache dirArtifactCache = createDirArtifactCache();
            buckEventBus.register(dirArtifactCache);
            builder.add(dirArtifactCache);
            break;
          case http:
            if (useDistributedCache) {
              ArtifactCache httpArtifactCache = createHttpArtifactCache(buckEventBus);
              builder.add(httpArtifactCache);
            }
            break;
        }
      }
    } catch (IllegalArgumentException e) {
      throw new HumanReadableException("Unusable cache.mode: '%s'", modes.toString());
    }
    ImmutableList<ArtifactCache> artifactCaches = builder.build();
    if (artifactCaches.size() == 1) {
      // Don't bother wrapping a single artifact cache in MultiArtifactCache.
      return artifactCaches.get(0);
    } else {
      return new MultiArtifactCache(artifactCaches);
    }
  }

  ImmutableList<String> getArtifactCacheModes() {
    return buckConfig.getListWithoutComments("cache", "mode");
  }

  public Optional<Long> getCacheDirMaxSizeBytes() {
    return buckConfig.getValue("cache", "dir_max_size").transform(
        new Function<String, Long>() {
          @Override
          public Long apply(String input) {
            return SizeUnit.parseBytes(input);
          }
        });
  }

  private ArtifactCache createDirArtifactCache() {
    Path cacheDir = getCacheDir();
    boolean doStore = readCacheMode("dir_mode", DEFAULT_DIR_CACHE_MODE);
    try {
      return new DirArtifactCache(
          "dir",
          projectFilesystem,
          cacheDir,
          doStore,
          getCacheDirMaxSizeBytes());
    } catch (IOException e) {
      throw new HumanReadableException(
          "Failure initializing artifact cache directory: %s",
          cacheDir);
    }
  }

  @VisibleForTesting
  boolean isWifiUsableForDistributedCache(Optional<String> currentWifiSsid) {
    // cache.blacklisted_wifi_ssids
    ImmutableSet<String> blacklistedWifi = ImmutableSet.copyOf(
        buckConfig.getListWithoutComments("cache", "blacklisted_wifi_ssids"));
    if (currentWifiSsid.isPresent() && blacklistedWifi.contains(currentWifiSsid.get())) {
      // We're connected to a wifi hotspot that has been explicitly blacklisted from connecting to
      // a distributed cache.
      return false;
    }
    return true;
  }

  private ArtifactCache createHttpArtifactCache(BuckEventBus buckEventBus) {
    URL url;
    try {
      url = new URL(buckConfig.getValue("cache", "http_url").or(DEFAULT_HTTP_URL));
    } catch (MalformedURLException e) {
      throw new HumanReadableException(e, "Malformed [cache]http_url: %s", e.getMessage());
    }

    int timeoutSeconds = Integer.parseInt(
        buckConfig.getValue("cache", "http_timeout_seconds")
            .or(DEFAULT_HTTP_CACHE_TIMEOUT_SECONDS));

    boolean doStore = readCacheMode("http_mode", DEFAULT_HTTP_CACHE_MODE);

    // Setup the defaut client to use.
    OkHttpClient client = new OkHttpClient();
    final String localhost = buckConfig.getLocalhost();
    client.networkInterceptors().add(
        new Interceptor() {
          @Override
          public Response intercept(Chain chain) throws IOException {
            return chain.proceed(
                chain.request().newBuilder()
                    .addHeader("X-BuckCache-User", System.getProperty("user.name", "<unknown>"))
                    .addHeader("X-BuckCache-Host", localhost)
                    .build());
          }
        });
    client.setConnectTimeout(timeoutSeconds, TimeUnit.SECONDS);
    client.setConnectionPool(
        new ConnectionPool(
            // It's important that this number is greater than the `-j` parallelism,
            // as if it's too small, we'll overflow the reusable connection pool and
            // start spamming new connections.  While this isn't the best location,
            // the other current option is setting this wherever we construct a `Build`
            // object and have access to the `-j` argument.  However, since that is
            // created in several places leave it here for now.
            /* maxIdleConnections */ 200,
            /* keepAliveDurationMs */ TimeUnit.MINUTES.toMillis(5)));

    // For fetches, use a client with a read timeout.
    OkHttpClient fetchClient = client.clone();
    fetchClient.setReadTimeout(timeoutSeconds, TimeUnit.SECONDS);

    return new HttpArtifactCache(
        "http",
        fetchClient,
        client,
        url,
        doStore,
        projectFilesystem,
        buckEventBus,
        Hashing.crc32());
  }

  private boolean readCacheMode(String fieldName, String defaultValue) {
    String cacheMode = buckConfig.getValue("cache", fieldName).or(defaultValue);
    final boolean doStore;
    try {
      doStore = CacheMode.valueOf(cacheMode).doStore;
    } catch (IllegalArgumentException e) {
      throw new HumanReadableException("Unusable cache.%s: '%s'", fieldName, cacheMode);
    }
    return doStore;
  }

  private enum ArtifactCacheNames {
    dir,
    http
  }

  private enum CacheMode {
    readonly(false),
    readwrite(true),
    ;

    private final boolean doStore;

    private CacheMode(boolean doStore) {
      this.doStore = doStore;
    }
  }



}
