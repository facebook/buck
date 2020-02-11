/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.remoteexecution.config;

import static com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig.getEnvVarFieldNameForField;

import com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.GlobPatternMatcher;
import com.facebook.buck.io.filesystem.PathMatcher;
import com.facebook.buck.remoteexecution.proto.RESessionID;
import com.facebook.buck.remoteexecution.proto.WorkerRequirements;
import com.facebook.buck.remoteexecution.util.RemoteExecutionUtil;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.immutables.value.Value;

/** Config object for the [remoteexecution] section of .buckconfig. */
@BuckStyleValue
public abstract class RemoteExecutionConfig implements ConfigView<BuckConfig> {
  public static final Logger LOG = Logger.get(RemoteExecutionConfig.class);
  public static final String SECTION = "remoteexecution";

  public static final int DEFAULT_REMOTE_PORT = 19030;
  public static final int DEFAULT_CAS_PORT = 19031;
  public static final int DEFAULT_CAS_DEADLINE_S = 300;

  public static final int DEFAULT_REMOTE_STRATEGY_THREADS = 12;
  public static final int DEFAULT_REMOTE_CONCURRENT_ACTION_COMPUTATIONS = 4;
  public static final int DEFAULT_REMOTE_CONCURRENT_PENDING_UPLOADS = 100;
  public static final int DEFAULT_REMOTE_CONCURRENT_EXECUTIONS = 80;
  public static final int DEFAULT_REMOTE_CONCURRENT_RESULT_HANDLING = 6;
  public static final int DEFAULT_REMOTE_OUTPUT_MATERIALIZATION_THREADS = 4;
  public static final boolean DEFAULT_IS_LOCAL_FALLBACK_ENABLED = false;
  public static final boolean DEFAULT_IS_LOCAL_FALLBACK_DISABLED_ON_CORRUPT_ARTIFACTS = false;
  public static final boolean DEFAULT_IS_LOCAL_FALLBACK_ENABLED_FOR_COMPLETED_ACTION = true;

  private static final String CONFIG_CERT = "cert";
  private static final String CONFIG_KEY = "key";

  /** Tenant ID that will be attached to each action * */
  public static final String TENANT_ID_KEY = "tenant_id";

  /**
   * Limit on the number of outstanding execution requests. This is probably the value that's most
   * likely to be non-default.
   */
  public static final String CONCURRENT_EXECUTIONS_KEY = "concurrent_executions";
  /**
   * Number of actions to compute at a time. These should be <25ms average, but require I/O and cpu.
   */
  public static final String CONCURRENT_ACTION_COMPUTATIONS_KEY = "concurrent_action_computations";
  /** Number of results to concurrently handle at a time. */
  public static final String CONCURRENT_RESULT_HANDLING_KEY = "concurrent_result_handling";
  /** Number of threads to handle output materialization. */
  public static final String OUTPUT_MATERIALIZATION_THREADS_KEY = "output_materialization_threads";
  /** Whether failed remote executions are retried locally. */
  public static final String IS_LOCAL_FALLBACK_ENABLED_KEY = "is_local_fallback_enabled";
  /** Whether failed remote executions are retried locally if the artifacts are corrupted. */
  public static final String IS_LOCAL_FALLBACK_DISABLED_ON_CORRUPT_ARTIFACTS_KEY =
      "is_local_fallback_disabled_on_corrupt_artifacts";
  /** The maximum size of inputs allowed on remote execution, if unset, no maximum. */
  public static final String MAX_INPUT_SIZE_BYTES = "max_input_size_bytes";
  /** The large blob size bytes threshold, if unset, no threshold. */
  public static final String LARGE_BLOB_SIZE_BYTES = "large_blob_size_bytes";
  /**
   * Number of threads for the strategy to do its work. This doesn't need to be a lot, but should
   * probably be greater than concurrent_result_handling below.
   */
  public static final String STRATEGY_WORKER_THREADS_KEY = "worker_threads";
  /**
   * Number of pending uploads at a time. While an action is pending uploads, it may hold a
   * reference to some large-ish data (>1MB). If this limit is reached, it will also block future
   * action computations until uploads finish.
   */
  public static final String CONCURRENT_PENDING_UPLOADS_KEY = "concurrent_pending_uploads";
  /** URL format string for debug UI on the super console */
  public static final String DEBUG_FORMAT_STRING_URL_KEY = "debug_format_string_url";
  /**
   * The variable identifier string to be replace in any configured format defined by
   * DEBUG_FORMAT_STRING_URL_KEY. This is being presented to avoid string duplication.
   */
  public static final String FORMAT_SESSION_ID_VARIABLE_STRING = "{id}";

