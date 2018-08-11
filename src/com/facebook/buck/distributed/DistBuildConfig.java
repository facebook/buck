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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.distributed.thrift.MinionRequirements;
import com.facebook.buck.distributed.thrift.MinionType;
import com.facebook.buck.distributed.thrift.SchedulingEnvironmentType;
import com.facebook.buck.log.Logger;
import com.facebook.buck.slb.SlbBuckConfig;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.config.RawConfig;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import okhttp3.Connection;
import okhttp3.OkHttpClient;
import okhttp3.Response;

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
  private static final Integer NUMBER_OF_MINIONS_DEFAULT_VALUE = 0;

  // List of top-level projects to be attempted to run with Stampede.
  private static final String AUTO_STAMPEDE_PROJECT_WHITELIST = "auto_stampede_project_whitelist";

  // Users on this list will not get automatic Stampede builds, even if building a target
  // that is part of a whitelisted proejct.
  private static final String AUTO_STAMPEDE_USER_BLACKLIST = "auto_stampede_user_blacklist";

  private static final String NUMBER_OF_LOW_SPEC_MINIONS = "number_of_low_spec_minions";

  private static final String JOB_NAME_ENVIRONMENT_VARIABLE = "job_name_environment_variable";
  private static final String TAKS_ID_ENVIRONMENT_VARIABLE = "task_id_environment_variable";
  private static final String REPOSITORY = "repository";
  private static final String DEFAULT_REPOSITORY = "";
  private static final String TENANT_ID = "tenant_id";
  private static final String DEFAULT_TENANT_ID = "";

  private static final String BUILD_LABEL = "build_label";
  private static final String DEFAULT_BUILD_LABEL = "";

  private static final String MINION_QUEUE = "minion_queue";
  private static final String LOW_SPEC_MINION_QUEUE = "low_spec_minion_queue";
  private static final String MINION_REGION = "minion_region";

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

  private static final String BUILD_SLAVE_REQUEST_MAX_RETRIES = "build_slave_request_max_retries";
  private static final int DEFAULT_BUILD_SLAVE_REQUEST_MAX_RETRIES = 3;

  private static final String BUILD_SLAVE_REQUEST_RETRY_INTERVAL_MILLIS =
      "build_slave_request_retry_interval_millis";
  private static final long DEFAULT_BUILD_SLAVE_REQUEST_RETRY_INTERVAL_MILLIS = 500;

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

  private static final String MAX_CONSECUTIVE_SLOW_DEAD_MINION_CHECKS =
      "max_consecutive_slow_dead_minion_checks";
  private static final int DEFAULT_MAX_CONSECUTIVE_SLOW_DEAD_MINION_CHECKS = 3;

  private static final String ENABLE_DEEP_REMOTE_BUILD = "enable_deep_remote_build";
  private static final boolean DEFAULT_ENABLE_DEEP_REMOTE_BUILD = false;

  private static final String ENABLE_ASYNC_LOGGING = "enable_async_logging";
  private static final boolean DEFAULT_ENABLE_ASYNC_LOGGING = true;

  private static final String ENABLE_CACHE_MISS_ANALYSIS = "enable_cache_miss_analysis";
  private static final boolean DEFAULT_ENABLE_CACHE_MISS_ANALYSIS = false;

  private static final String MINION_TYPE = "minion_type";
  private static final String DEFAULT_MINION_TYPE = MinionType.STANDARD_SPEC.name();

  private static final String ENVIRONMENT_TYPE = "environment_type";
  private static final String DEFAULT_ENVIRONMENT_TYPE =
      SchedulingEnvironmentType.IDENTICAL_HARDWARE.name();

  public static final String LOCAL_MODE = "local_mode";
  public static final DistLocalBuildMode DEFAULT_LOCAL_MODE = DistLocalBuildMode.WAIT_FOR_REMOTE;

  private static final String MOST_BUILD_RULES_FINISHED_PERCENTAGE_THRESHOLD =
      "most_build_rules_finished_percentage_threshold";
  private static final int DEFAULT_MOST_BUILD_RULES_FINISHED_PERCENTAGE_THRESHOLD = 80;

  private static final String ENABLE_UPLOADS_FROM_LOCAL_CACHE = "enable_uploads_from_local_cache";
  private static final boolean DEFAULT_ENABLE_UPLOADS_FROM_LOCAL_CACHE = false;

  private static final String ENABLE_RELEASING_MINIONS_EARLY = "enable_releasing_minions_early";
  private static final boolean DEFAULT_ENABLE_RELEASING_MINIONS_EARLY = true;

  /**
   * While the experiments.stampede_beta_test flag is set to true, this flag can be used to
   * configure whether we want auto-stampede conversion for all builds, no builds, or some builds.
   * See {@link AutoStampedeMode}.
   */
  private static final String AUTO_STAMPEDE_EXPERIMENTS_ENABLED =
      "auto_stampede_experiments_enabled";

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

  private static final String BUILD_SELECTED_TARGETS_LOCALLY = "build_selected_targets_locally";
  private static final boolean DEFAULT_BUILD_SELECTED_TARGETS_LOCALLY = true;

  // Stacking BuckConfig keys.
  private static final String STACK_SIZE = "stacking_stack_size";
  private static final Integer STACK_SIZE_DEFAULT_VALUE = 1;

  private static final String SLAVE_SERVER_HTTP_PORT = "slave_server_http_port";
  private static final Integer SLAVE_SERVER_HTTP_PORT_DEFAULT_VALUE = 8080;

  private static final String ENABLE_GREEDY_STACKING = "enable_greedy_stacking";
  private static final boolean DEFAULT_ENABLE_GREEDY_STACKING = false;

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

  public Optional<String> getJobNameEnvironmentVariable() {
    return buckConfig.getValue(STAMPEDE_SECTION, JOB_NAME_ENVIRONMENT_VARIABLE);
  }

  public Optional<String> getTaskIdEnvironmentVariable() {
    return buckConfig.getValue(STAMPEDE_SECTION, TAKS_ID_ENVIRONMENT_VARIABLE);
  }

  public OptionalInt getSourceFileMultiFetchMaxBufferSize() {
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

  /** @return Total number of minions to be used in this build */
  public int getNumberOfMinions() {
    return buckConfig
        .getInteger(STAMPEDE_SECTION, NUMBER_OF_MINIONS)
        .orElse(NUMBER_OF_MINIONS_DEFAULT_VALUE);
  }

  /**
   * @return Number of standard spec minions to be used in mixed environment build. Default is total
   *     number of minions - 1.
   */
  public int getNumberOfLowSpecMinions() {
    return buckConfig
        .getInteger(STAMPEDE_SECTION, NUMBER_OF_LOW_SPEC_MINIONS)
        .orElse(getNumberOfMinions() - 1);
  }

  public Optional<String> getMinionQueue() {
    return buckConfig.getValue(STAMPEDE_SECTION, MINION_QUEUE);
  }

  public Optional<String> getLowSpecMinionQueue() {
    return buckConfig.getValue(STAMPEDE_SECTION, LOW_SPEC_MINION_QUEUE);
  }

  public Optional<String> getMinionRegion() {
    return buckConfig.getValue(STAMPEDE_SECTION, MINION_REGION);
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
   * This returns the mode that the local will have, either wait for remote build, run at the same
   * time or fire up the remote build and shut down local client.
   *
   * @return the mode.
   */
  public DistLocalBuildMode getLocalBuildMode() {
    return buckConfig
        .getEnum(STAMPEDE_SECTION, LOCAL_MODE, DistLocalBuildMode.class)
        .orElse(DEFAULT_LOCAL_MODE);
  }

  public long getHeartbeatServiceRateMillis() {
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

  public int getMaxConsecutiveSlowDeadMinionChecks() {
    return buckConfig
        .getInteger(STAMPEDE_SECTION, MAX_CONSECUTIVE_SLOW_DEAD_MINION_CHECKS)
        .orElse(DEFAULT_MAX_CONSECUTIVE_SLOW_DEAD_MINION_CHECKS);
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

  public int getBuildSlaveRequestMaxRetries() {
    return buckConfig
        .getInteger(STAMPEDE_SECTION, BUILD_SLAVE_REQUEST_MAX_RETRIES)
        .orElse(DEFAULT_BUILD_SLAVE_REQUEST_MAX_RETRIES);
  }

  public long getBuildSlaveRequestRetryIntervalMillis() {
    return buckConfig
        .getLong(STAMPEDE_SECTION, BUILD_SLAVE_REQUEST_RETRY_INTERVAL_MILLIS)
        .orElse(DEFAULT_BUILD_SLAVE_REQUEST_RETRY_INTERVAL_MILLIS);
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
  public boolean shouldUseDistributedBuild(
      BuildId buildId, String username, List<String> commandArguments) {
    if (isAutoStampedeBlacklistedUser(username)) {
      return false; // Blacklisted users never get auto Stampede builds
    }

    if (DistBuildUtil.doTargetsMatchProjectWhitelist(
        commandArguments, getAutoStampedeProjectWhitelist(), buckConfig)) {
      // Builds of enabled projects always get auto Stampede builds
      LOG.info("Running auto Stampede build as targets matched project whitelist");
      return true;
    }

    // All other users get builds depending on in they are in an experiment control group,
    // and the current experiment setting resolves to true.
    boolean userInAutoStampedeControlGroup =
        buckConfig.getBooleanValue(
            EXPERIMENTS_SECTION, STAMPEDE_BETA_TEST, DEFAULT_STAMPEDE_BETA_TEST);
    if (!userInAutoStampedeControlGroup) {
      return false;
    }

    AutoStampedeMode enabled =
        buckConfig
            .getEnum(STAMPEDE_SECTION, AUTO_STAMPEDE_EXPERIMENTS_ENABLED, AutoStampedeMode.class)
            .orElse(AutoStampedeMode.DEFAULT)
            .resolveExperiment(buildId);

    LOG.info("Should use distributed build: %s", enabled);
    return enabled.equals(AutoStampedeMode.TRUE);
  }

  /** @return The hardware category for this minion (when running in minion mode). */
  public MinionType getMinionType() {
    String minionTypeStr =
        buckConfig.getValue(STAMPEDE_SECTION, MINION_TYPE).orElse(DEFAULT_MINION_TYPE);

    return MinionType.valueOf(minionTypeStr);
  }

  /** @return The hardware scheduling environment to be used for this distributed build */
  public SchedulingEnvironmentType getSchedulingEnvironmentType() {
    String environmentTypeStr =
        buckConfig.getValue(STAMPEDE_SECTION, ENVIRONMENT_TYPE).orElse(DEFAULT_ENVIRONMENT_TYPE);

    return SchedulingEnvironmentType.valueOf(environmentTypeStr);
  }

  public Optional<String> getAutoDistributedBuildMessage() {
    return buckConfig.getValue(STAMPEDE_SECTION, AUTO_STAMPEDE_BUILD_MESSAGE);
  }

  public int getCacheSynchronizationSafetyMarginMillis() {
    return buckConfig
        .getInteger(STAMPEDE_SECTION, CACHE_SYNCHRONIZATION_SAFETY_MARGIN_MILLIS)
        .orElse(DEFAULT_CACHE_SYNCHRONIZATION_SAFETY_MARGIN_MILLIS);
  }

  public boolean shouldBuildSelectedTargetsLocally() {
    return buckConfig.getBooleanValue(
        STAMPEDE_SECTION, BUILD_SELECTED_TARGETS_LOCALLY, DEFAULT_BUILD_SELECTED_TARGETS_LOCALLY);
  }

  /** @return Size of maximum builds running on the same build slave */
  public int getStackSize() {
    return buckConfig.getInteger(STAMPEDE_SECTION, STACK_SIZE).orElse(STACK_SIZE_DEFAULT_VALUE);
  }

  /** @return Http server port the build slave is listening to on localhost */
  public int getBuildSlaveHttpPort() {
    return buckConfig
        .getInteger(STAMPEDE_SECTION, SLAVE_SERVER_HTTP_PORT)
        .orElse(SLAVE_SERVER_HTTP_PORT_DEFAULT_VALUE);
  }

  public boolean isGreedyStackingEnabled() {
    return buckConfig.getBooleanValue(
        STAMPEDE_SECTION, ENABLE_GREEDY_STACKING, DEFAULT_ENABLE_GREEDY_STACKING);
  }

  public OkHttpClient createOkHttpClient() {
    OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();
    clientBuilder
        .connectTimeout(getFrontendRequestTimeoutMillis(), TimeUnit.MILLISECONDS)
        .readTimeout(getFrontendRequestTimeoutMillis(), TimeUnit.MILLISECONDS)
        .writeTimeout(getFrontendRequestTimeoutMillis(), TimeUnit.MILLISECONDS);

    clientBuilder
        .networkInterceptors()
        .add(
            chain -> {
              String remoteAddress = null;
              Connection connection = chain.connection();
              if (connection != null) {
                remoteAddress = connection.socket().getRemoteSocketAddress().toString();
              }

              Response response = chain.proceed(chain.request());
              if (response.code() != 200 && remoteAddress != null) {
                LOG.warn(
                    String.format(
                        "Connection to %s failed with code %d", remoteAddress, response.code()));
              }
              return response;
            });

    return clientBuilder.build();
  }

  public MinionRequirements getMinionRequirements() {
    int totalMinions = getNumberOfMinions();
    int lowSpecMinions = getNumberOfLowSpecMinions();

    if (totalMinions <= 0 && lowSpecMinions <= totalMinions) {
      LOG.info("No specific minion requirements. Using empty specification.");
      return new MinionRequirements();
    }

    return DistBuildUtil.createMinionRequirements(
        getBuildMode(), getSchedulingEnvironmentType(), totalMinions, lowSpecMinions);
  }

  /** @return ImmutableSet of all projects that are white-listed for auto Stampede builds */
  public ImmutableSet<String> getAutoStampedeProjectWhitelist() {
    Optional<ImmutableList<String>> optionalListWithoutComments =
        buckConfig.getOptionalListWithoutComments(
            STAMPEDE_SECTION, AUTO_STAMPEDE_PROJECT_WHITELIST);

    return optionalListWithoutComments.isPresent()
        ? ImmutableSet.copyOf(optionalListWithoutComments.get())
        : ImmutableSet.of();
  }

  /** Checks if the given user is black-listed for auto Stampede builds */
  public boolean isAutoStampedeBlacklistedUser(String user) {
    Optional<ImmutableList<String>> optionalBlackListUsers =
        buckConfig.getOptionalListWithoutComments(STAMPEDE_SECTION, AUTO_STAMPEDE_USER_BLACKLIST);

    return optionalBlackListUsers.isPresent() && optionalBlackListUsers.get().contains(user);
  }

  public boolean isReleasingMinionsEarlyEnabled() {
    return buckConfig.getBooleanValue(
        STAMPEDE_SECTION, ENABLE_RELEASING_MINIONS_EARLY, DEFAULT_ENABLE_RELEASING_MINIONS_EARLY);
  }
}
