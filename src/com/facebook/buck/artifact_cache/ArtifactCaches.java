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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.NetworkEvent.BytesReceivedEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.CommandThreadFactory;
import com.facebook.buck.log.Logger;
import com.facebook.buck.slb.HttpLoadBalancer;
import com.facebook.buck.slb.HttpService;
import com.facebook.buck.slb.LoadBalancedService;
import com.facebook.buck.slb.RetryingHttpService;
import com.facebook.buck.slb.SingleUriService;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.Interceptor;
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

/**
 * Creates instances of the {@link ArtifactCache}.
 */
public class ArtifactCaches {

  private static final Logger LOG = Logger.get(ArtifactCaches.class);

  private interface NetworkCacheFactory {
    ArtifactCache newInstance(NetworkCacheArgs args);
  }

  private static final NetworkCacheFactory HTTP_PROTOCOL = new NetworkCacheFactory() {
    @Override
    public ArtifactCache newInstance(NetworkCacheArgs args) {
      return new HttpArtifactCache(args);
    }
  };

  private static final NetworkCacheFactory THRIFT_PROTOCOL = new NetworkCacheFactory() {
    @Override
    public ArtifactCache newInstance(NetworkCacheArgs args) {
      return new ThriftArtifactCache(args);
    }
  };

  private ArtifactCaches() {
  }

  private static Request.Builder addHeadersToBuilder(
      Request.Builder builder, ImmutableMap<String, String> headers) {
    ImmutableSet<Map.Entry<String, String>> entries = headers.entrySet();
    for (Map.Entry<String, String> header : entries) {
      builder.addHeader(header.getKey(), header.getValue());
    }
    return builder;
  }

  /**
   * Creates a new instance of the cache for use during a build.
   *
   * @param buckConfig describes what kind of cache to create
   * @param buckEventBus event bus
   * @param projectFilesystem filesystem to store files on
   * @param wifiSsid current WiFi ssid to decide if we want the http cache or not
   * @return a cache
   * @throws InterruptedException
   */
  public static ArtifactCache newInstance(
      ArtifactCacheBuckConfig buckConfig,
      BuckEventBus buckEventBus,
      ProjectFilesystem projectFilesystem,
      Optional<String> wifiSsid,
      ListeningExecutorService httpWriteExecutorService) throws InterruptedException {
    ArtifactCacheConnectEvent.Started started = ArtifactCacheConnectEvent.started();
    buckEventBus.post(started);
    ArtifactCache artifactCache = newInstanceInternal(
        buckConfig,
        buckEventBus,
        projectFilesystem,
        wifiSsid,
        httpWriteExecutorService);
    buckEventBus.post(ArtifactCacheConnectEvent.finished(started));
    return artifactCache;
  }

  /**
   * Creates a new instance of the cache to be used to serve the dircache from the WebServer.
   *
   * @param buckConfig describes how to configure te cache
   * @param projectFilesystem filesystem to store files on
   * @return a cache
   */
  public static Optional<ArtifactCache> newServedCache(
      ArtifactCacheBuckConfig buckConfig,
      final ProjectFilesystem projectFilesystem) {
    return buckConfig.getServedLocalCache().transform(
        new Function<DirCacheEntry, ArtifactCache>() {
          @Override
          public ArtifactCache apply(DirCacheEntry input) {
            return createDirArtifactCache(
                Optional.<BuckEventBus>absent(),
                input,
                projectFilesystem);
          }
        });
  }

