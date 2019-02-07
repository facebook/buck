/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.util.network;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.slb.SlbBuckConfig;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

public class RemoteLogBuckConfig {
  private static final String LOG_SECTION_NAME = "log";

  private static final String REQUEST_TIMEOUT_MILLIS = "remote_log_timeout_millis";
  private static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 3000;
  private static final String REQUEST_MAX_THREADS = "remote_log_max_threads";
  private static final int DEFAULT_REQUEST_MAX_THREADS = 5;

  private final SlbBuckConfig frontendConfig;
  private final BuckConfig buckConfig;

  public RemoteLogBuckConfig(BuckConfig config) {
    this.buckConfig = config;
    this.frontendConfig = new SlbBuckConfig(config, LOG_SECTION_NAME);
  }

  public SlbBuckConfig getFrontendConfig() {
    return frontendConfig;
  }

  public OkHttpClient createOkHttpClient() {
    long timeout =
        buckConfig
            .getLong(LOG_SECTION_NAME, REQUEST_TIMEOUT_MILLIS)
            .orElse(DEFAULT_REQUEST_TIMEOUT_MILLIS);
    return new OkHttpClient.Builder()
        .connectTimeout(timeout, TimeUnit.MILLISECONDS)
        .readTimeout(timeout, TimeUnit.MILLISECONDS)
        .writeTimeout(timeout, TimeUnit.MILLISECONDS)
        .build();
  }

  /** @return max. threads to be used for concurrent remote log requests. */
  public int getMaxThreads() {
    return buckConfig
        .getInteger(LOG_SECTION_NAME, REQUEST_MAX_THREADS)
        .orElse(DEFAULT_REQUEST_MAX_THREADS);
  }

  public String getRepository() {
    return buckConfig.getValue("cache", "repository").orElse("unknown");
  }
}
