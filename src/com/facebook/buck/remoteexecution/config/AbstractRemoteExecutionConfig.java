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

package com.facebook.buck.remoteexecution.config;

import static com.facebook.buck.rules.modern.config.ModernBuildRuleBuildStrategy.REMOTE;

import com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.remoteexecution.proto.RESessionID;
import com.facebook.buck.rules.modern.config.ModernBuildRuleBuildStrategy;
import com.facebook.buck.rules.modern.config.ModernBuildRuleConfig;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.immutables.value.Value;

/** Config object for the [remoteexecution] section of .buckconfig. */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractRemoteExecutionConfig implements ConfigView<BuckConfig> {
  public static final Logger LOG = Logger.get(RemoteExecutionConfig.class);
  public static final String SECTION = "remoteexecution";
  public static final String OLD_SECTION = ModernBuildRuleConfig.SECTION;

  public static final int DEFAULT_REMOTE_PORT = 19030;
  public static final int DEFAULT_CAS_PORT = 19031;

  public static final int DEFAULT_REMOTE_STRATEGY_THREADS = 12;
  public static final int DEFAULT_REMOTE_CONCURRENT_ACTION_COMPUTATIONS = 4;
  public static final int DEFAULT_REMOTE_CONCURRENT_PENDING_UPLOADS = 100;
  public static final int DEFAULT_REMOTE_CONCURRENT_EXECUTIONS = 80;
  public static final int DEFAULT_REMOTE_CONCURRENT_RESULT_HANDLING = 6;
  public static final boolean DEFAULT_IS_LOCAL_FALLBACK_ENABLED = false;

  private static final String CONFIG_CERT = "cert";
  private static final String CONFIG_KEY = "key";

  /**
   * Limit on the number of outstanding execution requests. This is probably the value that's most
   * likely to be non-default.
   */
  public static final String CONCURRENT_EXECUTIONS_KEY = "concurrent_executions";
  /**
   * Number of actions to compute at a time. These should be <25ms average, but require I/O and cpu.
   */
  public static final String CONCURRENT_ACTION_COMPUTATIONS_KEY = "concurrent_action_computations";
  /**
   * Number of results to concurrently handle at a time. This is mostly just downloading outputs
   * which is currently a blocking operation.
   */
  public static final String CONCURRENT_RESULT_HANDLING_KEY = "concurrent_result_handling";
  /** Whether failed remote executions are retried locally. */
  public static final String IS_LOCAL_FALLBACK_ENABLED_KEY = "is_local_fallback_enabled";
  /** The maximum size of inputs allowed on remote execution, if unset, no maximum. */
  public static final String MAX_INPUT_SIZE_BYTES = "max_input_size_bytes";
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
   * Free form string label that can be passed along any Remote Execution session. Useful for
   * logging.
   */
  public static final String RE_SESSION_LABEL_KEY = "re_session_label";

  /** Worker requirements filename */
  public static final String WORKER_REQUIREMENTS_FILENAME = "worker_requirements_filename";

  // Should ree try to reschedule OOMed action on a larger worker
  public static final String TRY_LARGER_WORKER_ON_OOM = "try_larger_worker_on_oom";

  public String getRemoteHost() {
    return getValueWithFallback("remote_host").orElse("localhost");
  }

  public int getRemotePort() {
    return getValueWithFallback("remote_port").map(Integer::parseInt).orElse(DEFAULT_REMOTE_PORT);
  }

  public String getCasHost() {
    return getValueWithFallback("cas_host").orElse("localhost");
  }

  public int getCasPort() {
    return getValueWithFallback("cas_port").map(Integer::parseInt).orElse(DEFAULT_CAS_PORT);
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
    return getValueWithFallback(DEBUG_FORMAT_STRING_URL_KEY)
        .orElse(FORMAT_SESSION_ID_VARIABLE_STRING);
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

    boolean isLocalFallbackEnabled =
        getDelegate()
            .getBooleanValue(
                SECTION, IS_LOCAL_FALLBACK_ENABLED_KEY, DEFAULT_IS_LOCAL_FALLBACK_ENABLED);
    OptionalInt maxInputSizeBytes = getDelegate().getInteger(SECTION, MAX_INPUT_SIZE_BYTES);

    String workerRequirementsFilename =
        getDelegate()
            .getValue(SECTION, WORKER_REQUIREMENTS_FILENAME)
            .orElse("re_worker_requirements");

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

    if (workerThreads < concurrentResultHandling) {
      LOG.error(
          "%s.%s=%d will be limited by %s.%s=%d",
          SECTION,
          CONCURRENT_ACTION_COMPUTATIONS_KEY,
          concurrentResultHandling,
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
      public boolean isLocalFallbackEnabled() {
        return isLocalFallbackEnabled;
      }

      @Override
      public OptionalInt maxInputSizeBytes() {
        return maxInputSizeBytes;
      }

      @Override
      public String getWorkerRequirementsFilename() {
        return workerRequirementsFilename;
      }

      @Override
      public boolean tryLargerWorkerOnOom() {
        return tryLargerWorkerOnOom;
      }
    };
  }

  public RemoteExecutionType getType() {
    Optional<RemoteExecutionType> specifiedType =
        getDelegate().getEnum(SECTION, "type", RemoteExecutionType.class);
    if (specifiedType.isPresent()) {
      return specifiedType.get();
    }

    ModernBuildRuleBuildStrategy fallbackStrategy =
        getDelegate().getView(ModernBuildRuleConfig.class).getBuildStrategy();
    // Try the old modern_build_rule way.
    switch (fallbackStrategy) {
      case HYBRID_LOCAL:
      case DEBUG_RECONSTRUCT:
      case DEBUG_PASSTHROUGH:
      case REMOTE:
      case NONE:
        break;

      case GRPC_REMOTE:
        specifiedType = Optional.of(RemoteExecutionType.GRPC);
        break;
      case DEBUG_ISOLATED_OUT_OF_PROCESS_GRPC:
        specifiedType = Optional.of(RemoteExecutionType.DEBUG_GRPC_IN_PROCESS);
        break;
      case DEBUG_GRPC_SERVICE_IN_PROCESS:
        specifiedType = Optional.of(RemoteExecutionType.DEBUG_GRPC_LOCAL);
        break;
    }

    if (!specifiedType.isPresent()) {
      return RemoteExecutionType.DEFAULT;
    }

    String currentConfig = String.format("%s.strategy=%s", OLD_SECTION, fallbackStrategy);
    String newStrategy = String.format("%s.strategy=%s", OLD_SECTION, REMOTE);
    String newRemoteType = String.format("%s.type=%s", SECTION, specifiedType.get());

    LOG.error(
        "Configuration %s is deprecated. This should be configured by setting both %s and %s.",
        currentConfig, newStrategy, newRemoteType);
    return specifiedType.get();
  }

  public Optional<String> getValueWithFallback(String key) {
    Optional<String> value = getDelegate().getValue(SECTION, key);
    if (value.isPresent()) {
      return value;
    }
    value = getDelegate().getValue(OLD_SECTION, key);
    if (value.isPresent()) {
      LOG.error(
          "Configuration should be specified with %s.%s, not %s.%s.",
          SECTION, key, OLD_SECTION, key);
    }
    return value;
  }

  /** Whether Console output of Remote Execution information is enabled. */
  public boolean isConsoleEnabled() {
    return getType() != RemoteExecutionType.NONE;
  }

  public String getReSessionLabel() {
    return getDelegate().getValue(SECTION, RE_SESSION_LABEL_KEY).orElse("");
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

  private Optional<String> getErrorOnInvalidFile(String configName, Optional<Path> certPath) {
    if (!certPath.isPresent()) {
      return Optional.of(
          String.format("Config [%s.%s] must point to a file.", SECTION, configName));
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
}