  private static ArtifactCache newInstanceInternal(
      ArtifactCacheBuckConfig buckConfig,
      BuckEventBus buckEventBus,
      ProjectFilesystem projectFilesystem,
      Optional<String> wifiSsid,
      ListeningExecutorService httpWriteExecutorService) throws InterruptedException {
    ImmutableSet<ArtifactCacheBuckConfig.ArtifactCacheMode> modes =
        buckConfig.getArtifactCacheModes();
    if (modes.isEmpty()) {
      return new NoopArtifactCache();
    }
    ImmutableList.Builder<ArtifactCache> builder = ImmutableList.builder();
    for (ArtifactCacheBuckConfig.ArtifactCacheMode mode : modes) {
      switch (mode) {
        case dir:
          builder.add(
              createDirArtifactCache(
                  Optional.of(buckEventBus),
                  buckConfig.getDirCache(),
                  projectFilesystem));
          break;
        case http:
          initializeDistributedCaches(
              buckConfig,
              buckEventBus,
              projectFilesystem,
              wifiSsid,
              httpWriteExecutorService,
              builder,
              HTTP_PROTOCOL);
          break;

        case thrift_over_http:
          initializeDistributedCaches(
              buckConfig,
              buckEventBus,
              projectFilesystem,
              wifiSsid,
              httpWriteExecutorService,
              builder,
              THRIFT_PROTOCOL);
          break;
      }
    }
    ImmutableList<ArtifactCache> artifactCaches = builder.build();
    ArtifactCache result;

    if (artifactCaches.size() == 1) {
      // Don't bother wrapping a single artifact cache in MultiArtifactCache.
      result = artifactCaches.get(0);
    } else {
      result = new MultiArtifactCache(artifactCaches);
    }

    // Always support reading two-level cache stores (in case we performed any in the past).
    result = new TwoLevelArtifactCacheDecorator(
        result,
        projectFilesystem,
        buckEventBus,
        buckConfig.getTwoLevelCachingEnabled(),
        buckConfig.getTwoLevelCachingMinimumSize(),
        buckConfig.getTwoLevelCachingMaximumSize());

    return result;
  }

  private static void initializeDistributedCaches(
      ArtifactCacheBuckConfig buckConfig,
      BuckEventBus buckEventBus,
      ProjectFilesystem projectFilesystem,
      Optional<String> wifiSsid,
      ListeningExecutorService httpWriteExecutorService,
      ImmutableList.Builder<ArtifactCache> builder,
      NetworkCacheFactory factory) {
    for (HttpCacheEntry cacheEntry : buckConfig.getHttpCaches()) {
      if (!cacheEntry.isWifiUsableForDistributedCache(wifiSsid)) {
        LOG.warn("HTTP cache is disabled because WiFi is not usable.");
        continue;
      }

      builder.add(createHttpArtifactCache(
              cacheEntry,
              buckConfig.getHostToReportToRemoteCacheServer(),
              buckEventBus,
              projectFilesystem,
              httpWriteExecutorService,
              buckConfig,
              factory));
    }
  }

  private static ArtifactCache createDirArtifactCache(
      Optional<BuckEventBus> buckEventBus,
      DirCacheEntry dirCacheConfig,
      ProjectFilesystem projectFilesystem) {
    Path cacheDir = dirCacheConfig.getCacheDir();
    try {
      DirArtifactCache dirArtifactCache =  new DirArtifactCache(
          "dir",
          projectFilesystem,
          cacheDir,
          dirCacheConfig.getCacheReadMode().isDoStore(),
          dirCacheConfig.getMaxSizeBytes());

      if (!buckEventBus.isPresent()) {
        return dirArtifactCache;
      }

      return new LoggingArtifactCacheDecorator(buckEventBus.get(),
          dirArtifactCache,
          new DirArtifactCacheEvent.DirArtifactCacheEventFactory());

    } catch (IOException e) {
      throw new HumanReadableException(
          "Failure initializing artifact cache directory: %s",
          cacheDir);
    }
  }

