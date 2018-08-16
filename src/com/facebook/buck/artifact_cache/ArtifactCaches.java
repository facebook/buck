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
package com.facebook.buck.artifact_cache;

import static com.facebook.buck.artifact_cache.config.ArtifactCacheMode.CacheType.local;
import static com.facebook.buck.artifact_cache.config.ArtifactCacheMode.CacheType.remote;

import com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig;
import com.facebook.buck.artifact_cache.config.ArtifactCacheEntries;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode.CacheType;
import com.facebook.buck.artifact_cache.config.DirCacheEntry;
import com.facebook.buck.artifact_cache.config.HttpCacheEntry;
import com.facebook.buck.artifact_cache.config.MultiFetchType;
import com.facebook.buck.artifact_cache.config.SQLiteCacheEntry;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.ExperimentEvent;
import com.facebook.buck.event.NetworkEvent.BytesReceivedEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.slb.HttpLoadBalancer;
import com.facebook.buck.slb.HttpService;
import com.facebook.buck.slb.LoadBalancedService;
import com.facebook.buck.slb.RetryingHttpService;
import com.facebook.buck.slb.SingleUriService;
import com.facebook.buck.util.randomizedtrial.RandomizedTrial;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;

/** Creates instances of the {@link ArtifactCache}. */
public class ArtifactCaches implements ArtifactCacheFactory, AutoCloseable {

  private static final Logger LOG = Logger.get(ArtifactCaches.class);

  private final ArtifactCacheBuckConfig buckConfig;
  private final BuckEventBus buckEventBus;
  private final ProjectFilesystem projectFilesystem;
  private final Optional<String> wifiSsid;
  private final ListeningExecutorService httpWriteExecutorService;
  private final ListeningExecutorService httpFetchExecutorService;
  private final ListeningExecutorService downloadHeavyBuildHttpFetchExecutorService;
  private final ExecutorService artifactCacheCloseExecutorService;
  private List<ArtifactCache> artifactCaches = new ArrayList<>();

  @Override
  public void close() {
    // close all artifact caches asynchronously using a separate executor
    for (ArtifactCache artifactCache : artifactCaches) {
      artifactCacheCloseExecutorService.submit(
          () -> {
            try {
              artifactCache.close();
            } catch (Exception e) {
              LOG.warn(e, "Exception when performing async close of %s.", artifactCache);
            }
          });
    }

    buckEventBus.post(HttpArtifactCacheEvent.newShutdownEvent());
  }

  private interface NetworkCacheFactory {
    ArtifactCache newInstance(NetworkCacheArgs args);
  }

  /**
   * Creates a new instance of the cache factory for use during a build.
   *
   * @param buckConfig describes what kind of cache to create
   * @param buckEventBus event bus
   * @param projectFilesystem filesystem to store files on
   * @param wifiSsid current WiFi ssid to decide if we want the http cache or not
   * @param artifactCacheCloseExecutorService executor (like thread pool) that will process closing
   *     tasks of all artifact caches created by this factory
   */
  public ArtifactCaches(
      ArtifactCacheBuckConfig buckConfig,
      BuckEventBus buckEventBus,
      ProjectFilesystem projectFilesystem,
      Optional<String> wifiSsid,
      ListeningExecutorService httpWriteExecutorService,
      ListeningExecutorService httpFetchExecutorService,
      ListeningExecutorService downloadHeavyBuildHttpFetchExecutorService,
      ExecutorService artifactCacheCloseExecutorService) {
    this.buckConfig = buckConfig;
    this.buckEventBus = buckEventBus;
    this.projectFilesystem = projectFilesystem;
    this.wifiSsid = wifiSsid;
    this.httpWriteExecutorService = httpWriteExecutorService;
    this.httpFetchExecutorService = httpFetchExecutorService;
    this.downloadHeavyBuildHttpFetchExecutorService = downloadHeavyBuildHttpFetchExecutorService;
    this.artifactCacheCloseExecutorService = artifactCacheCloseExecutorService;
  }

