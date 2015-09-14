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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.unit.SizeUnit;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

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

  public ArtifactCacheBuckConfig(BuckConfig buckConfig) {
    this.buckConfig = buckConfig;
  }

  public int getHttpCacheTimeoutSeconds() {
    return Integer.parseInt(
        buckConfig.getValue("cache", "http_timeout_seconds")
            .or(DEFAULT_HTTP_CACHE_TIMEOUT_SECONDS));
  }

  public URL getHttpCacheUrl() {
    try {
      return new URL(buckConfig.getValue("cache", "http_url").or(DEFAULT_HTTP_URL));
    } catch (MalformedURLException e) {
      throw new HumanReadableException(e, "Malformed [cache]http_url: %s", e.getMessage());
    }
  }

  public String getHostToReportToRemoteCacheServer() {
    return buckConfig.getLocalhost();
  }

  public ImmutableList<String> getArtifactCacheModesRaw() {
    return buckConfig.getListWithoutComments("cache", "mode");
  }

  public ImmutableSet<ArtifactCacheMode> getArtifactCacheModes() {
    return FluentIterable.from(getArtifactCacheModesRaw())
        .transform(
            new Function<String, ArtifactCacheMode>() {
              @Override
              public ArtifactCacheMode apply(String input) {
                try {
                  return ArtifactCacheMode.valueOf(input);
                } catch (IllegalArgumentException e) {
                  throw new HumanReadableException("Unusable cache.mode: '%s'", input);
                }
              }
            })
        .toSet();
  }

  public Path getCacheDir() {
    String cacheDir = buckConfig.getValue("cache", "dir").or(DEFAULT_CACHE_DIR);
    Path pathToCacheDir = buckConfig.resolvePathThatMayBeOutsideTheProjectFilesystem(
        Paths.get(
            cacheDir));
    return Preconditions.checkNotNull(pathToCacheDir);
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

  public boolean isWifiUsableForDistributedCache(Optional<String> currentWifiSsid) {
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

  public boolean getDirCacheReadMode() {
    return readCacheMode("dir_mode", DEFAULT_DIR_CACHE_MODE);
  }

  public boolean getHttpCacheReadMode() {
    return readCacheMode("http_mode", DEFAULT_HTTP_CACHE_MODE);
  }

  private boolean readCacheMode(String fieldName, String defaultValue) {
    String cacheMode = buckConfig.getValue("cache", fieldName).or(defaultValue);
    final boolean doStore;
    try {
      doStore = CacheMode.valueOf(cacheMode).isDoStore();
    } catch (IllegalArgumentException e) {
      throw new HumanReadableException("Unusable cache.%s: '%s'", fieldName, cacheMode);
    }
    return doStore;
  }

  public enum ArtifactCacheMode {
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

    public boolean isDoStore() {
      return doStore;
    }
  }
}
