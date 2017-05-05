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
import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.slb.SlbBuckConfig;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

public class DistBuildConfig {

  public static final String STAMPEDE_SECTION = "stampede";

  private static final String FRONTEND_REQUEST_TIMEOUT_MILLIS = "stampede_timeout_millis";
  private static final long REQUEST_TIMEOUT_MILLIS_DEFAULT_VALUE = TimeUnit.SECONDS.toMillis(60);

  private static final String ALWAYS_MATERIALIZE_WHITELIST = "always_materialize_whitelist";

  private static final String ENABLE_SLOW_LOCAL_BUILD_FALLBACK = "enable_slow_local_build_fallback";
  private static final boolean ENABLE_SLOW_LOCAL_BUILD_FALLBACK_DEFAULT_VALUE = false;

  private static final String BUILD_MODE = "build_mode";
  private static final BuildMode BUILD_MODE_DEFAULT_VALUE = BuildMode.REMOTE_BUILD;

  private static final String NUMBER_OF_MINIONS = "number_of_minions";
  private static final Integer NUMBER_OF_MINIONS_DEFAULT_VALUE = 2;

  private final SlbBuckConfig frontendConfig;
  private final BuckConfig buckConfig;

  public DistBuildConfig(BuckConfig config) {
    this.buckConfig = config;
    this.frontendConfig = new SlbBuckConfig(config, STAMPEDE_SECTION);
  }

  public SlbBuckConfig getFrontendConfig() {
    return frontendConfig;
  }

  public BuckConfig getBuckConfig() {
    return buckConfig;
  }

  public Optional<ImmutableList<Path>> getOptionalPathWhitelist() {
    return buckConfig.getOptionalPathList(STAMPEDE_SECTION, ALWAYS_MATERIALIZE_WHITELIST);
  }

  public long getFrontendRequestTimeoutMillis() {
    return buckConfig
        .getLong(STAMPEDE_SECTION, FRONTEND_REQUEST_TIMEOUT_MILLIS)
        .orElse(REQUEST_TIMEOUT_MILLIS_DEFAULT_VALUE);
  }

  public BuildMode getBuildMode() {
    return buckConfig
        .getEnum(STAMPEDE_SECTION, BUILD_MODE, BuildMode.class)
        .orElse(BUILD_MODE_DEFAULT_VALUE);
  }

  public int getNumberOfMinions() {
    return buckConfig
        .getInteger(STAMPEDE_SECTION, NUMBER_OF_MINIONS)
        .orElse(NUMBER_OF_MINIONS_DEFAULT_VALUE);
  }

  /**
   * Whether buck distributed build should stop building if remote/distributed build fails (true) or
   * if it should fallback to building locally if remote/distributed build fails (false).
   */
  public boolean isSlowLocalBuildFallbackModeEnabled() {
    return buckConfig.getBooleanValue(
        STAMPEDE_SECTION,
        ENABLE_SLOW_LOCAL_BUILD_FALLBACK,
        ENABLE_SLOW_LOCAL_BUILD_FALLBACK_DEFAULT_VALUE);
  }

  public OkHttpClient createOkHttpClient() {
    return new OkHttpClient.Builder()
        .connectTimeout(getFrontendRequestTimeoutMillis(), TimeUnit.MILLISECONDS)
        .readTimeout(getFrontendRequestTimeoutMillis(), TimeUnit.MILLISECONDS)
        .writeTimeout(getFrontendRequestTimeoutMillis(), TimeUnit.MILLISECONDS)
        .build();
  }
}