  private static Request.Builder addHeadersToBuilder(
      Request.Builder builder, ImmutableMap<String, String> headers) {
    ImmutableSet<Map.Entry<String, String>> entries = headers.entrySet();
    for (Map.Entry<String, String> header : entries) {
      builder.addHeader(header.getKey(), header.getValue());
    }
    return builder;
  }

  @Override
  public ArtifactCache newInstance() {
    return newInstance(false, false);
  }

  /**
   * Creates a new instance of the cache for use during a build.
   *
   * @param distributedBuildModeEnabled true if this is a distributed build
   * @param isDownloadHeavyBuild true if creating cache connector for download heavy build
   * @return ArtifactCache instance
   */
  @Override
  public ArtifactCache newInstance(
      boolean distributedBuildModeEnabled, boolean isDownloadHeavyBuild) {
    return newInstanceInternal(
        ImmutableSet.of(), distributedBuildModeEnabled, isDownloadHeavyBuild);
  }

  @Override
  public ArtifactCache remoteOnlyInstance(
      boolean distributedBuildModeEnabled, boolean isDownloadHeavyBuild) {
    return newInstanceInternal(
        ImmutableSet.of(local), distributedBuildModeEnabled, isDownloadHeavyBuild);
  }

  @Override
  public ArtifactCache localOnlyInstance(
      boolean distributedBuildModeEnabled, boolean isDownloadHeavyBuild) {
    return newInstanceInternal(
        ImmutableSet.of(remote), distributedBuildModeEnabled, isDownloadHeavyBuild);
  }

  private ArtifactCache newInstanceInternal(
      ImmutableSet<CacheType> cacheTypeBlacklist,
      boolean distributedBuildModeEnabled,
      boolean isDownloadHeavyBuild) {
    ArtifactCacheConnectEvent.Started started = ArtifactCacheConnectEvent.started();
    buckEventBus.post(started);

    ArtifactCache artifactCache =
        newInstanceInternal(
            buckConfig,
            buckEventBus,
            projectFilesystem,
            wifiSsid,
            httpWriteExecutorService,
            isDownloadHeavyBuild
                ? downloadHeavyBuildHttpFetchExecutorService
                : httpFetchExecutorService,
            cacheTypeBlacklist,
            distributedBuildModeEnabled);

    artifactCaches.add(artifactCache);

    buckEventBus.post(ArtifactCacheConnectEvent.finished(started));
    return artifactCache;
  }

  @Override
  public ArtifactCacheFactory cloneWith(BuckConfig newConfig) {
    return new ArtifactCaches(
        new ArtifactCacheBuckConfig(newConfig),
        buckEventBus,
        projectFilesystem,
        wifiSsid,
        httpWriteExecutorService,
        httpFetchExecutorService,
        downloadHeavyBuildHttpFetchExecutorService,
        artifactCacheCloseExecutorService);
  }

  /**
   * Creates a new instance of the cache to be used to serve the dircache from the WebServer.
   *
   * @param buckConfig describes how to configure te cache
   * @param projectFilesystem filesystem to store files on
   * @return a cache
   */
  public static Optional<ArtifactCache> newServedCache(
      ArtifactCacheBuckConfig buckConfig, ProjectFilesystem projectFilesystem) {
    return buckConfig
        .getServedLocalCache()
        .map(input -> createDirArtifactCache(Optional.empty(), input, projectFilesystem));
  }

