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

package com.facebook.buck.artifact_cache.config;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.resources.ResourcesConfig;
import com.facebook.buck.slb.SlbBuckConfig;
import com.facebook.buck.util.unit.SizeUnit;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Represents configuration specific to the {@link com.facebook.buck.artifact_cache.ArtifactCache}.
 */
public class ArtifactCacheBuckConfig implements ConfigView<BuckConfig> {
  private static final String CACHE_SECTION_NAME = "cache";

  private static final String DEFAULT_DIR_CACHE_MODE = CacheReadMode.READWRITE.name();
  private static final String DEFAULT_SQLITE_CACHE_MODE = CacheReadMode.READWRITE.name();

  // Names of the fields in a [cache*] section that describe a single HTTP cache.
  private static final String HTTP_URL_FIELD_NAME = "http_url";
  private static final String HTTP_BLACKLISTED_WIFI_SSIDS_FIELD_NAME = "blacklisted_wifi_ssids";
  private static final String HTTP_MODE_FIELD_NAME = "http_mode";
  private static final String HTTP_TIMEOUT_SECONDS_FIELD_NAME = "http_timeout_seconds";
  private static final String HTTP_CONNECT_TIMEOUT_SECONDS_FIELD_NAME =
      "http_connect_timeout_seconds";
  private static final String HTTP_READ_TIMEOUT_SECONDS_FIELD_NAME = "http_read_timeout_seconds";
  private static final String HTTP_WRITE_TIMEOUT_SECONDS_FIELD_NAME = "http_write_timeout_seconds";
  private static final String HTTP_READ_HEADERS_FIELD_NAME = "http_read_headers";
  private static final String HTTP_WRITE_HEADERS_FIELD_NAME = "http_write_headers";
  private static final String HTTP_CACHE_ERROR_MESSAGE_NAME = "http_error_message_format";
  private static final String HTTP_CACHE_ERROR_MESSAGE_LIMIT_NAME = "http_error_message_limit";
  private static final String HTTP_MAX_STORE_SIZE = "http_max_store_size";
  private static final String HTTP_THREAD_POOL_SIZE = "http_thread_pool_size";
  private static final String HTTP_THREAD_POOL_KEEP_ALIVE_DURATION_MILLIS =
      "http_thread_pool_keep_alive_duration_millis";
  private static final ImmutableSet<String> HTTP_CACHE_DESCRIPTION_FIELDS =
      ImmutableSet.of(
          HTTP_URL_FIELD_NAME,
          HTTP_BLACKLISTED_WIFI_SSIDS_FIELD_NAME,
          HTTP_MODE_FIELD_NAME,
          HTTP_TIMEOUT_SECONDS_FIELD_NAME,
          HTTP_CONNECT_TIMEOUT_SECONDS_FIELD_NAME,
          HTTP_READ_TIMEOUT_SECONDS_FIELD_NAME,
          HTTP_WRITE_TIMEOUT_SECONDS_FIELD_NAME,
          HTTP_READ_HEADERS_FIELD_NAME,
          HTTP_WRITE_HEADERS_FIELD_NAME,
          HTTP_CACHE_ERROR_MESSAGE_NAME,
          HTTP_CACHE_ERROR_MESSAGE_LIMIT_NAME,
          HTTP_MAX_STORE_SIZE);
  private static final String HTTP_MAX_FETCH_RETRIES = "http_max_fetch_retries";
  private static final String HTTP_MAX_STORE_ATTEMPTS = "http_max_store_attempts";
  private static final String HTTP_STORE_RETRY_INTERVAL_MILLIS = "http_store_retry_interval_millis";

  private static final String DIR_FIELD = "dir";
  private static final String DIR_MODE_FIELD = "dir_mode";
  private static final String DIR_MAX_SIZE_FIELD = "dir_max_size";
  private static final String DIR_CACHE_NAMES_FIELD_NAME = "dir_cache_names";
  private static final ImmutableSet<String> DIR_CACHE_DESCRIPTION_FIELDS =
      ImmutableSet.of(DIR_FIELD, DIR_MODE_FIELD, DIR_MAX_SIZE_FIELD);