  /**
   * Actions which require a worker of this size (or higher) will not be stolen locally when running
   * in hybrid mode
   */
  public static final String MAX_WORKER_SIZE_TO_STEAL_FROM = "max_worker_size_to_steal_from";

  /**
   * Free form string label that can be passed along any Remote Execution session. Useful for
   * logging.
   */
  public static final String RE_SESSION_LABEL_KEY = "re_session_label";

  /** Worker requirements filename */
  public static final String WORKER_REQUIREMENTS_FILENAME = "worker_requirements_filename";

  // Should retry to reschedule OOMed action on a larger worker
  public static final String TRY_LARGER_WORKER_ON_OOM = "try_larger_worker_on_oom";

  // Should retry actions locally if action exit code is returned and is not 0.
  public static final String IS_LOCAL_FALLBACK_ENABLED_FOR_COMPLETED_ACTION_KEY =
      "is_local_fallback_enabled_for_completed_actions";

  public static final String AUTO_RE_BUILD_PROJECTS_WHITELIST_KEY =
      "auto_re_build_projects_whitelist";
  public static final String AUTO_RE_BUILD_USERS_BLACKLIST_KEY = "auto_re_build_users_blacklist";

  /** Input paths to ignore for actions */
  public static final String INPUT_IGNORE_KEY = "inputs_ignore";

  /**
   * Strategy used to determine whether to enable Remote Execution automatically for the current
   * build
   */
  public static final String AUTO_RE_STRATEGY_KEY = "auto_re_strategy";

  // Auxiliary flag used for setting custom build tags.
  public static final String BUILD_TAGS_KEY = "build_tags";

  // Property inside [experiments] section that will be used to enable Remote Execution
  // automatically or not
  public static final String AUTO_RE_EXPERIMENT_PROPERTY_KEY = "auto_re_experiment_property";

  public static final String DEFAULT_AUTO_RE_EXPERIMENT_PROPERTY = "remote_execution_beta_test";

  public static final String USE_REMOTE_EXECUTION_FOR_GENRULE_IF_REQUESTED_FORMAT =
      "use_remote_execution_for_%s_if_requested";

  // A non-exhaustive list of characters that might indicate that we're about to deal with a glob.
  private static final Pattern GLOB_CHARS = Pattern.compile("[*?{\\[]");

  private String getAutoReExperimentPropertyKey() {
    return getValue(AUTO_RE_EXPERIMENT_PROPERTY_KEY).orElse(DEFAULT_AUTO_RE_EXPERIMENT_PROPERTY);
  }

  public static RemoteExecutionConfig of(BuckConfig delegate) {
    return ImmutableRemoteExecutionConfig.of(delegate);
  }

  @VisibleForTesting
  boolean isExperimentEnabled() {
    return getDelegate().getBooleanValue("experiments", getAutoReExperimentPropertyKey(), false);
  }

  @VisibleForTesting
  public static String getUseRemoteExecutionForGenruleIfRequestedField(String type) {
    return String.format(USE_REMOTE_EXECUTION_FOR_GENRULE_IF_REQUESTED_FORMAT, type);
  }

  /**
   * Returns whether or not we should honor the `remote` argument to `genrule`, which requests that
   * the genrule run remotely.
   */
  public boolean shouldUseRemoteExecutionForGenruleIfRequested(String type) {
    return getDelegate()
        .getBooleanValue(SECTION, getUseRemoteExecutionForGenruleIfRequestedField(type), false);
  }

  public boolean isRemoteExecutionAutoEnabled(String username, List<String> commandArguments) {
    return isRemoteExecutionAutoEnabled(
        isBuildWhitelistedForRemoteExecution(username, commandArguments),
        isExperimentEnabled(),
        getAutoRemoteExecutionStrategy());
  }

  @VisibleForTesting
  static boolean isRemoteExecutionAutoEnabled(
      boolean whitelistedForRemoteExecution,
      boolean experimentEnabled,
      AutoRemoteExecutionStrategy autoRemoteExecutionStrategy) {
    switch (autoRemoteExecutionStrategy) {
      case DISABLED:
        return false;
      case RE_IF_EXPERIMENT_ENABLED:
        return experimentEnabled;
      case RE_IF_WHITELIST_MATCH:
        return whitelistedForRemoteExecution;
      case RE_IF_EXPERIMENT_ENABLED_AND_WHITELIST_MATCH:
        return experimentEnabled && whitelistedForRemoteExecution;
      case RE_IF_EXPERIMENT_ENABLED_OR_WHITELIST_MATCH:
        return experimentEnabled || whitelistedForRemoteExecution;
      default:
        return false;
    }
  }