  private static ArtifactCache createHttpArtifactCache(
      HttpCacheEntry cacheDescription,
      final String hostToReportToRemote,
      final BuckEventBus buckEventBus,
      ProjectFilesystem projectFilesystem,
      ListeningExecutorService httpWriteExecutorService,
      ArtifactCacheBuckConfig config,
      NetworkCacheFactory factory) {

    // Setup the default client to use.
    OkHttpClient.Builder storeClientBuilder = new OkHttpClient.Builder();
    storeClientBuilder.networkInterceptors().add(
        new Interceptor() {
          @Override
          public Response intercept(Chain chain) throws IOException {
            return chain.proceed(
                chain.request().newBuilder()
                    .addHeader("X-BuckCache-User", System.getProperty("user.name", "<unknown>"))
                    .addHeader("X-BuckCache-Host", hostToReportToRemote)
                    .build());
          }
        });
    int timeoutSeconds = cacheDescription.getTimeoutSeconds();
    setTimeouts(storeClientBuilder, timeoutSeconds);
    storeClientBuilder.connectionPool(
        new ConnectionPool(
            /* maxIdleConnections */ (int) config.getThreadPoolSize(),
            /* keepAliveDurationMs */ config.getThreadPoolKeepAliveDurationMillis(),
            TimeUnit.MILLISECONDS)
        );

    final ImmutableMap<String, String> readHeaders = cacheDescription.getReadHeaders();
    final ImmutableMap<String, String> writeHeaders = cacheDescription.getWriteHeaders();

    // If write headers are specified, add them to every default client request.
    if (!writeHeaders.isEmpty()) {
      storeClientBuilder.networkInterceptors().add(
          new Interceptor() {
            @Override
            public Response intercept(Chain chain) throws IOException {
              return chain.proceed(
                addHeadersToBuilder(chain.request().newBuilder(), writeHeaders).build()
              );
            }
          });
    }

    OkHttpClient storeClient = storeClientBuilder.build();

    // For fetches, use a client with a read timeout.
    OkHttpClient.Builder fetchClientBuilder = storeClient.newBuilder();
    setTimeouts(fetchClientBuilder, timeoutSeconds);

    // If read headers are specified, add them to every read client request.
    if (!readHeaders.isEmpty()) {
      fetchClientBuilder.networkInterceptors().add(
          new Interceptor() {
            @Override
            public Response intercept(Chain chain) throws IOException {
              return chain.proceed(
                addHeadersToBuilder(chain.request().newBuilder(), readHeaders).build()
              );
            }
          });
    }

    fetchClientBuilder.networkInterceptors().add((new Interceptor() {
      @Override
      public Response intercept(Chain chain) throws IOException {
        Response originalResponse = chain.proceed(chain.request());
        return originalResponse.newBuilder()
            .body(new ProgressResponseBody(originalResponse.body(), buckEventBus))
            .build();
      }
    }));
    OkHttpClient fetchClient = fetchClientBuilder.build();

    HttpService fetchService;
    HttpService storeService;
    switch (config.getLoadBalancingType()) {
      case CLIENT_SLB:
        HttpLoadBalancer clientSideSlb = config.getSlbConfig().createClientSideSlb(
            new DefaultClock(),
            buckEventBus,
            new CommandThreadFactory("ArtifactCaches.HttpLoadBalancer"));
        fetchService =
            new RetryingHttpService(
                buckEventBus,
                new LoadBalancedService(clientSideSlb, fetchClient, buckEventBus),
                config.getMaxFetchRetries());
        storeService = new LoadBalancedService(clientSideSlb, storeClient, buckEventBus);
        break;

      case SINGLE_SERVER:
        URI url = cacheDescription.getUrl();
        fetchService = new SingleUriService(url, fetchClient);
        storeService = new SingleUriService(url, storeClient);
        break;

      default:
        throw new IllegalArgumentException("Unknown HttpLoadBalancer type: " +
            config.getLoadBalancingType());
    }

    String cacheName = cacheDescription.getName()
        .transform(new Function<String, String>() {
          @Override
          public String apply(String input) {
            return "http-" + input;
          }
        })
        .or("http");
    boolean doStore = cacheDescription.getCacheReadMode().isDoStore();
    return factory.newInstance(
        NetworkCacheArgs.builder()
            .setThriftEndpointPath(config.getHybridThriftEndpoint())
            .setCacheName(cacheName)
            .setRepository(config.getRepository())
            .setScheduleType(config.getScheduleType())
            .setFetchClient(fetchService)
            .setStoreClient(storeService)
            .setDoStore(doStore)
            .setProjectFilesystem(projectFilesystem)
            .setBuckEventBus(buckEventBus)
            .setHttpWriteExecutorService(httpWriteExecutorService)
            .setErrorTextTemplate(cacheDescription.getErrorMessageFormat())
            .build());
  }

  private static OkHttpClient.Builder setTimeouts(
      OkHttpClient.Builder builder,
      int timeoutSeconds) {
    return builder
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS);
  }

  private static class ProgressResponseBody extends ResponseBody {

    private final ResponseBody responseBody;
    private BuckEventBus buckEventBus;
    private BufferedSource bufferedSource;

    public ProgressResponseBody(
        ResponseBody responseBody,
        BuckEventBus buckEventBus) throws IOException {
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