  private static ArtifactCache newInstanceInternal(
      ArtifactCacheBuckConfig buckConfig,
      BuckEventBus buckEventBus,
      ProjectFilesystem projectFilesystem,
      Optional<String> wifiSsid,
      ListeningExecutorService httpWriteExecutorService,
      ListeningExecutorService httpFetchExecutorService,
      ImmutableSet<CacheType> cacheTypeBlacklist,
      boolean distributedBuildModeEnabled) {
    ImmutableSet<ArtifactCacheMode> modes = buckConfig.getArtifactCacheModes();
    if (modes.isEmpty()) {
      return new NoopArtifactCache();
    }
    ArtifactCacheEntries cacheEntries = buckConfig.getCacheEntries();
    ImmutableList.Builder<ArtifactCache> builder = ImmutableList.builder();
    for (ArtifactCacheMode mode : modes) {
      if (cacheTypeBlacklist.contains(mode.getCacheType())) {
        continue;
      }

      switch (mode) {
        case unknown:
          break;
        case dir:
          initializeDirCaches(cacheEntries, buckEventBus, projectFilesystem, builder);
          break;
        case http:
          initializeDistributedCaches(
              cacheEntries,
              buckConfig,
              buckEventBus,
              projectFilesystem,
              wifiSsid,
              httpWriteExecutorService,
              httpFetchExecutorService,
              builder,
              HttpArtifactCache::new,
              mode);
          break;
        case sqlite:
          initializeSQLiteCaches(cacheEntries, buckEventBus, projectFilesystem, builder);
          break;
        case thrift_over_http:
          Preconditions.checkArgument(
              buckConfig.getHybridThriftEndpoint().isPresent(),
              "Hybrid thrift endpoint path is mandatory for the ThriftArtifactCache.");
          initializeDistributedCaches(
              cacheEntries,
              buckConfig,
              buckEventBus,
              projectFilesystem,
              wifiSsid,
              httpWriteExecutorService,
              httpFetchExecutorService,
              builder,
              (args) ->
                  new ThriftArtifactCache(
                      args,
                      buckConfig.getHybridThriftEndpoint().get(),
                      distributedBuildModeEnabled,
                      buckEventBus.getBuildId(),
                      getMultiFetchLimit(buckConfig, buckEventBus),
                      buckConfig.getHttpFetchConcurrency()),
              mode);
          break;
      }
    }
    ImmutableList<ArtifactCache> artifactCaches = builder.build();
    ArtifactCache result;

    if (artifactCaches.size() == 1) {
      // Don't bother wrapping a single artifact cache
      result = artifactCaches.get(0);
    } else {
      result = new MultiArtifactCache(artifactCaches);
    }

    // Always support reading two-level cache stores (in case we performed any in the past).
    result =
        new TwoLevelArtifactCacheDecorator(
            result,
            projectFilesystem,
            buckEventBus,
            buckConfig.getTwoLevelCachingEnabled(),
            buckConfig.getTwoLevelCachingMinimumSize(),
            buckConfig.getTwoLevelCachingMaximumSize());

    return result;
  }

  private static void initializeDirCaches(
      ArtifactCacheEntries artifactCacheEntries,
      BuckEventBus buckEventBus,
      ProjectFilesystem projectFilesystem,
      ImmutableList.Builder<ArtifactCache> builder) {
    for (DirCacheEntry cacheEntry : artifactCacheEntries.getDirCacheEntries()) {
      builder.add(
          createDirArtifactCache(Optional.ofNullable(buckEventBus), cacheEntry, projectFilesystem));
    }
  }

  private static void initializeDistributedCaches(
      ArtifactCacheEntries artifactCacheEntries,
      ArtifactCacheBuckConfig buckConfig,
      BuckEventBus buckEventBus,
      ProjectFilesystem projectFilesystem,
      Optional<String> wifiSsid,
      ListeningExecutorService httpWriteExecutorService,
      ListeningExecutorService httpFetchExecutorService,
      ImmutableList.Builder<ArtifactCache> builder,
      NetworkCacheFactory factory,
      ArtifactCacheMode cacheMode) {
    for (HttpCacheEntry cacheEntry : artifactCacheEntries.getHttpCacheEntries()) {
      if (!cacheEntry.isWifiUsableForDistributedCache(wifiSsid)) {
        buckEventBus.post(
            ConsoleEvent.warning(
                String.format(
                    "Remote Buck cache is disabled because the WiFi (%s) is not usable.",
                    wifiSsid)));
        LOG.warn("HTTP cache is disabled because WiFi is not usable.");
        continue;
      }

      builder.add(
          createRetryingArtifactCache(
              cacheEntry,
              buckConfig.getHostToReportToRemoteCacheServer(),
              buckEventBus,
              projectFilesystem,
              httpWriteExecutorService,
              httpFetchExecutorService,
              buckConfig,
              factory,
              cacheMode));
    }
  }