  private AutoRemoteExecutionStrategy getAutoRemoteExecutionStrategy() {
    return getDelegate()
        .getEnum(SECTION, AUTO_RE_STRATEGY_KEY, AutoRemoteExecutionStrategy.class)
        .orElse(AutoRemoteExecutionStrategy.DEFAULT);
  }

  public String getRemoteHost() {
    return getValue("remote_host").orElse("localhost");
  }

  public int getRemotePort() {
    return getValue("remote_port").map(Integer::parseInt).orElse(DEFAULT_REMOTE_PORT);
  }

  public String getCasHost() {
    return getValue("cas_host").orElse("localhost");
  }

  public int getCasPort() {
    return getValue("cas_port").map(Integer::parseInt).orElse(DEFAULT_CAS_PORT);
  }

  public int getCasDeadline() {
    return getValue("cas_deadline_sec").map(Integer::parseInt).orElse(DEFAULT_CAS_DEADLINE_S);
  }

  public boolean getInsecure() {
    return getDelegate().getBooleanValue(SECTION, "insecure", false);
  }

  public boolean getCasInsecure() {
    return getDelegate().getBooleanValue(SECTION, "cas_insecure", false);
  }

  /** client TLS certificate file in PEM format */
  public Optional<Path> getCertFile() {
    return getPathWithEnv(CONFIG_CERT);
  }

  /** client TLS private key in PEM format */
  public Optional<Path> getKeyFile() {
    return getPathWithEnv(CONFIG_KEY);
  }

  /** file containing all TLS authorities to verify server certificate with (PEM format) */
  public Optional<Path> getCertificateAuthoritiesFile() {
    return getPathWithEnv("ca");
  }

  private String getDebugURLFormatString() {
    return getValue(DEBUG_FORMAT_STRING_URL_KEY).orElse(FORMAT_SESSION_ID_VARIABLE_STRING);
  }

  public Optional<WorkerRequirements.WorkerSize> getMaxWorkerSizeToStealFrom() {
    Optional<String> workerSizeOptional = getValue(MAX_WORKER_SIZE_TO_STEAL_FROM);

    if (!workerSizeOptional.isPresent()) {
      return Optional.empty();
    }

    WorkerRequirements.WorkerSize workerSize =
        WorkerRequirements.WorkerSize.valueOf(workerSizeOptional.get());
    if (workerSize == WorkerRequirements.WorkerSize.UNRECOGNIZED) {
      throw new IllegalArgumentException(
          "Invalid value given for key " + MAX_WORKER_SIZE_TO_STEAL_FROM);
    }

    return Optional.of(workerSize);
  }

  public String getTenantId() {
    return getValue(TENANT_ID_KEY).orElse("");
  }

  public String getDebugURLString(RESessionID reSessionID) {
    String formatDebugSessionIDString = getDebugURLFormatString();
    return formatDebugSessionIDString.replace(
        FORMAT_SESSION_ID_VARIABLE_STRING, reSessionID.getId());
  }

  public boolean isDebug() {
    return getDelegate().getBooleanValue(SECTION, "debug", false);
  }