  private static final URI DEFAULT_HTTP_URL = URI.create("http://localhost:8080/");
  private static final String DEFAULT_HTTP_CACHE_MODE = CacheReadMode.READWRITE.name();
  private static final long DEFAULT_HTTP_CACHE_TIMEOUT_SECONDS = 3L;
  private static final String DEFAULT_HTTP_MAX_CONCURRENT_WRITES = "1";
  private static final String DEFAULT_HTTP_WRITE_SHUTDOWN_TIMEOUT_SECONDS = "1800"; // 30 minutes
  private static final String DEFAULT_HTTP_CACHE_ERROR_MESSAGE =
      "{cache_name} cache encountered an error: {error_message}";
  // After how many errors to show the HTTP_CACHE_ERROR_MESSAGE_NAME.
  private static final int DEFAULT_HTTP_CACHE_ERROR_MESSAGE_LIMIT_NAME = 100;
  private static final int DEFAULT_HTTP_MAX_FETCH_RETRIES = 2;
  private static final int DEFAULT_HTTP_MAX_STORE_ATTEMPTS = 1; // Make a single request, no retries
  private static final long DEFAULT_HTTP_STORE_RETRY_INTERVAL = 1000;

  private static final String SQLITE_MODE_FIELD = "sqlite_mode";
  private static final String SQLITE_MAX_SIZE_FIELD = "sqlite_max_size";
  private static final String SQLITE_MAX_INLINED_SIZE_FIELD = "sqlite_inlined_size";
  private static final String SQLITE_CACHE_NAMES_FIELD_NAME = "sqlite_cache_names";

  private static final String SERVED_CACHE_ENABLED_FIELD_NAME = "serve_local_cache";
  private static final String DEFAULT_SERVED_CACHE_MODE = CacheReadMode.READONLY.name();
  private static final String SERVED_CACHE_READ_MODE_FIELD_NAME = "served_local_cache_mode";
  private static final String LOAD_BALANCING_TYPE = "load_balancing_type";
  private static final LoadBalancingType DEFAULT_LOAD_BALANCING_TYPE =
      LoadBalancingType.SINGLE_SERVER;
  private static final long DEFAULT_HTTP_THREAD_POOL_SIZE = 5;
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
  public static final String MULTI_FETCH = "multi_fetch";
  private static final String MULTI_FETCH_LIMIT = "multi_fetch_limit";
  private static final int DEFAULT_MULTI_FETCH_LIMIT = 100;

  private static final String DOWNLOAD_HEAVY_BUILD_CACHE_FETCH_THREADS =
      "download_heavy_build_http_cache_fetch_threads";
  private static final int DEFAULT_DOWNLOAD_HEAVY_BUILD_CACHE_FETCH_THREADS = 20;

  private final BuckConfig buckConfig;
  private final SlbBuckConfig slbConfig;

  public static ArtifactCacheBuckConfig of(BuckConfig delegate) {
    return new ArtifactCacheBuckConfig(delegate);
  }

  public ArtifactCacheBuckConfig(BuckConfig buckConfig) {
    this.buckConfig = buckConfig;
    this.slbConfig = new SlbBuckConfig(buckConfig, CACHE_SECTION_NAME);
  }

  public MultiFetchType getMultiFetchType() {
    return buckConfig
        .getEnum(CACHE_SECTION_NAME, MULTI_FETCH, MultiFetchType.class)
        .orElse(MultiFetchType.DEFAULT);
  }

  @Override
  public BuckConfig getDelegate() {
    return buckConfig;
  }

  public int getDownloadHeavyBuildHttpFetchConcurrency() {
    return Math.min(
        buckConfig.getView(ResourcesConfig.class).getMaximumResourceAmounts().getNetworkIO(),
        getDownloadHeavyBuildHttpCacheFetchThreads());
  }

  public int getHttpFetchConcurrency() {
    return (int)
        Math.min(
            buckConfig.getView(ResourcesConfig.class).getMaximumResourceAmounts().getNetworkIO(),
            getThreadPoolSize());
  }