  private static void initializeSQLiteCaches(
      ArtifactCacheEntries artifactCacheEntries,
      BuckEventBus buckEventBus,
      ProjectFilesystem projectFilesystem,
      ImmutableList.Builder<ArtifactCache> builder) {
    artifactCacheEntries
        .getSQLiteCacheEntries()
        .forEach(
            cacheEntry ->
                builder.add(
                    createSQLiteArtifactCache(buckEventBus, cacheEntry, projectFilesystem)));
  }

  private static ArtifactCache createDirArtifactCache(
      Optional<BuckEventBus> buckEventBus,
      DirCacheEntry dirCacheConfig,
      ProjectFilesystem projectFilesystem) {
    Path cacheDir = dirCacheConfig.getCacheDir();
    try {
      DirArtifactCache dirArtifactCache =
          new DirArtifactCache(
              "dir",
              projectFilesystem,
              cacheDir,
              dirCacheConfig.getCacheReadMode(),
              dirCacheConfig.getMaxSizeBytes());

      if (!buckEventBus.isPresent()) {
        return dirArtifactCache;
      }

      return new LoggingArtifactCacheDecorator(
          buckEventBus.get(),
          dirArtifactCache,
          new DirArtifactCacheEvent.DirArtifactCacheEventFactory());

    } catch (IOException e) {
      throw new HumanReadableException(
          e, "Failure initializing artifact cache directory: %s", cacheDir);
    }
  }

  private static ArtifactCache createRetryingArtifactCache(
      HttpCacheEntry cacheDescription,
      String hostToReportToRemote,
      BuckEventBus buckEventBus,
      ProjectFilesystem projectFilesystem,
      ListeningExecutorService httpWriteExecutorService,
      ListeningExecutorService httpFetchExecutorService,
      ArtifactCacheBuckConfig config,
      NetworkCacheFactory factory,
      ArtifactCacheMode cacheMode) {
    ArtifactCache cache =
        createHttpArtifactCache(
            cacheDescription,
            hostToReportToRemote,
            buckEventBus,
            projectFilesystem,
            httpWriteExecutorService,
            httpFetchExecutorService,
            config,
            factory,
            cacheMode);
    return new RetryingCacheDecorator(cacheMode, cache, config.getMaxFetchRetries(), buckEventBus);
  }