  @Value.Derived
  public RemoteExecutionStrategyConfig getStrategyConfig() {
    int workerThreads =
        getDelegate()
            .getInteger(SECTION, STRATEGY_WORKER_THREADS_KEY)
            .orElse(DEFAULT_REMOTE_STRATEGY_THREADS);

    int concurrentActionComputations =
        getDelegate()
            .getInteger(SECTION, CONCURRENT_ACTION_COMPUTATIONS_KEY)
            .orElse(DEFAULT_REMOTE_CONCURRENT_ACTION_COMPUTATIONS);

    int concurrentPendingUploads =
        getDelegate()
            .getInteger(SECTION, CONCURRENT_PENDING_UPLOADS_KEY)
            .orElse(DEFAULT_REMOTE_CONCURRENT_PENDING_UPLOADS);

    int concurrentExecutions =
        getDelegate()
            .getInteger(SECTION, CONCURRENT_EXECUTIONS_KEY)
            .orElse(DEFAULT_REMOTE_CONCURRENT_EXECUTIONS);

    int concurrentResultHandling =
        getDelegate()
            .getInteger(SECTION, CONCURRENT_RESULT_HANDLING_KEY)
            .orElse(DEFAULT_REMOTE_CONCURRENT_RESULT_HANDLING);

    int outputMaterializationThreads =
        getDelegate()
            .getInteger(SECTION, OUTPUT_MATERIALIZATION_THREADS_KEY)
            .orElse(DEFAULT_REMOTE_OUTPUT_MATERIALIZATION_THREADS);

    boolean isLocalFallbackEnabled =
        getDelegate()
            .getBooleanValue(
                SECTION, IS_LOCAL_FALLBACK_ENABLED_KEY, DEFAULT_IS_LOCAL_FALLBACK_ENABLED);

    boolean isLocalFallbackDisabledOnCorruptedArtifacts =
        getDelegate()
            .getBooleanValue(
                SECTION,
                IS_LOCAL_FALLBACK_DISABLED_ON_CORRUPT_ARTIFACTS_KEY,
                DEFAULT_IS_LOCAL_FALLBACK_DISABLED_ON_CORRUPT_ARTIFACTS);

    boolean isLocalFallbackEnabledForCompletedAction =
        getDelegate()
            .getBooleanValue(
                SECTION,
                IS_LOCAL_FALLBACK_ENABLED_FOR_COMPLETED_ACTION_KEY,
                DEFAULT_IS_LOCAL_FALLBACK_ENABLED_FOR_COMPLETED_ACTION);

    OptionalLong maxInputSizeBytes =
        getDelegate()
            .getValue(SECTION, MAX_INPUT_SIZE_BYTES)
            .map(size -> OptionalLong.of(Long.parseLong(size)))
            .orElseGet(OptionalLong::empty);

    OptionalLong largeBlobSizeBytes =
        getDelegate()
            .getValue(SECTION, LARGE_BLOB_SIZE_BYTES)
            .map(size -> OptionalLong.of(Long.parseLong(size)))
            .orElseGet(OptionalLong::empty);

    String workerRequirementsFilename =
        getDelegate()
            .getValue(SECTION, WORKER_REQUIREMENTS_FILENAME)
            .orElse("re_worker_requirements");

    ImmutableSet.Builder<PathMatcher> builder = ImmutableSet.builder();
    getDelegate()
        .getListWithoutComments(SECTION, INPUT_IGNORE_KEY)
        .forEach(
            input -> {
              if (GLOB_CHARS.matcher(input).find()) {
                builder.add(GlobPatternMatcher.of(input));
              }
            });
    ImmutableSet<PathMatcher> ignorePaths = builder.build();

    boolean tryLargerWorkerOnOom =
        getDelegate().getBoolean(SECTION, TRY_LARGER_WORKER_ON_OOM).orElse(false);

    // Some of these values are also limited by other ones (e.g. synchronous work is limited by the
    // number of threads). We detect some of these cases and log an error to the user to help them
    // understand the behavior.
    if (workerThreads < concurrentActionComputations) {
      LOG.error(
          "%s.%s=%d will be limited by %s.%s=%d",
          SECTION,
          CONCURRENT_ACTION_COMPUTATIONS_KEY,
          concurrentActionComputations,
          SECTION,
          STRATEGY_WORKER_THREADS_KEY,
          workerThreads);
    }

    if (concurrentPendingUploads < concurrentActionComputations) {
      LOG.error(
          "%s.%s=%d will be limited by %s.%s=%d",
          SECTION,
          CONCURRENT_ACTION_COMPUTATIONS_KEY,
          concurrentActionComputations,
          SECTION,
          STRATEGY_WORKER_THREADS_KEY,
          concurrentPendingUploads);
    }

    return new RemoteExecutionStrategyConfig() {
      @Override
      public int getThreads() {
        return workerThreads;
      }

      @Override
      public int getMaxConcurrentActionComputations() {
        return concurrentActionComputations;
      }

      @Override
      public int getMaxConcurrentPendingUploads() {
        return concurrentPendingUploads;
      }

      @Override
      public int getMaxConcurrentExecutions() {
        return concurrentExecutions;
      }

      @Override
      public int getMaxConcurrentResultHandling() {
        return concurrentResultHandling;
      }

      @Override
      public int getOutputMaterializationThreads() {
        return outputMaterializationThreads;
      }

      @Override
      public boolean isLocalFallbackEnabled() {
        return isLocalFallbackEnabled;
      }

      @Override
      public boolean isLocalFallbackDisabledOnCorruptedArtifacts() {
        return isLocalFallbackDisabledOnCorruptedArtifacts;
      }

      @Override
      public boolean isLocalFallbackEnabledForCompletedAction() {
        return isLocalFallbackEnabledForCompletedAction;
      }

      @Override
      public OptionalLong maxInputSizeBytes() {
        return maxInputSizeBytes;
      }

      @Override
      public OptionalLong largeBlobSizeBytes() {
        return largeBlobSizeBytes;
      }

      @Override
      public String getWorkerRequirementsFilename() {
        return workerRequirementsFilename;
      }

      @Override
      public boolean tryLargerWorkerOnOom() {
        return tryLargerWorkerOnOom;
      }

      @Override
      public ImmutableSet<PathMatcher> getIgnorePaths() {
        return ignorePaths;
      }
    };
  }