  public int getMultiFetchLimit() {
    return buckConfig
        .getInteger(CACHE_SECTION_NAME, MULTI_FETCH_LIMIT)
        .orElse(DEFAULT_MULTI_FETCH_LIMIT);
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
    return buckConfig
        .getEnum(CACHE_SECTION_NAME, LOAD_BALANCING_TYPE, LoadBalancingType.class)
        .orElse(DEFAULT_LOAD_BALANCING_TYPE);
  }

  public int getHttpMaxConcurrentWrites() {
    return Integer.parseInt(
        buckConfig
            .getValue(CACHE_SECTION_NAME, "http_max_concurrent_writes")
            .orElse(DEFAULT_HTTP_MAX_CONCURRENT_WRITES));
  }

  public int getHttpWriterShutdownTimeout() {
    return Integer.parseInt(
        buckConfig
            .getValue(CACHE_SECTION_NAME, "http_writer_shutdown_timeout_seconds")
            .orElse(DEFAULT_HTTP_WRITE_SHUTDOWN_TIMEOUT_SECONDS));
  }

  public int getMaxFetchRetries() {
    return buckConfig
        .getInteger(CACHE_SECTION_NAME, HTTP_MAX_FETCH_RETRIES)
        .orElse(DEFAULT_HTTP_MAX_FETCH_RETRIES);
  }

  public int getMaxStoreAttempts() {
    return buckConfig
        .getInteger(CACHE_SECTION_NAME, HTTP_MAX_STORE_ATTEMPTS)
        .orElse(DEFAULT_HTTP_MAX_STORE_ATTEMPTS);
  }

  public int getErrorMessageLimit() {
    return buckConfig
        .getInteger(CACHE_SECTION_NAME, HTTP_CACHE_ERROR_MESSAGE_LIMIT_NAME)
        .orElse(DEFAULT_HTTP_CACHE_ERROR_MESSAGE_LIMIT_NAME);
  }

  public long getStoreRetryIntervalMillis() {
    return buckConfig
        .getLong(CACHE_SECTION_NAME, HTTP_STORE_RETRY_INTERVAL_MILLIS)
        .orElse(DEFAULT_HTTP_STORE_RETRY_INTERVAL);
  }

  public boolean hasAtLeastOneWriteableRemoteCache() {
    return getHttpCacheEntries()
        .stream()
        .anyMatch(entry -> entry.getCacheReadMode().equals(CacheReadMode.READWRITE));
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
    return getArtifactCacheModesRaw()
        .stream()
        .map(
            input -> {
              try {
                return ArtifactCacheMode.valueOf(input);
              } catch (IllegalArgumentException e) {
                throw new HumanReadableException(
                    "Unusable %s.mode: '%s'", CACHE_SECTION_NAME, input);
              }
            })
        .collect(ImmutableSet.toImmutableSet());
  }

  public Optional<DirCacheEntry> getServedLocalCache() {
    if (!getServingLocalCacheEnabled()) {
      return Optional.empty();
    }
    return Optional.of(
        obtainDirEntryForName(Optional.empty()).withCacheReadMode(getServedLocalCacheReadMode()));
  }

  public ArtifactCacheEntries getCacheEntries() {
    ImmutableSet<DirCacheEntry> dirCacheEntries = getDirCacheEntries();
    ImmutableSet<HttpCacheEntry> httpCacheEntries = getHttpCacheEntries();
    ImmutableSet<SQLiteCacheEntry> sqliteCacheEntries = getSQLiteCacheEntries();
    Predicate<DirCacheEntry> isDirCacheEntryWriteable =
        dirCache -> dirCache.getCacheReadMode().isWritable();

    // Enforce some sanity checks on the config:
    //  - we don't want multiple writeable dir caches pointing to the same directory
    dirCacheEntries
        .stream()
        .filter(isDirCacheEntryWriteable)
        .collect(Collectors.groupingBy(DirCacheEntry::getCacheDir))
        .forEach(
            (path, dirCachesPerPath) -> {
              if (dirCachesPerPath.size() > 1) {
                throw new HumanReadableException(
                    "Multiple writeable dir caches defined for path %s. This is not supported.",
                    path);
              }
            });

    return ArtifactCacheEntries.builder()
        .setDirCacheEntries(dirCacheEntries)
        .setHttpCacheEntries(httpCacheEntries)
        .setSQLiteCacheEntries(sqliteCacheEntries)
        .build();
  }