  private static ArtifactCache createHttpArtifactCache(
      HttpCacheEntry cacheDescription,
      String hostToReportToRemote,
      BuckEventBus buckEventBus,
      ProjectFilesystem projectFilesystem,
      ListeningExecutorService httpWriteExecutorService,
      ListeningExecutorService httpFetchExecutorService,
      ArtifactCacheBuckConfig config,
      NetworkCacheFactory factory,
      ArtifactCacheMode cacheMode) {

    // Setup the default client to use.
    OkHttpClient.Builder storeClientBuilder = new OkHttpClient.Builder();
    storeClientBuilder
        .networkInterceptors()
        .add(
            chain ->
                chain.proceed(
                    chain
                        .request()
                        .newBuilder()
                        .addHeader(
                            "X-BuckCache-User",
                            stripNonAscii(System.getProperty("user.name", "<unknown>")))
                        .addHeader("X-BuckCache-Host", stripNonAscii(hostToReportToRemote))
                        .build()));
    int connectTimeoutSeconds = cacheDescription.getConnectTimeoutSeconds();
    int readTimeoutSeconds = cacheDescription.getReadTimeoutSeconds();
    int writeTimeoutSeconds = cacheDescription.getWriteTimeoutSeconds();
    setTimeouts(storeClientBuilder, connectTimeoutSeconds, readTimeoutSeconds, writeTimeoutSeconds);
    storeClientBuilder.connectionPool(
        new ConnectionPool(
            /* maxIdleConnections */ (int) config.getThreadPoolSize(),
            /* keepAliveDurationMs */ config.getThreadPoolKeepAliveDurationMillis(),
            TimeUnit.MILLISECONDS));

    // The artifact cache effectively only connects to a single host at a time. We should allow as
    // many concurrent connections to that host as we allow threads.
    Dispatcher dispatcher = new Dispatcher();
    dispatcher.setMaxRequestsPerHost((int) config.getThreadPoolSize());
    storeClientBuilder.dispatcher(dispatcher);

    ImmutableMap<String, String> readHeaders = cacheDescription.getReadHeaders();
    ImmutableMap<String, String> writeHeaders = cacheDescription.getWriteHeaders();

    // If write headers are specified, add them to every default client request.
    if (!writeHeaders.isEmpty()) {
      storeClientBuilder
          .networkInterceptors()
          .add(
              chain ->
                  chain.proceed(
                      addHeadersToBuilder(chain.request().newBuilder(), writeHeaders).build()));
    }

    OkHttpClient storeClient = storeClientBuilder.build();

    // For fetches, use a client with a read timeout.
    OkHttpClient.Builder fetchClientBuilder = storeClient.newBuilder();
    setTimeouts(fetchClientBuilder, connectTimeoutSeconds, readTimeoutSeconds, writeTimeoutSeconds);

    // If read headers are specified, add them to every read client request.
    if (!readHeaders.isEmpty()) {
      fetchClientBuilder
          .networkInterceptors()
          .add(
              chain ->
                  chain.proceed(
                      addHeadersToBuilder(chain.request().newBuilder(), readHeaders).build()));
    }

    fetchClientBuilder
        .networkInterceptors()
        .add(
            (chain -> {
              Response originalResponse = chain.proceed(chain.request());
              return originalResponse
                  .newBuilder()
                  .body(new ProgressResponseBody(originalResponse.body(), buckEventBus))
                  .build();
            }));
    OkHttpClient fetchClient = fetchClientBuilder.build();

    HttpService fetchService;
    HttpService storeService;
    switch (config.getLoadBalancingType()) {
      case CLIENT_SLB:
        HttpLoadBalancer clientSideSlb =
            config.getSlbConfig().createClientSideSlb(new DefaultClock(), buckEventBus);
        fetchService =
            new RetryingHttpService(
                buckEventBus,
                new LoadBalancedService(clientSideSlb, fetchClient, buckEventBus),
                "buck_cache_fetch_request_http_retries",
                config.getMaxFetchRetries());
        storeService =
            new RetryingHttpService(
                buckEventBus,
                new LoadBalancedService(clientSideSlb, storeClient, buckEventBus),
                "buck_cache_store_request_http_retries",
                config.getMaxStoreAttempts() - 1 /* maxNumberOfRetries */,
                config.getStoreRetryIntervalMillis());

        break;

      case SINGLE_SERVER:
        URI url = cacheDescription.getUrl();
        fetchService = new SingleUriService(url, fetchClient);
        storeService = new SingleUriService(url, storeClient);
        break;

      default:
        throw new IllegalArgumentException(
            "Unknown HttpLoadBalancer type: " + config.getLoadBalancingType());
    }

    return factory.newInstance(
        NetworkCacheArgs.builder()
            .setCacheName(cacheMode.name())
            .setCacheMode(cacheMode)
            .setRepository(config.getRepository())
            .setScheduleType(config.getScheduleType())
            .setFetchClient(fetchService)
            .setStoreClient(storeService)
            .setCacheReadMode(cacheDescription.getCacheReadMode())
            .setProjectFilesystem(projectFilesystem)
            .setBuckEventBus(buckEventBus)
            .setHttpWriteExecutorService(httpWriteExecutorService)
            .setHttpFetchExecutorService(httpFetchExecutorService)
            .setErrorTextTemplate(cacheDescription.getErrorMessageFormat())
            .setErrorTextLimit(cacheDescription.getErrorMessageLimit())
            .build());
  }