  public RemoteExecutionType getType() {
    Optional<RemoteExecutionType> specifiedType =
        getDelegate().getEnum(SECTION, "type", RemoteExecutionType.class);
    return specifiedType.orElse(RemoteExecutionType.DEFAULT);
  }

  /** Whether Console output of Remote Execution information is enabled. */
  public boolean isConsoleEnabled() {
    return getType() != RemoteExecutionType.NONE;
  }

  public String getReSessionLabel() {
    return getValue(RE_SESSION_LABEL_KEY).orElse("");
  }

  /** Provides an auxiliary tag used for capturing any custom configurations. */
  public String getAuxiliaryBuildTag() {
    ImmutableList<String> tags =
        getDelegate()
            .getOptionalListWithoutComments(SECTION, BUILD_TAGS_KEY)
            .orElse(ImmutableList.of());
    return tags.stream().sorted().collect(Collectors.joining("::"));
  }

  public void validateCertificatesOrThrow() {
    List<String> errors = Lists.newArrayList();
    if (!getInsecure() || !getCasInsecure()) {
      getErrorOnInvalidFile(CONFIG_CERT, getCertFile()).ifPresent(errorMsg -> errors.add(errorMsg));
      getErrorOnInvalidFile(CONFIG_KEY, getKeyFile()).ifPresent(errorMsg -> errors.add(errorMsg));
    }

    if (getCertificateAuthoritiesFile().isPresent()) {
      Path caFile = getCertificateAuthoritiesFile().get();
      if (!Files.exists(caFile)) {
        errors.add(
            String.format(
                "Config [%s.ca] points to file [%s] that does not exist.", SECTION, caFile));
      }
    }

    if (!errors.isEmpty()) {
      throw new HumanReadableException(Joiner.on("\n").join(errors));
    }
  }

  private boolean isBuildWhitelistedForRemoteExecution(
      String username, List<String> commandArguments) {
    Optional<ImmutableList<String>> optionalUsersBlacklist =
        getDelegate().getOptionalListWithoutComments(SECTION, AUTO_RE_BUILD_USERS_BLACKLIST_KEY);
    if (optionalUsersBlacklist.isPresent() && optionalUsersBlacklist.get().contains(username)) {
      return false;
    }

    Optional<ImmutableList<String>> optionalProjectsWhitelist =
        getDelegate().getOptionalListWithoutComments(SECTION, AUTO_RE_BUILD_PROJECTS_WHITELIST_KEY);
    ImmutableSet<String> projectWhitelist =
        optionalProjectsWhitelist.isPresent()
            ? ImmutableSet.copyOf(optionalProjectsWhitelist.get())
            : ImmutableSet.of();
    return RemoteExecutionUtil.doTargetsMatchProjectWhitelist(
        commandArguments, projectWhitelist, getDelegate());
  }

  private Optional<String> getErrorOnInvalidFile(String configName, Optional<Path> certPath) {
    if (!certPath.isPresent()) {
      return Optional.of(
          String.format(
              "Config [%s.%s] must point to a file, value [%s] or [%s.%s] must be set to a valid file location, value [%s].",
              SECTION,
              configName,
              getDelegate().getValue(SECTION, configName).orElse(""),
              SECTION,
              getEnvVarFieldNameForField(configName),
              getDelegate().getValue(SECTION, getEnvVarFieldNameForField(configName)).orElse("")));
    }

    if (!Files.exists(certPath.get())) {
      return Optional.of(
          String.format(
              "Config [%s.%s] points to a file [%s] that does not exist.",
              SECTION, configName, certPath.get()));
    }

    return Optional.empty();
  }

  private Optional<Path> getPathWithEnv(String field) {
    return ArtifactCacheBuckConfig.getStringOrEnvironmentVariable(getDelegate(), SECTION, field)
        .map(Paths::get);
  }

  private Optional<String> getValue(String key) {
    return getDelegate().getValue(SECTION, key);
  }
}