  private ImmutableSet<HttpCacheEntry> getHttpCacheEntries() {
    if (getArtifactCacheModes().contains(ArtifactCacheMode.http)
        || legacyHttpCacheConfigurationFieldsPresent()) {
      return ImmutableSet.of(obtainHttpEntry());
    }
    return ImmutableSet.of();
  }

  private ImmutableSet<DirCacheEntry> getDirCacheEntries() {
    ImmutableSet.Builder<DirCacheEntry> result = ImmutableSet.builder();

    ImmutableList<String> names = getDirCacheNames();
    boolean implicitLegacyCache =
        names.isEmpty() && getArtifactCacheModes().contains(ArtifactCacheMode.dir);
    if (implicitLegacyCache || legacyDirCacheConfigurationFieldsPresent()) {
      result.add(obtainDirEntryForName(Optional.empty()));
    }

    for (String cacheName : names) {
      result.add(obtainDirEntryForName(Optional.of(cacheName)));
    }

    return result.build();
  }

  private ImmutableSet<SQLiteCacheEntry> getSQLiteCacheEntries() {
    return getSQLiteCacheNames()
        .parallelStream()
        .map(this::obtainSQLiteEntryForName)
        .collect(ImmutableSet.toImmutableSet());
  }

  // It's important that this number is greater than the `-j` parallelism,
  // as if it's too small, we'll overflow the reusable connection pool and
  // start spamming new connections.  While this isn't the best location,
  // the other current option is setting this wherever we construct a `Build`
  // object and have access to the `-j` argument.  However, since that is
  // created in several places leave it here for now.
  public long getThreadPoolSize() {
    return buckConfig
        .getLong(CACHE_SECTION_NAME, HTTP_THREAD_POOL_SIZE)
        .orElse(DEFAULT_HTTP_THREAD_POOL_SIZE);
  }

  public long getThreadPoolKeepAliveDurationMillis() {
    return buckConfig
        .getLong(CACHE_SECTION_NAME, HTTP_THREAD_POOL_KEEP_ALIVE_DURATION_MILLIS)
        .orElse(DEFAULT_HTTP_THREAD_POOL_KEEP_ALIVE_DURATION_MILLIS);
  }

  public boolean getTwoLevelCachingEnabled() {
    return buckConfig.getBooleanValue(
        CACHE_SECTION_NAME, TWO_LEVEL_CACHING_ENABLED_FIELD_NAME, false);
  }

  public long getTwoLevelCachingMinimumSize() {
    return buckConfig
        .getValue(CACHE_SECTION_NAME, TWO_LEVEL_CACHING_MIN_SIZE_FIELD_NAME)
        .map(Optional::of)
        .orElse(buckConfig.getValue(CACHE_SECTION_NAME, TWO_LEVEL_CACHING_THRESHOLD_FIELD_NAME))
        .map(SizeUnit::parseBytes)
        .orElse(TWO_LEVEL_CACHING_MIN_SIZE_DEFAULT);
  }

  public Optional<Long> getTwoLevelCachingMaximumSize() {
    return buckConfig
        .getValue(CACHE_SECTION_NAME, TWO_LEVEL_CACHING_MAX_SIZE_FIELD_NAME)
        .map(SizeUnit::parseBytes);
  }

  /**
   * Gets the path to a PEM encoded X509 certifiate to use as the TLS client certificate for HTTP
   * cache requests
   *
   * <p>Both the key and certificate must be set for client TLS certificates to be used
   */
  public Optional<Path> getClientTlsCertificate() {
    return buckConfig.getValue("cache", "http_client_tls_cert").map(Paths::get);
  }

  /**
   * Gets the path to a PEM encoded PCKS#8 key to use as the TLS client key for HTTP cache requests.
   * This may be a file that contains both the private key and the certificate if both objects are
   * newline delimited.
   *
   * <p>Both the key and certificate must be set for client TLS certificates to be used
   */
  public Optional<Path> getClientTlsKey() {
    return buckConfig.getValue("cache", "http_client_tls_key").map(Paths::get);
  }

