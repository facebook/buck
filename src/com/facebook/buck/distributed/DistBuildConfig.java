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

package com.facebook.buck.distributed;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.SlbBuckConfig;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public class DistBuildConfig {

  public static final String STAMPEDE_SECTION = "stampede";

  private static final String FRONTEND_REQUEST_TIMEOUT_MILLIS = "stampede_timeout_millis";
  private static final long DEFAULT_DEFAULT_REQUEST_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60);

  private final SlbBuckConfig frontendConfig;
  private final BuckConfig buckConfig;

  public DistBuildConfig(BuckConfig config) {
    this.buckConfig = config;
    this.frontendConfig = new SlbBuckConfig(config, STAMPEDE_SECTION);
  }

  public SlbBuckConfig getFrontendConfig() {
    return frontendConfig;
  }

  public long getFrontendRequestTimeoutMillis() {
    return buckConfig.getLong(STAMPEDE_SECTION, FRONTEND_REQUEST_TIMEOUT_MILLIS).orElse(
        DEFAULT_DEFAULT_REQUEST_TIMEOUT_MILLIS);
  }

  public OkHttpClient createOkHttpClient() {
    return new OkHttpClient.Builder()
        .connectTimeout(getFrontendRequestTimeoutMillis(), TimeUnit.MILLISECONDS)
        .readTimeout(getFrontendRequestTimeoutMillis(), TimeUnit.MILLISECONDS)
        .writeTimeout(getFrontendRequestTimeoutMillis(), TimeUnit.MILLISECONDS)
        .build();
  }
}
