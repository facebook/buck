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
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.squareup.okhttp.ConnectionPool;
import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Response;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Creates instances of the {@link ArtifactCache}.
 */
public class ArtifactCaches {

  private ArtifactCaches() {
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
   * @throws InterruptedException
   */
  public static Optional<ArtifactCache> newServedCache(
      ArtifactCacheBuckConfig buckConfig,
      ProjectFilesystem projectFilesystem) {
    if (!buckConfig.getServingLocalCacheEnabled()) {
      return Optional.absent();
    } else {
      return Optional.of(createDirArtifactCache(
              Optional.<BuckEventBus>absent(), buckConfig, projectFilesystem));
    }
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
                  buckConfig,
                  projectFilesystem));
          break;
        case http:
          for (HttpCacheEntry cacheEntry : buckConfig.getHttpCaches()) {
            if (!cacheEntry.isWifiUsableForDistributedCache(wifiSsid)) {
              continue;
            }
            builder.add(createHttpArtifactCache(
                    cacheEntry,
                    buckConfig.getHostToReportToRemoteCacheServer(),
                    buckEventBus,
                    projectFilesystem,
                    httpWriteExecutorService));
          }
          break;
      }
    }
    ImmutableList<ArtifactCache> artifactCaches = builder.build();

    if (artifactCaches.size() == 1) {
      // Don't bother wrapping a single artifact cache in MultiArtifactCache.
      return artifactCaches.get(0);
    } else {
      return new MultiArtifactCache(artifactCaches);
    }
  }

  private static ArtifactCache createDirArtifactCache(
      Optional<BuckEventBus> buckEventBus,
      ArtifactCacheBuckConfig buckConfig,
      ProjectFilesystem projectFilesystem) {
    DirCacheEntry dirCacheConfig = buckConfig.getDirCache();
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
      BuckEventBus buckEventBus,
      ProjectFilesystem projectFilesystem,
      ListeningExecutorService httpWriteExecutorService) {
    String cacheName = cacheDescription.getName()
        .transform(new Function<String, String>() {
                     @Override
                     public String apply(String input) {
                       return "http-" + input;
                     }
                   })
        .or("http");
    URI url = cacheDescription.getUrl();
    int timeoutSeconds = cacheDescription.getTimeoutSeconds();
    boolean doStore = cacheDescription.getCacheReadMode().isDoStore();

    // Setup the defaut client to use.
    OkHttpClient client = new OkHttpClient();
    client.networkInterceptors().add(
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
        cacheName,
        fetchClient,
        client,
        url,
        doStore,
        projectFilesystem,
        buckEventBus,
        httpWriteExecutorService);
  }

}
