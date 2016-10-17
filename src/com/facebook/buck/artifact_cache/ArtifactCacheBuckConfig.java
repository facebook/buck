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
import com.facebook.buck.cli.SlbBuckConfig;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.unit.SizeUnit;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

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
  private static final String HTTP_READ_HEADERS_FIELD_NAME = "http_read_headers";
  private static final String HTTP_WRITE_HEADERS_FIELD_NAME = "http_write_headers";
  private static final String HTTP_CACHE_ERROR_MESSAGE_NAME = "http_error_message_format";
  private static final String HTTP_MAX_STORE_SIZE = "http_max_store_size";
  private static final String HTTP_THREAD_POOL_SIZE = "http_thread_pool_size";
  private static final String HTTP_THREAD_POOL_KEEP_ALIVE_DURATION_MILLIS =
      "http_thread_pool_keep_alive_duration_millis";
  private static final ImmutableSet<String> HTTP_CACHE_DESCRIPTION_FIELDS = ImmutableSet.of(
      HTTP_URL_FIELD_NAME,
      HTTP_BLACKLISTED_WIFI_SSIDS_FIELD_NAME,
      HTTP_MODE_FIELD_NAME,
      HTTP_TIMEOUT_SECONDS_FIELD_NAME,
      HTTP_READ_HEADERS_FIELD_NAME,
      HTTP_WRITE_HEADERS_FIELD_NAME,
      HTTP_CACHE_ERROR_MESSAGE_NAME,
      HTTP_MAX_STORE_SIZE);
  private static final String HTTP_MAX_FETCH_RETRIES = "http_max_fetch_retries";

  // List of names of cache-* sections that contain the fields above. This is used to emulate
  // dicts, essentially.
  private static final String HTTP_CACHE_NAMES_FIELD_NAME = "http_cache_names";

  private static final URI DEFAULT_HTTP_URL = URI.create("http://localhost:8080/");
  private static final String DEFAULT_HTTP_CACHE_MODE = CacheReadMode.readwrite.name();
  private static final long DEFAULT_HTTP_CACHE_TIMEOUT_SECONDS = 3L;
  private static final String DEFAULT_HTTP_MAX_CONCURRENT_WRITES = "1";
  private static final String DEFAULT_HTTP_WRITE_SHUTDOWN_TIMEOUT_SECONDS = "1800"; // 30 minutes
  private static final String DEFAULT_HTTP_CACHE_ERROR_MESSAGE =
      "{cache_name} cache encountered an error: {error_message}";
  private static final int DEFAULT_HTTP_MAX_FETCH_RETRIES = 2;

  private static final String SERVED_CACHE_ENABLED_FIELD_NAME = "serve_local_cache";
  private static final String DEFAULT_SERVED_CACHE_MODE = CacheReadMode.readonly.name();
  private static final String SERVED_CACHE_READ_MODE_FIELD_NAME = "served_local_cache_mode";
  private static final String LOAD_BALANCING_TYPE = "load_balancing_type";
  private static final LoadBalancingType DEFAULT_LOAD_BALANCING_TYPE =
      LoadBalancingType.SINGLE_SERVER;
  private static final long DEFAULT_HTTP_THREAD_POOL_SIZE = 200;
  private static final long DEFAULT_HTTP_THREAD_POOL_KEEP_ALIVE_DURATION_MILLIS =
      TimeUnit.MINUTES.toMillis(1);

  private static final String TWO_LEVEL_CACHING_ENABLED_FIELD_NAME = "two_level_cache_enabled";
  // Old name for "two_level_cache_minimum_size", remove eventually.
  private static final String TWO_LEVEL_CACHING_THRESHOLD_FIELD_NAME = "two_level_cache_threshold";
  private static final String TWO_LEVEL_CACHING_MIN_SIZE_FIELD_NAME =
      "two_level_cache_minimum_size";
  private static final String TWO_LEVEL_CACHING_MAX_SIZE_FIELD_NAME =
      "two_level_cache_maximum_size";
  private static final long TWO_LEVEL_CACHING_MIN_SIZE_DEFAULT = 20 * 1024L;

  private static final String HYBRID_THRIFT_ENDPOINT = "hybrid_thrift_endpoint";
  private static final String REPOSITORY = "repository";
  private static final String DEFAULT_REPOSITORY = "";

  private static final String SCHEDULE_TYPE = "schedule_type";
  private static final String DEFAULT_SCHEDULE_TYPE = "none";

  public enum LoadBalancingType {
    SINGLE_SERVER,
    CLIENT_SLB,
  }

  private final BuckConfig buckConfig;
  private final SlbBuckConfig slbConfig;

  public ArtifactCacheBuckConfig(BuckConfig buckConfig) {
    this.buckConfig = buckConfig;
    this.slbConfig = new SlbBuckConfig(buckConfig, CACHE_SECTION_NAME);
  }

  public String getRepository() {
    return buckConfig.getValue(CACHE_SECTION_NAME, REPOSITORY).orElse(DEFAULT_REPOSITORY);
  }

  public String getScheduleType() {
    return buckConfig.getValue(CACHE_SECTION_NAME, SCHEDULE_TYPE).orElse(DEFAULT_SCHEDULE_TYPE);
  }

  public SlbBuckConfig getSlbConfig() {
    return slbConfig;
  }

  public Optional<String> getHybridThriftEndpoint() {
    return buckConfig.getValue(CACHE_SECTION_NAME, HYBRID_THRIFT_ENDPOINT);
  }

  public LoadBalancingType getLoadBalancingType() {
    return buckConfig.getEnum(
        CACHE_SECTION_NAME,
        LOAD_BALANCING_TYPE,
        LoadBalancingType.class).orElse(DEFAULT_LOAD_BALANCING_TYPE);
  }

  public int getHttpMaxConcurrentWrites() {
    return Integer.valueOf(
        buckConfig.getValue(CACHE_SECTION_NAME, "http_max_concurrent_writes").orElse(
            DEFAULT_HTTP_MAX_CONCURRENT_WRITES));
  }

  public int getHttpWriterShutdownTimeout() {
    return Integer.valueOf(
        buckConfig.getValue(CACHE_SECTION_NAME, "http_writer_shutdown_timeout_seconds").orElse(
            DEFAULT_HTTP_WRITE_SHUTDOWN_TIMEOUT_SECONDS));
  }

  public int getMaxFetchRetries() {
    return buckConfig.getInteger(CACHE_SECTION_NAME, HTTP_MAX_FETCH_RETRIES).orElse(
        DEFAULT_HTTP_MAX_FETCH_RETRIES);
  }

  public boolean hasAtLeastOneWriteableCache() {
    return FluentIterable.from(getHttpCaches()).anyMatch(
        input -> input.getCacheReadMode().equals(CacheReadMode.readwrite));
  }

  public String getHostToReportToRemoteCacheServer() {
    return buckConfig.getLocalhost();
  }

  public ImmutableList<String> getArtifactCacheModesRaw() {
    // If there is a user-set value, even if it is `mode =`, use it.
    if (buckConfig.hasUserDefinedValue(CACHE_SECTION_NAME, "mode")) {
      return buckConfig.getListWithoutComments(CACHE_SECTION_NAME, "mode");
    }
    // Otherwise, we default to using the directory cache.
    return ImmutableList.of("dir");
  }

  public ImmutableSet<ArtifactCacheMode> getArtifactCacheModes() {
    return FluentIterable.from(getArtifactCacheModesRaw())
        .transform(
            input -> {
              try {
                return ArtifactCacheMode.valueOf(input);
              } catch (IllegalArgumentException e) {
                throw new HumanReadableException(
                    "Unusable %s.mode: '%s'",
                    CACHE_SECTION_NAME,
                    input);
              }
            })
        .toSet();
  }

  public Optional<DirCacheEntry> getServedLocalCache() {
    if (!getServingLocalCacheEnabled()) {
      return Optional.empty();
    }
    return Optional.of(getDirCache().withCacheReadMode(getServedLocalCacheReadMode()));
  }

  public DirCacheEntry getDirCache() {
    return DirCacheEntry.builder()
        .setCacheDir(getCacheDir())
        .setCacheReadMode(getDirCacheReadMode())
        .setMaxSizeBytes(getCacheDirMaxSizeBytes())
        .build();
  }

  public ImmutableSet<HttpCacheEntry> getHttpCaches() {
    ImmutableSet.Builder<HttpCacheEntry> result = ImmutableSet.builder();

    ImmutableSet<String> httpCacheNames = getHttpCacheNames();
    boolean implicitLegacyCache = httpCacheNames.isEmpty() &&
        getArtifactCacheModes().contains(ArtifactCacheMode.http);
    if (implicitLegacyCache || legacyCacheConfigurationFieldsPresent()) {
      result.add(obtainEntryForName(Optional.empty()));
    }

    for (String cacheName : httpCacheNames) {
      result.add(obtainEntryForName(Optional.of(cacheName)));
    }
    return result.build();
  }

  // It's important that this number is greater than the `-j` parallelism,
  // as if it's too small, we'll overflow the reusable connection pool and
  // start spamming new connections.  While this isn't the best location,
  // the other current option is setting this wherever we construct a `Build`
  // object and have access to the `-j` argument.  However, since that is
  // created in several places leave it here for now.
  public long getThreadPoolSize() {
    return buckConfig.getLong(CACHE_SECTION_NAME, HTTP_THREAD_POOL_SIZE).orElse(
        DEFAULT_HTTP_THREAD_POOL_SIZE);
  }

  public long getThreadPoolKeepAliveDurationMillis() {
    return buckConfig.getLong(
        CACHE_SECTION_NAME,
        HTTP_THREAD_POOL_KEEP_ALIVE_DURATION_MILLIS).orElse(
        DEFAULT_HTTP_THREAD_POOL_KEEP_ALIVE_DURATION_MILLIS);
  }

  public boolean getTwoLevelCachingEnabled() {
    return buckConfig.getBooleanValue(
        CACHE_SECTION_NAME,
        TWO_LEVEL_CACHING_ENABLED_FIELD_NAME,
        false);
  }

  public long getTwoLevelCachingMinimumSize() {
    return buckConfig.getValue(CACHE_SECTION_NAME, TWO_LEVEL_CACHING_MIN_SIZE_FIELD_NAME)
        .map(Optional::of)
        .orElse(buckConfig.getValue(CACHE_SECTION_NAME, TWO_LEVEL_CACHING_THRESHOLD_FIELD_NAME))
        .map(SizeUnit::parseBytes)
        .orElse(TWO_LEVEL_CACHING_MIN_SIZE_DEFAULT);
  }

  public Optional<Long> getTwoLevelCachingMaximumSize() {
    return buckConfig.getValue(CACHE_SECTION_NAME, TWO_LEVEL_CACHING_MAX_SIZE_FIELD_NAME).map(
        SizeUnit::parseBytes);
  }

  private CacheReadMode getDirCacheReadMode() {
    return getCacheReadMode(CACHE_SECTION_NAME, "dir_mode", DEFAULT_DIR_CACHE_MODE);
  }

  private Path getCacheDir() {
    String cacheDir = buckConfig.getLocalCacheDirectory();
    Path pathToCacheDir = buckConfig.resolvePathThatMayBeOutsideTheProjectFilesystem(
        Paths.get(
            cacheDir));
    return Preconditions.checkNotNull(pathToCacheDir);
  }

  private Optional<Long> getCacheDirMaxSizeBytes() {
    return buckConfig.getValue(CACHE_SECTION_NAME, "dir_max_size").map(SizeUnit::parseBytes);
  }

  private boolean getServingLocalCacheEnabled() {
    return buckConfig.getBooleanValue(CACHE_SECTION_NAME, SERVED_CACHE_ENABLED_FIELD_NAME, false);
  }

  private CacheReadMode getServedLocalCacheReadMode() {
    return getCacheReadMode(
        CACHE_SECTION_NAME,
        SERVED_CACHE_READ_MODE_FIELD_NAME,
        DEFAULT_SERVED_CACHE_MODE);
  }

  private CacheReadMode getCacheReadMode(String section, String fieldName, String defaultValue) {
    String cacheMode = buckConfig.getValue(section, fieldName).orElse(defaultValue);
    final CacheReadMode result;
    try {
      result = CacheReadMode.valueOf(cacheMode);
    } catch (IllegalArgumentException e) {
      throw new HumanReadableException("Unusable cache.%s: '%s'", fieldName, cacheMode);
    }
    return result;
  }

  private ImmutableMap<String, String> getCacheHeaders(String section, String fieldName) {
    ImmutableMap.Builder<String, String> headerBuilder = ImmutableMap.builder();
    ImmutableList<String> rawHeaders = buckConfig.getListWithoutComments(
        section,
        fieldName,
        ';');
    for (String rawHeader : rawHeaders) {
      List<String> splitHeader = Splitter.on(':')
          .omitEmptyStrings()
          .trimResults()
          .splitToList(rawHeader);
      headerBuilder.put(splitHeader.get(0), splitHeader.get(1));
    }
    return headerBuilder.build();
  }

  private ImmutableSet<String> getHttpCacheNames() {
    ImmutableList<String> httpCacheNames = buckConfig.getListWithoutComments(
        CACHE_SECTION_NAME,
        HTTP_CACHE_NAMES_FIELD_NAME);
    return ImmutableSet.copyOf(httpCacheNames);
  }

  private String getCacheErrorFormatMessage(String section, String fieldName, String defaultValue) {
    return buckConfig.getValue(section, fieldName).orElse(defaultValue);
  }

  private HttpCacheEntry obtainEntryForName(Optional<String> cacheName) {
    final String section = Joiner.on('#').skipNulls().join(
        CACHE_SECTION_NAME,
        cacheName.orElse(null));

    HttpCacheEntry.Builder builder = HttpCacheEntry.builder();
    builder.setName(cacheName);
    builder.setUrl(buckConfig.getUrl(section, HTTP_URL_FIELD_NAME).orElse(DEFAULT_HTTP_URL));
    builder.setTimeoutSeconds(
        buckConfig.getLong(section, HTTP_TIMEOUT_SECONDS_FIELD_NAME).orElse(
            DEFAULT_HTTP_CACHE_TIMEOUT_SECONDS).intValue());
    builder.setReadHeaders(getCacheHeaders(section, HTTP_READ_HEADERS_FIELD_NAME));
    builder.setWriteHeaders(getCacheHeaders(section, HTTP_WRITE_HEADERS_FIELD_NAME));
    builder.setBlacklistedWifiSsids(
        buckConfig.getListWithoutComments(section, HTTP_BLACKLISTED_WIFI_SSIDS_FIELD_NAME));
    builder.setCacheReadMode(
        getCacheReadMode(section, HTTP_MODE_FIELD_NAME, DEFAULT_HTTP_CACHE_MODE));
    builder.setErrorMessageFormat(
        getCacheErrorFormatMessage(
            section,
            HTTP_CACHE_ERROR_MESSAGE_NAME,
            DEFAULT_HTTP_CACHE_ERROR_MESSAGE));
    builder.setMaxStoreSize(buckConfig.getLong(section, HTTP_MAX_STORE_SIZE));
    return builder.build();
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
    http,
    thrift_over_http,
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
  abstract static class AbstractDirCacheEntry {
    public abstract Path getCacheDir();
    public abstract Optional<Long> getMaxSizeBytes();
    public abstract CacheReadMode getCacheReadMode();
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractHttpCacheEntry {
    public abstract Optional<String> getName();
    public abstract URI getUrl();
    public abstract int getTimeoutSeconds();
    public abstract ImmutableMap<String, String> getReadHeaders();
    public abstract ImmutableMap<String, String> getWriteHeaders();
    public abstract CacheReadMode getCacheReadMode();
    protected abstract ImmutableSet<String> getBlacklistedWifiSsids();
    public abstract String getErrorMessageFormat();
    public abstract Optional<Long> getMaxStoreSize();

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