  private static ArtifactCache createSQLiteArtifactCache(
      BuckEventBus buckEventBus,
      SQLiteCacheEntry cacheConfig,
      ProjectFilesystem projectFilesystem) {
    Path cacheDir = cacheConfig.getCacheDir();
    try {
      SQLiteArtifactCache sqLiteArtifactCache =
          new SQLiteArtifactCache(
              "sqlite",
              projectFilesystem,
              cacheDir,
              buckEventBus,
              cacheConfig.getMaxSizeBytes(),
              cacheConfig.getMaxInlinedSizeBytes(),
              cacheConfig.getCacheReadMode());

      return new LoggingArtifactCacheDecorator(
          buckEventBus,
          sqLiteArtifactCache,
          new SQLiteArtifactCacheEvent.SQLiteArtifactCacheEventFactory());
    } catch (IOException | SQLException e) {
      throw new HumanReadableException(
          e, "Failure initializing artifact cache directory: %s", cacheDir);
    }
  }

  private static String stripNonAscii(String str) {
    if (CharMatcher.ascii().matchesAllOf(str)) {
      return str;
    }
    StringBuilder builder = new StringBuilder(str.length());
    for (int i = 0; i < str.length(); ++i) {
      char c = str.charAt(i);
      builder.append(CharMatcher.ascii().matches(c) ? c : '?');
    }
    return builder.toString();
  }

  private static OkHttpClient.Builder setTimeouts(
      OkHttpClient.Builder builder,
      int connectTimeoutSeconds,
      int readTimeoutSeconds,
      int writeTimeoutSeconds) {
    return builder
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS);
  }

  private static int getMultiFetchLimit(ArtifactCacheBuckConfig buckConfig, BuckEventBus eventBus) {
    return getAndRecordMultiFetchEnabled(buckConfig, eventBus)
        ? buckConfig.getMultiFetchLimit()
        : 0;
  }

  private static boolean getAndRecordMultiFetchEnabled(
      ArtifactCacheBuckConfig buckConfig, BuckEventBus eventBus) {
    MultiFetchType multiFetchType = buckConfig.getMultiFetchType();
    if (multiFetchType == MultiFetchType.EXPERIMENT) {
      multiFetchType =
          RandomizedTrial.getGroup(
              ArtifactCacheBuckConfig.MULTI_FETCH,
              eventBus.getBuildId().toString(),
              MultiFetchType.class);
      switch (multiFetchType) {
        case DISABLED:
        case ENABLED:
          eventBus.post(
              new ExperimentEvent(
                  ArtifactCacheBuckConfig.MULTI_FETCH, multiFetchType.toString(), "", null, null));
          break;
        case EXPERIMENT:
          throw new RuntimeException("RandomizedTrial picked invalid MultiFetchType.");
      }
    }
    return multiFetchType == MultiFetchType.ENABLED;
  }

  private static class ProgressResponseBody extends ResponseBody {

    private final ResponseBody responseBody;
    private BuckEventBus buckEventBus;
    private BufferedSource bufferedSource;

    public ProgressResponseBody(ResponseBody responseBody, BuckEventBus buckEventBus) {
      this.responseBody = responseBody;
      this.buckEventBus = buckEventBus;
      this.bufferedSource = Okio.buffer(source(responseBody.source()));
    }

    @Override
    public MediaType contentType() {
      return responseBody.contentType();
    }

    @Override
    public long contentLength() {
      return responseBody.contentLength();
    }

    @Override
    public BufferedSource source() {
      return bufferedSource;
    }

    private Source source(Source source) {
      return new ForwardingSource(source) {
        @Override
        public long read(Buffer sink, long byteCount) throws IOException {
          long bytesRead = super.read(sink, byteCount);
          // read() returns the number of bytes read, or -1 if this source is exhausted.
          if (byteCount != -1) {
            buckEventBus.post(new BytesReceivedEvent(byteCount));
          }
          return bytesRead;
        }
      };
    }
  }
}
