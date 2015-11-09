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

import static com.facebook.buck.util.BuckConstant.DEFAULT_CACHE_DIR;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.unit.SizeUnit;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Represents configuration specific to the {@link ArtifactCache}s.
 */
public class ArtifactCacheBuckConfig {
  private static final String CACHE_SECTION_NAME = "cache";

  private static final String DEFAULT_DIR_CACHE_MODE = CacheReadMode.readwrite.name();

  // Names of the fields in a [cache*] section that describe a single HTTP cache.
  private static final String HTTP_URL_FIELD_NAME = "http_url";
  private static final String HTTP_BLACKLISTED_WIFI_SSIDS_FIELD_NAME = "blacklisted_wifi_ssids";
  private static final String HTTP_MODE_FIELD_NAME = "http_mode";
  private static final String HTTP_TIMEOUT_SECONDS_FIELD_NAME = "http_timeout_seconds";
  private static final ImmutableSet<String> HTTP_CACHE_DESCRIPTION_FIELDS = ImmutableSet.of(
      HTTP_URL_FIELD_NAME,
      HTTP_BLACKLISTED_WIFI_SSIDS_FIELD_NAME,
      HTTP_MODE_FIELD_NAME,
      HTTP_TIMEOUT_SECONDS_FIELD_NAME);

  // List of names of cache-* sections that contain the fields above. This is used to emulate
  // dicts, essentially.
  private static final String HTTP_CACHE_NAMES_FIELD_NAME = "http_cache_names";

  private static final URI DEFAULT_HTTP_URL = URI.create("http://localhost:8080/");
  private static final String DEFAULT_HTTP_CACHE_MODE = CacheReadMode.readwrite.name();
  private static final long DEFAULT_HTTP_CACHE_TIMEOUT_SECONDS = 3L;
  private static final String DEFAULT_HTTP_MAX_CONCURRENT_WRITES = "1";
  private static final String DEFAULT_HTTP_WRITE_SHUTDOWN_TIMEOUT_SECONDS = "1800"; // 30 minutes


  private final BuckConfig buckConfig;

  public ArtifactCacheBuckConfig(BuckConfig buckConfig) {
    this.buckConfig = buckConfig;
  }

  public int getHttpMaxConcurrentWrites() {
    return Integer.valueOf(
        buckConfig.getValue("cache", "http_max_concurrent_writes")
            .or(DEFAULT_HTTP_MAX_CONCURRENT_WRITES));
  }

  public int getHttpWriterShutdownTimeout() {
    return Integer.valueOf(
        buckConfig.getValue("cache", "http_writer_shutdown_timeout_seconds")
            .or(DEFAULT_HTTP_WRITE_SHUTDOWN_TIMEOUT_SECONDS));
  }

  public boolean hasAtLeastOneWriteableCache() {
    return FluentIterable.from(getHttpCaches()).anyMatch(
        new Predicate<HttpCacheEntry>() {
          @Override
          public boolean apply(HttpCacheEntry input) {
            return input.getCacheReadMode().equals(ArtifactCacheBuckConfig.CacheReadMode.readwrite);
          }
        });
  }

  public String getHostToReportToRemoteCacheServer() {
    return buckConfig.getLocalhost();
  }

