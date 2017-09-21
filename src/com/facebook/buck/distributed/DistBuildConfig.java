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
import com.facebook.buck.log.Logger;
import com.facebook.buck.slb.SlbBuckConfig;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.config.RawConfig;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

public class DistBuildConfig {

  private static final Logger LOG = Logger.get(DistBuildConfig.class);

  public static final String STAMPEDE_SECTION = "stampede";

  private static final String FRONTEND_REQUEST_TIMEOUT_MILLIS = "stampede_timeout_millis";
  private static final long REQUEST_TIMEOUT_MILLIS_DEFAULT_VALUE = TimeUnit.SECONDS.toMillis(60);

  @VisibleForTesting
  static final String ALWAYS_MATERIALIZE_WHITELIST = "always_materialize_whitelist";

  private static final String ENABLE_SLOW_LOCAL_BUILD_FALLBACK = "enable_slow_local_build_fallback";
  private static final boolean ENABLE_SLOW_LOCAL_BUILD_FALLBACK_DEFAULT_VALUE = false;

  private static final String BUILD_MODE = "build_mode";
  private static final BuildMode BUILD_MODE_DEFAULT_VALUE = BuildMode.REMOTE_BUILD;

  private static final String NUMBER_OF_MINIONS = "number_of_minions";
  private static final Integer NUMBER_OF_MINIONS_DEFAULT_VALUE = 2;

  private static final String REPOSITORY = "repository";
  private static final String DEFAULT_REPOSITORY = "";
  private static final String TENANT_ID = "tenant_id";
  private static final String DEFAULT_TENANT_ID = "";

  private static final String BUILD_LABEL = "build_label";
  private static final String DEFAULT_BUILD_LABEL = "";

  private static final String MINION_QUEUE = "minion_queue";

  private static final String MAX_BUILD_NODES_PER_MINION = "max_build_nodes_per_minion";
  private static final int DEFAULT_MAX_BUILD_NODES_PER_MINION = 100;

  private static final String SOURCE_FILE_MULTI_FETCH_BUFFER_PERIOD_MS =
      "source_file_multi_fetch_buffer_period_ms";
  private static final String SOURCE_FILE_MULTI_FETCH_MAX_BUFFER_SIZE =
      "source_file_multi_fetch_max_buffer_size";

  private static final String MATERIALIZE_SOURCE_FILES_ON_DEMAND =
      "materialize_source_files_on_demand";

  @VisibleForTesting static final String SERVER_BUCKCONFIG_OVERRIDE = "server_buckconfig_override";

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

  public Optional<Long> getSourceFileMultiFetchBufferPeriodMs() {
    return buckConfig.getLong(STAMPEDE_SECTION, SOURCE_FILE_MULTI_FETCH_BUFFER_PERIOD_MS);
  }

  public Optional<Integer> getSourceFileMultiFetchMaxBufferSize() {
    return buckConfig.getInteger(STAMPEDE_SECTION, SOURCE_FILE_MULTI_FETCH_MAX_BUFFER_SIZE);
  }

  public boolean materializeSourceFilesOnDemand() {
    return buckConfig.getBooleanValue(STAMPEDE_SECTION, MATERIALIZE_SOURCE_FILES_ON_DEMAND, false);
  }

  public Optional<ImmutableList<String>> getOptionalPathWhitelist() {
    // Can't use getOptionalPathList here because sparse checkouts may mean we don't have all files
    // in other cells.
    return buckConfig.getOptionalListWithoutComments(
        STAMPEDE_SECTION, ALWAYS_MATERIALIZE_WHITELIST);
  }

  public Config getRemoteConfigWithOverride() {
    Optional<Path> serverConfigPath = getOptionalServerBuckconfigOverride();

    RawConfig.Builder rawConfigBuilder = RawConfig.builder();
    rawConfigBuilder.putAll(buckConfig.getConfig().getRawConfigForDistBuild());
    if (serverConfigPath.isPresent()) {
      try {
        rawConfigBuilder.putAll(Configs.parseConfigFile(serverConfigPath.get()));
        LOG.info("Applied server side config override [%s].", serverConfigPath.get().toString());
      } catch (IOException e) {
        throw new RuntimeException(
            String.format(
                "Unable to parse server-side config file (%s) specified in [%s:%s].",
                serverConfigPath.get().toString(), STAMPEDE_SECTION, SERVER_BUCKCONFIG_OVERRIDE),
            e);
      }
    }
    return new Config(rawConfigBuilder.build());
  }

  public Optional<Path> getOptionalServerBuckconfigOverride() {
    return buckConfig.getPath(STAMPEDE_SECTION, SERVER_BUCKCONFIG_OVERRIDE);
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

  public Optional<String> getMinionQueue() {
    return buckConfig.getValue(STAMPEDE_SECTION, MINION_QUEUE);
  }

  public int getMaxBuildNodesPerMinion() {
    return buckConfig
        .getInteger(STAMPEDE_SECTION, MAX_BUILD_NODES_PER_MINION)
        .orElse(DEFAULT_MAX_BUILD_NODES_PER_MINION);
  }

  public String getRepository() {
    return buckConfig.getValue(STAMPEDE_SECTION, REPOSITORY).orElse(DEFAULT_REPOSITORY);
  }

  public String getTenantId() {
    return buckConfig.getValue(STAMPEDE_SECTION, TENANT_ID).orElse(DEFAULT_TENANT_ID);
  }

  public String getBuildLabel() {
    return buckConfig.getValue(STAMPEDE_SECTION, BUILD_LABEL).orElse(DEFAULT_BUILD_LABEL);
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
