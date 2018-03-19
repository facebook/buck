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

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
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

  // Connection and socket timeout used by minions in ThriftCoordinatorClient
  private static final String COORDINATOR_CONNECTION_TIMEOUT_MILLIS =
      "coordinator_connection_timeout_millis";
  private static final int COORDINATOR_CONNECTION_TIMEOUT_MILLIS_DEFAULT_VALUE = 2000;

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

  private static final String SOURCE_FILE_MULTI_FETCH_BUFFER_PERIOD_MS =
      "source_file_multi_fetch_buffer_period_ms";
  private static final String SOURCE_FILE_MULTI_FETCH_MAX_BUFFER_SIZE =
      "source_file_multi_fetch_max_buffer_size";

  private static final String MATERIALIZE_SOURCE_FILES_ON_DEMAND =
      "materialize_source_files_on_demand";

  private static final String MAX_WAIT_FOR_REMOTE_LOGS_TO_BE_AVAILABLE_MILLIS =
      "max_wait_for_remote_logs_to_be_available_millis";
  private static final long DEFAULT_MAX_WAIT_FOR_REMOTE_LOGS_TO_BE_AVAILABLE_MILLIS =
      TimeUnit.MINUTES.toMillis(5);

  private static final String LOG_MATERIALIZATION_ENABLED = "log_materialization_enabled";
  private static final boolean DEFAULT_LOG_MATERIALIZATION_ENABLED = false;

  private static final String DISTRIBUTED_BUILD_THREAD_KILL_TIMEOUT_SECONDS =
      "distributed_build_thread_kill_timeout_seconds";
  private static final long DEFAULT_DISTRIBUTED_BUILD_THREAD_KILL_TIMEOUT_SECONDS = 2;

  private static final String PERFORM_RULE_KEY_CONSISTENCY_CHECK =
      "perform_rule_key_consistency_check";
  private static final boolean DEFAULT_PERFORM_RULE_KEY_CONSISTENCY_CHECK = false;

  @VisibleForTesting static final String SERVER_BUCKCONFIG_OVERRIDE = "server_buckconfig_override";

  private static final String FRONTEND_REQUEST_MAX_RETRIES = "frontend_request_max_retries";
  private static final int DEFAULT_FRONTEND_REQUEST_MAX_RETRIES = 3;

  private static final String FRONTEND_REQUEST_RETRY_INTERVAL_MILLIS =
      "frontend_request_retry_interval_millis";
  private static final long DEFAULT_FRONTEND_REQUEST_RETRY_INTERVAL_MILLIS = 1000;

  private static final String MINION_POLL_LOOP_INTERVAL_MILLIS = "minion_poll_loop_interval_millis";
  private static final long DEFAULT_MINION_POLL_LOOP_INTERVAL_MILLIS = 10;

  private static final String HEARTBEAT_SERVICE_INTERVAL_MILLIS =
      "heartbeat_service_interval_millis";
  private static final long DEFAULT_HEARTBEAT_SERVICE_INTERVAL_MILLIS = 10000;

  // If heartbeat takes longer than slow_heartbeat_warning_threshold_millis, record this as a
  // warning.
  private static final String SLOW_HEARTBEAT_WARNING_THRESHOLD_MILLIS =
      "slow_heartbeat_warning_threshold_millis";
  private static final long DEFAULT_SLOW_HEARTBEAT_WARNING_THRESHOLD_MILLIS = 15000;

  // Max number of threads used for fetching build statuses, sending requests to frontend etc.
  private static final String CONTROLLER_MAX_THREAD_COUNT = "controller_max_thread_count";
  private static final int DEFAULT_CONTROLLER_MAX_THREAD_COUNT = 20;

  private static final String MAX_MINION_SILENCE_MILLIS = "max_minion_silence_millis";
  private static final long DEFAULT_MAX_MINION_SILENCE_MILLIS = TimeUnit.SECONDS.toMillis(30);

  private static final String ENABLE_DEEP_REMOTE_BUILD = "enable_deep_remote_build";
  private static final boolean DEFAULT_ENABLE_DEEP_REMOTE_BUILD = false;

  private static final String ENABLE_ASYNC_LOGGING = "enable_async_logging";
  private static final boolean DEFAULT_ENABLE_ASYNC_LOGGING = true;

  private static final String ENABLE_CACHE_MISS_ANALYSIS = "enable_cache_miss_analysis";
  private static final boolean DEFAULT_ENABLE_CACHE_MISS_ANALYSIS = false;

  private static final String ALWAYS_WAIT_FOR_REMOTE_BUILD_BEFORE_PROCEEDING_LOCALLY =
      "always_wait_for_remote_build_before_proceeding_locally";
  private static final boolean DEFAULT_ALWAYS_WAIT_FOR_REMOTE_BUILD_BEFORE_PROCEEDING_LOCALLY =
      true;

  private static final String MOST_BUILD_RULES_FINISHED_PERCENTAGE_THRESHOLD =
      "most_build_rules_finished_percentage_threshold";
  private static final int DEFAULT_MOST_BUILD_RULES_FINISHED_PERCENTAGE_THRESHOLD = 80;

  private static final String ENABLE_UPLOADS_FROM_LOCAL_CACHE = "enable_uploads_from_local_cache";
  private static final boolean DEFAULT_ENABLE_UPLOADS_FROM_LOCAL_CACHE = false;

  // Percentage of available CPU cores to use for the coordinator build.
  // Default this to 75% to ensure coordinator is always responsive to requests from minions
  private static final String COORDINATOR_BUILD_CAPACITY_RATIO = "coordinator_build_capacity_ratio";
  private static final Double DEFAULT_COORDINATOR_BUILD_CAPACITY_RATIO = 0.75;

  // Percentage of available CPU cores to use for the minion builds.
  // Default this to 90% to ensure we never timeout requests to the coordinator.
  private static final String MINION_BUILD_CAPACITY_RATIO = "minion_build_capacity_ratio";
  private static final Double DEFAULT_MINION_BUILD_CAPACITY_RATIO = 0.9;

  /**
   * While the experiments.stampede_beta_test flag is set to true, this flag can be used to
   * configure whether we want auto-stampede conversion for all builds, no builds, or some builds.
   * See {@link AutoStampedeMode}.
   */
  private static final String AUTO_STAMPEDE_BUILD_ENABLED = "auto_stampede_build_enabled";

  private static final String EXPERIMENTS_SECTION = "experiments";
  private static final String STAMPEDE_BETA_TEST = "stampede_beta_test";
  private static final boolean DEFAULT_STAMPEDE_BETA_TEST = false;

  private static final String AUTO_STAMPEDE_BUILD_MESSAGE = "auto_stampede_build_message";

  private static final String FILE_MATERIALIZATION_TIMEOUT_SECS =
      "pending_file_materialization_timeout_secs";
  private static final long DEFAULT_FILE_MATERIALIZATION_TIMEOUT_SECS = 30;

  private static final String CACHE_SYNCHRONIZATION_SAFETY_MARGIN_MILLIS =
      "cache_synchronization_safety_margin_millis";
  private static final int DEFAULT_CACHE_SYNCHRONIZATION_SAFETY_MARGIN_MILLIS = 5000;

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

  public int getCoordinatorConnectionTimeoutMillis() {
    return buckConfig
        .getInteger(STAMPEDE_SECTION, COORDINATOR_CONNECTION_TIMEOUT_MILLIS)
        .orElse(COORDINATOR_CONNECTION_TIMEOUT_MILLIS_DEFAULT_VALUE);
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

  public String getRepository() {
    return buckConfig.getValue(STAMPEDE_SECTION, REPOSITORY).orElse(DEFAULT_REPOSITORY);
  }

  public String getTenantId() {
    return buckConfig.getValue(STAMPEDE_SECTION, TENANT_ID).orElse(DEFAULT_TENANT_ID);
  }

  public String getBuildLabel() {
    return buckConfig.getValue(STAMPEDE_SECTION, BUILD_LABEL).orElse(DEFAULT_BUILD_LABEL);
  }

  public long getMaxWaitForRemoteLogsToBeAvailableMillis() {
    return buckConfig
        .getLong(STAMPEDE_SECTION, MAX_WAIT_FOR_REMOTE_LOGS_TO_BE_AVAILABLE_MILLIS)
        .orElse(DEFAULT_MAX_WAIT_FOR_REMOTE_LOGS_TO_BE_AVAILABLE_MILLIS);
  }

  public boolean getLogMaterializationEnabled() {
    return buckConfig
        .getBoolean(STAMPEDE_SECTION, LOG_MATERIALIZATION_ENABLED)
        .orElse(DEFAULT_LOG_MATERIALIZATION_ENABLED);
  }

  public long getDistributedBuildThreadKillTimeoutSeconds() {
    return buckConfig
        .getLong(STAMPEDE_SECTION, DISTRIBUTED_BUILD_THREAD_KILL_TIMEOUT_SECONDS)
        .orElse(DEFAULT_DISTRIBUTED_BUILD_THREAD_KILL_TIMEOUT_SECONDS);
  }

  public boolean getPerformRuleKeyConsistencyCheck() {
    return buckConfig
        .getBoolean(STAMPEDE_SECTION, PERFORM_RULE_KEY_CONSISTENCY_CHECK)
        .orElse(DEFAULT_PERFORM_RULE_KEY_CONSISTENCY_CHECK);
  }

  public long getMinionPollLoopIntervalMillis() {
    return buckConfig
        .getLong(STAMPEDE_SECTION, MINION_POLL_LOOP_INTERVAL_MILLIS)
        .orElse(DEFAULT_MINION_POLL_LOOP_INTERVAL_MILLIS);
  }

  public boolean isDeepRemoteBuildEnabled() {
    return buckConfig.getBooleanValue(
        STAMPEDE_SECTION, ENABLE_DEEP_REMOTE_BUILD, DEFAULT_ENABLE_DEEP_REMOTE_BUILD);
  }

  public boolean isAsyncLoggingEnabled() {
    return buckConfig.getBooleanValue(
        STAMPEDE_SECTION, ENABLE_ASYNC_LOGGING, DEFAULT_ENABLE_ASYNC_LOGGING);
  }

  public boolean isCacheMissAnalysisEnabled() {
    return buckConfig.getBooleanValue(
        STAMPEDE_SECTION, ENABLE_CACHE_MISS_ANALYSIS, DEFAULT_ENABLE_CACHE_MISS_ANALYSIS);
  }

  /**
   * If true, local Stampede client will wait for remote build of rule to complete before building
   * locally. If false, it will go ahead building locally if remote build of rule hasn't started
   * yet.
   *
   * @return
   */
  public boolean shouldAlwaysWaitForRemoteBuildBeforeProceedingLocally() {
    return buckConfig.getBooleanValue(
        STAMPEDE_SECTION,
        ALWAYS_WAIT_FOR_REMOTE_BUILD_BEFORE_PROCEEDING_LOCALLY,
        DEFAULT_ALWAYS_WAIT_FOR_REMOTE_BUILD_BEFORE_PROCEEDING_LOCALLY);
  }

  public long getHearbeatServiceRateMillis() {
    return buckConfig
        .getLong(STAMPEDE_SECTION, HEARTBEAT_SERVICE_INTERVAL_MILLIS)
        .orElse(DEFAULT_HEARTBEAT_SERVICE_INTERVAL_MILLIS);
  }

  public long getSlowHeartbeatWarningThresholdMillis() {
    return buckConfig
        .getLong(STAMPEDE_SECTION, SLOW_HEARTBEAT_WARNING_THRESHOLD_MILLIS)
        .orElse(DEFAULT_SLOW_HEARTBEAT_WARNING_THRESHOLD_MILLIS);
  }

  public long getMaxMinionSilenceMillis() {
    return buckConfig
        .getLong(STAMPEDE_SECTION, MAX_MINION_SILENCE_MILLIS)
        .orElse(DEFAULT_MAX_MINION_SILENCE_MILLIS);
  }

  public int getFrontendRequestMaxRetries() {
    return buckConfig
        .getInteger(STAMPEDE_SECTION, FRONTEND_REQUEST_MAX_RETRIES)
        .orElse(DEFAULT_FRONTEND_REQUEST_MAX_RETRIES);
  }

  public long getFrontendRequestRetryIntervalMillis() {
    return buckConfig
        .getLong(STAMPEDE_SECTION, FRONTEND_REQUEST_RETRY_INTERVAL_MILLIS)
        .orElse(DEFAULT_FRONTEND_REQUEST_RETRY_INTERVAL_MILLIS);
  }

  public int getControllerMaxThreadCount() {
    return buckConfig
        .getInteger(STAMPEDE_SECTION, CONTROLLER_MAX_THREAD_COUNT)
        .orElse(DEFAULT_CONTROLLER_MAX_THREAD_COUNT);
  }

  public int getMostBuildRulesFinishedPercentageThreshold() {
    return buckConfig
        .getInteger(STAMPEDE_SECTION, MOST_BUILD_RULES_FINISHED_PERCENTAGE_THRESHOLD)
        .orElse(DEFAULT_MOST_BUILD_RULES_FINISHED_PERCENTAGE_THRESHOLD);
  }

  /** @return Ratio of available build capacity that should be used by coordinator */
  public double getCoordinatorBuildCapacityRatio() {
    Optional<String> configValue =
        buckConfig.getValue(STAMPEDE_SECTION, COORDINATOR_BUILD_CAPACITY_RATIO);
    return configValue.isPresent()
        ? Double.valueOf(configValue.get())
        : DEFAULT_COORDINATOR_BUILD_CAPACITY_RATIO;
  }

  /** @return Ratio of available build capacity that should be used by minions */
  public double getMinionBuildCapacityRatio() {
    Optional<String> configValue =
        buckConfig.getValue(STAMPEDE_SECTION, MINION_BUILD_CAPACITY_RATIO);
    return configValue.isPresent()
        ? Double.valueOf(configValue.get())
        : DEFAULT_MINION_BUILD_CAPACITY_RATIO;
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

  public boolean isUploadFromLocalCacheEnabled() {
    return buckConfig.getBooleanValue(
        STAMPEDE_SECTION, ENABLE_UPLOADS_FROM_LOCAL_CACHE, DEFAULT_ENABLE_UPLOADS_FROM_LOCAL_CACHE);
  }

  public long getFileMaterializationTimeoutSecs() {
    return buckConfig
        .getLong(STAMPEDE_SECTION, FILE_MATERIALIZATION_TIMEOUT_SECS)
        .orElse(DEFAULT_FILE_MATERIALIZATION_TIMEOUT_SECS);
  }

  /** Whether a non-distributed build should be automatically turned into a distributed one. */
  public boolean shouldUseDistributedBuild(BuildId buildId) {
    boolean userInAutoStampedeControlGroup =
        buckConfig.getBooleanValue(
            EXPERIMENTS_SECTION, STAMPEDE_BETA_TEST, DEFAULT_STAMPEDE_BETA_TEST);
    if (!userInAutoStampedeControlGroup) {
      return false;
    }

    AutoStampedeMode enabled =
        buckConfig
            .getEnum(STAMPEDE_SECTION, AUTO_STAMPEDE_BUILD_ENABLED, AutoStampedeMode.class)
            .orElse(AutoStampedeMode.DEFAULT)
            .resolveExperiment(buildId);

    LOG.info("Should use distributed build: %s", enabled);
    return enabled.equals(AutoStampedeMode.TRUE);
  }

  public Optional<String> getAutoDistributedBuildMessage() {
    return buckConfig.getValue(STAMPEDE_SECTION, AUTO_STAMPEDE_BUILD_MESSAGE);
  }

  public int getCacheSynchronizationSafetyMarginMillis() {
    return buckConfig
        .getInteger(STAMPEDE_SECTION, CACHE_SYNCHRONIZATION_SAFETY_MARGIN_MILLIS)
        .orElse(DEFAULT_CACHE_SYNCHRONIZATION_SAFETY_MARGIN_MILLIS);
  }

  public OkHttpClient createOkHttpClient() {
    return new OkHttpClient.Builder()
        .connectTimeout(getFrontendRequestTimeoutMillis(), TimeUnit.MILLISECONDS)
        .readTimeout(getFrontendRequestTimeoutMillis(), TimeUnit.MILLISECONDS)
        .writeTimeout(getFrontendRequestTimeoutMillis(), TimeUnit.MILLISECONDS)
        .build();
  }
}