  public ImmutableList<String> getArtifactCacheModesRaw() {
    return buckConfig.getListWithoutComments(CACHE_SECTION_NAME, "mode");
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
                  throw new HumanReadableException(
                      "Unusable %s.mode: '%s'",
                      CACHE_SECTION_NAME,
                      input);
                }
              }
            })
        .toSet();
  }

  public Path getCacheDir() {
    String cacheDir = buckConfig.getValue(CACHE_SECTION_NAME, "dir").or(DEFAULT_CACHE_DIR);
    Path pathToCacheDir = buckConfig.resolvePathThatMayBeOutsideTheProjectFilesystem(
        Paths.get(
            cacheDir));
    return Preconditions.checkNotNull(pathToCacheDir);
  }

  public Optional<Long> getCacheDirMaxSizeBytes() {
    return buckConfig.getValue(CACHE_SECTION_NAME, "dir_max_size").transform(
        new Function<String, Long>() {
          @Override
          public Long apply(String input) {
            return SizeUnit.parseBytes(input);
          }
        });
  }

  public boolean getServingLocalCacheEnabled() {
    return buckConfig.getBooleanValue(CACHE_SECTION_NAME, "serve_local_cache", false);
  }

  public CacheReadMode getDirCacheReadMode() {
    return getCacheReadMode(CACHE_SECTION_NAME, "dir_mode", DEFAULT_DIR_CACHE_MODE);
  }

  public ImmutableSet<HttpCacheEntry> getHttpCaches() {
    ImmutableSet.Builder<HttpCacheEntry> result = ImmutableSet.builder();

    ImmutableSet<String> httpCacheNames = getHttpCacheNames();
    boolean implicitLegacyCache = httpCacheNames.isEmpty() &&
        getArtifactCacheModes().contains(ArtifactCacheMode.http);
    if (implicitLegacyCache || legacyCacheConfigurationFieldsPresent()) {
      result.add(obtainEntryForName(Optional.<String>absent()));
    }

    for (String cacheName : httpCacheNames) {
      result.add(obtainEntryForName(Optional.of(cacheName)));
    }
    return result.build();
  }

  private CacheReadMode getCacheReadMode(String section, String fieldName, String defaultValue) {
    String cacheMode = buckConfig.getValue(section, fieldName).or(defaultValue);
    final CacheReadMode result;
    try {
      result = CacheReadMode.valueOf(cacheMode);
    } catch (IllegalArgumentException e) {
      throw new HumanReadableException("Unusable cache.%s: '%s'", fieldName, cacheMode);
    }
    return result;
  }

  private ImmutableSet<String> getHttpCacheNames() {
    ImmutableList<String> httpCacheNames = buckConfig.getListWithoutComments(
        CACHE_SECTION_NAME,
        HTTP_CACHE_NAMES_FIELD_NAME);
    return ImmutableSet.copyOf(httpCacheNames);
  }

  private HttpCacheEntry obtainEntryForName(Optional<String> cacheName) {
    final String section = Joiner.on('#').skipNulls().join(CACHE_SECTION_NAME, cacheName.orNull());

    HttpCacheEntry.Builder builder = HttpCacheEntry.builder();
    builder.setName(cacheName);
    builder.setUrl(getUri(section, HTTP_URL_FIELD_NAME).or(DEFAULT_HTTP_URL));
    builder.setTimeoutSeconds(
        buckConfig.getLong(section, HTTP_TIMEOUT_SECONDS_FIELD_NAME)
            .or(DEFAULT_HTTP_CACHE_TIMEOUT_SECONDS).intValue());
    builder.setBlacklistedWifiSsids(
        buckConfig.getListWithoutComments(section, HTTP_BLACKLISTED_WIFI_SSIDS_FIELD_NAME));
    builder.setCacheReadMode(
        getCacheReadMode(section, HTTP_MODE_FIELD_NAME, DEFAULT_HTTP_CACHE_MODE));
    return builder.build();
  }

  private final Optional<URI> getUri(String section, String field) {
    try {
      // URL has stricter parsing rules than URI, so we want to use that constructor to surface
      // the error message early. Passing around a URL is problematic as it hits DNS from the
      // equals method, which is why the (new URL(...).toURI()) call instead of just URI.create.
      Optional<String> value = buckConfig.getValue(section, field);
      if (!value.isPresent()) {
        return Optional.absent();
      }
      return Optional.of(new URL(value.get()).toURI());
    } catch (URISyntaxException|MalformedURLException e) {
      throw new HumanReadableException(e, "Malformed [cache]%s: %s", field, e.getMessage());
    }
  }

  private boolean legacyCacheConfigurationFieldsPresent() {
    for (String field : HTTP_CACHE_DESCRIPTION_FIELDS) {
      if (buckConfig.getValue(CACHE_SECTION_NAME, field).isPresent()) {
        return true;
      }
    }
    return false;
  }

  public enum ArtifactCacheMode {
    dir,
    http
  }

  public enum CacheReadMode {
    readonly(false),
    readwrite(true),
    ;

    private final boolean doStore;

    CacheReadMode(boolean doStore) {
      this.doStore = doStore;
    }

    public boolean isDoStore() {
      return doStore;
    }
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractHttpCacheEntry {
    public abstract Optional<String> getName();
    public abstract URI getUrl();
    public abstract int getTimeoutSeconds();
    public abstract CacheReadMode getCacheReadMode();
    protected abstract ImmutableSet<String> getBlacklistedWifiSsids();

    public boolean isWifiUsableForDistributedCache(Optional<String> currentWifiSsid) {
      if (currentWifiSsid.isPresent() &&
          getBlacklistedWifiSsids().contains(currentWifiSsid.get())) {
        // We're connected to a wifi hotspot that has been explicitly blacklisted from connecting to
        // a distributed cache.
        return false;
      }
      return true;
    }
  }
}