  private boolean getServingLocalCacheEnabled() {
    return buckConfig.getBooleanValue(CACHE_SECTION_NAME, SERVED_CACHE_ENABLED_FIELD_NAME, false);
  }

  private CacheReadMode getServedLocalCacheReadMode() {
    return getCacheReadMode(
        CACHE_SECTION_NAME, SERVED_CACHE_READ_MODE_FIELD_NAME, DEFAULT_SERVED_CACHE_MODE);
  }

  private CacheReadMode getCacheReadMode(String section, String fieldName, String defaultValue) {
    String cacheMode = buckConfig.getValue(section, fieldName).orElse(defaultValue);
    CacheReadMode result;
    try {
      result = CacheReadMode.valueOf(cacheMode.toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new HumanReadableException("Unusable cache.%s: '%s'", fieldName, cacheMode);
    }
    return result;
  }

  private ImmutableMap<String, String> getCacheHeaders(String section, String fieldName) {
    ImmutableMap.Builder<String, String> headerBuilder = ImmutableMap.builder();
    ImmutableList<String> rawHeaders = buckConfig.getListWithoutComments(section, fieldName, ';');
    for (String rawHeader : rawHeaders) {
      List<String> splitHeader =
          Splitter.on(':').omitEmptyStrings().trimResults().splitToList(rawHeader);
      headerBuilder.put(splitHeader.get(0), splitHeader.get(1));
    }
    return headerBuilder.build();
  }

  private ImmutableList<String> getDirCacheNames() {
    return buckConfig.getListWithoutComments(CACHE_SECTION_NAME, DIR_CACHE_NAMES_FIELD_NAME);
  }

  private ImmutableList<String> getSQLiteCacheNames() {
    return buckConfig.getListWithoutComments(CACHE_SECTION_NAME, SQLITE_CACHE_NAMES_FIELD_NAME);
  }

  private String getCacheErrorFormatMessage(String section, String fieldName, String defaultValue) {
    return buckConfig.getValue(section, fieldName).orElse(defaultValue);
  }

  private DirCacheEntry obtainDirEntryForName(Optional<String> cacheName) {
    String section = Joiner.on('#').skipNulls().join(CACHE_SECTION_NAME, cacheName.orElse(null));

    CacheReadMode readMode = getCacheReadMode(section, DIR_MODE_FIELD, DEFAULT_DIR_CACHE_MODE);

    String cacheDir = buckConfig.getLocalCacheDirectory(section);
    Path pathToCacheDir =
        buckConfig.resolvePathThatMayBeOutsideTheProjectFilesystem(Paths.get(cacheDir));
    Objects.requireNonNull(pathToCacheDir);

    Optional<Long> maxSizeBytes =
        buckConfig.getValue(section, DIR_MAX_SIZE_FIELD).map(SizeUnit::parseBytes);

    return DirCacheEntry.builder()
        .setName(cacheName)
        .setCacheDir(pathToCacheDir)
        .setCacheReadMode(readMode)
        .setMaxSizeBytes(maxSizeBytes)
        .build();
  }

  private HttpCacheEntry obtainHttpEntry() {
    HttpCacheEntry.Builder builder = HttpCacheEntry.builder();
    builder.setUrl(
        buckConfig.getUrl(CACHE_SECTION_NAME, HTTP_URL_FIELD_NAME).orElse(DEFAULT_HTTP_URL));
    long defaultTimeoutValue =
        buckConfig
            .getLong(CACHE_SECTION_NAME, HTTP_TIMEOUT_SECONDS_FIELD_NAME)
            .orElse(DEFAULT_HTTP_CACHE_TIMEOUT_SECONDS);
    builder.setConnectTimeoutSeconds(
        buckConfig
            .getLong(CACHE_SECTION_NAME, HTTP_CONNECT_TIMEOUT_SECONDS_FIELD_NAME)
            .orElse(defaultTimeoutValue)
            .intValue());
    builder.setReadTimeoutSeconds(
        buckConfig
            .getLong(CACHE_SECTION_NAME, HTTP_READ_TIMEOUT_SECONDS_FIELD_NAME)
            .orElse(defaultTimeoutValue)
            .intValue());
    builder.setWriteTimeoutSeconds(
        buckConfig
            .getLong(CACHE_SECTION_NAME, HTTP_WRITE_TIMEOUT_SECONDS_FIELD_NAME)
            .orElse(defaultTimeoutValue)
            .intValue());
    builder.setReadHeaders(getCacheHeaders(CACHE_SECTION_NAME, HTTP_READ_HEADERS_FIELD_NAME));
    builder.setWriteHeaders(getCacheHeaders(CACHE_SECTION_NAME, HTTP_WRITE_HEADERS_FIELD_NAME));
    builder.setBlacklistedWifiSsids(getBlacklistedWifiSsids());
    builder.setCacheReadMode(
        getCacheReadMode(CACHE_SECTION_NAME, HTTP_MODE_FIELD_NAME, DEFAULT_HTTP_CACHE_MODE));
    builder.setErrorMessageFormat(
        getCacheErrorFormatMessage(
            CACHE_SECTION_NAME, HTTP_CACHE_ERROR_MESSAGE_NAME, DEFAULT_HTTP_CACHE_ERROR_MESSAGE));
    builder.setErrorMessageLimit(getErrorMessageLimit());
    builder.setMaxStoreSize(buckConfig.getLong(CACHE_SECTION_NAME, HTTP_MAX_STORE_SIZE));

    return builder.build();
  }

  private SQLiteCacheEntry obtainSQLiteEntryForName(String cacheName) {
    String section = String.join("#", CACHE_SECTION_NAME, cacheName);

    CacheReadMode readMode =
        getCacheReadMode(section, SQLITE_MODE_FIELD, DEFAULT_SQLITE_CACHE_MODE);

    String cacheDir = buckConfig.getLocalCacheDirectory(section);
    Path pathToCacheDir =
        buckConfig.resolvePathThatMayBeOutsideTheProjectFilesystem(Paths.get(cacheDir));

    Optional<Long> maxSizeBytes =
        buckConfig.getValue(section, SQLITE_MAX_SIZE_FIELD).map(SizeUnit::parseBytes);

    Optional<Long> maxInlinedSizeBytes =
        buckConfig.getValue(section, SQLITE_MAX_INLINED_SIZE_FIELD).map(SizeUnit::parseBytes);

    return SQLiteCacheEntry.builder()
        .setName(cacheName)
        .setCacheDir(pathToCacheDir)
        .setCacheReadMode(readMode)
        .setMaxSizeBytes(maxSizeBytes)
        .setMaxInlinedSizeBytes(maxInlinedSizeBytes)
        .build();
  }

  public ImmutableSet<String> getBlacklistedWifiSsids() {
    return ImmutableSet.copyOf(
        buckConfig.getListWithoutComments(
            CACHE_SECTION_NAME, HTTP_BLACKLISTED_WIFI_SSIDS_FIELD_NAME));
  }

  private boolean legacyHttpCacheConfigurationFieldsPresent() {
    for (String field : HTTP_CACHE_DESCRIPTION_FIELDS) {
      if (buckConfig.getValue(CACHE_SECTION_NAME, field).isPresent()) {
        return true;
      }
    }
    return false;
  }

  private boolean legacyDirCacheConfigurationFieldsPresent() {
    for (String field : DIR_CACHE_DESCRIPTION_FIELDS) {
      if (buckConfig.getValue(CACHE_SECTION_NAME, field).isPresent()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Number of cache fetch threads to be used by download heavy builds, such as the synchronized
   * build phase of Stampede, which almost entirely consists of cache fetches.
   *
   * @return
   */
  private int getDownloadHeavyBuildHttpCacheFetchThreads() {
    return buckConfig
        .getInteger(CACHE_SECTION_NAME, DOWNLOAD_HEAVY_BUILD_CACHE_FETCH_THREADS)
        .orElse(DEFAULT_DOWNLOAD_HEAVY_BUILD_CACHE_FETCH_THREADS);
  }
}
