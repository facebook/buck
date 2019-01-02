/*
 * Copyright 2018-present Facebook, Inc.
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
package com.facebook.buck.manifestservice;

import com.facebook.buck.artifact_cache.thrift.BuckCacheRequest;
import com.facebook.buck.artifact_cache.thrift.BuckCacheResponse;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.slb.ClientSideSlb;
import com.facebook.buck.slb.HttpService;
import com.facebook.buck.slb.HybridThriftOverHttpServiceImpl;
import com.facebook.buck.slb.HybridThriftOverHttpServiceImplArgs;
import com.facebook.buck.slb.LoadBalancedService;
import com.facebook.buck.slb.SlbBuckConfig;
import com.facebook.buck.util.timing.Clock;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClient.Builder;

/** Manifest configuration section in the buck config. */
public class ManifestServiceConfig {
  private static final String MANIFEST_SECTION = "manifestservice";

  private final BuckConfig buckConfig;
  private final SlbBuckConfig slbConfig;

  public ManifestServiceConfig(BuckConfig buckConfig) {
    this.buckConfig = buckConfig;
    this.slbConfig = new SlbBuckConfig(buckConfig, MANIFEST_SECTION);
  }

  /** New instance of the ManifestService from the current config. */
  public ManifestService createManifestService(
      Clock clock, BuckEventBus eventBus, ListeningExecutorService executor) {
    ClientSideSlb slb = slbConfig.createClientSideSlb(clock, eventBus);
    Dispatcher dispatcher = new Dispatcher();
    dispatcher.setMaxRequestsPerHost(getThreadPoolSize());
    OkHttpClient client =
        new Builder()
            .connectTimeout(getTimeoutMillis(), TimeUnit.MILLISECONDS)
            .readTimeout(getTimeoutMillis(), TimeUnit.MILLISECONDS)
            .writeTimeout(getTimeoutMillis(), TimeUnit.MILLISECONDS)
            .dispatcher(dispatcher)
            .connectionPool(
                new ConnectionPool(
                    getThreadPoolSize(), getThreadPoolKeepAliveDurationMillis(), TimeUnit.MINUTES))
            .build();
    HttpService httpService = new LoadBalancedService(slb, client, eventBus);
    HybridThriftOverHttpServiceImplArgs args =
        HybridThriftOverHttpServiceImplArgs.builder()
            .setExecutor(executor)
            .setService(httpService)
            .build();
    HybridThriftOverHttpServiceImpl<BuckCacheRequest, BuckCacheResponse> hybridThriftService =
        new HybridThriftOverHttpServiceImpl<>(args);
    return new ThriftManifestService(hybridThriftService, executor);
  }

  public long getTimeoutMillis() {
    return buckConfig.getLong(MANIFEST_SECTION, "request_timeout_millis").orElse(1000L);
  }

  public String getHybridThriftEndpoint() {
    return buckConfig.getValue(MANIFEST_SECTION, "hybrid_thrift_endpoint").orElse("/hybrid_thrift");
  }

  public boolean isEnabledForManifestRuleKeys() {
    return buckConfig.getBooleanValue(MANIFEST_SECTION, "enable_for_manifest_rule_key", false);
  }

  public int getThreadPoolSize() {
    return buckConfig.getInteger(MANIFEST_SECTION, "http_thread_pool_size").orElse(5);
  }

  public long getThreadPoolKeepAliveDurationMillis() {
    return buckConfig
        .getLong(MANIFEST_SECTION, "http_thread_pool_keep_alive_duration_millis")
        .orElse(TimeUnit.MINUTES.toMillis(1));
  }
}
