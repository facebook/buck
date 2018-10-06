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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.rules.modern.config.ModernBuildRuleBuildStrategy;
import com.facebook.buck.rules.modern.config.ModernBuildRuleConfig;
import java.util.Optional;
import org.immutables.value.Value;

/** Config object for the [remoteexecution] section of .buckconfig. */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractRemoteExecutionConfig implements ConfigView<BuckConfig> {
  public static final Logger LOG = Logger.get(RemoteExecutionConfig.class);
  public static final String SECTION = "remoteexecution";
  public static final String OLD_SECTION = ModernBuildRuleConfig.SECTION;

  public static final String CONSOLE_CAS_ENABLED_CONFIG = "show_cas_stats_on_console";
  public static final String CONSOLE_ACTIONS_ENABLED_CONFIG = "show_action_stats_on_console";

  public static final int DEFAULT_REMOTE_PORT = 19030;
  public static final int DEFAULT_CAS_PORT = 19031;

  public int getMaxNumberOfRemoteWorkers() {
    return getDelegate().getInteger(SECTION, "max_number_of_remote_workers").orElse(80);
  }

  public boolean useClientPool() {
    return getDelegate().getBooleanValue(SECTION, "enable_client_pool", true);
  }

  public boolean useWorkerThreadPool() {
    return getDelegate().getBooleanValue(SECTION, "enable_worker_thread_pool", true);
  }

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
      case DEBUG_RECONSTRUCT:
      case DEBUG_PASSTHROUGH:
      case DEBUG_ISOLATED_IN_PROCESS:
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

  public Optional<String> getTraceID() {
    return getValueWithFallback("trace_id");
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

  /** Whether SuperConsole output of Remote Execution information is enabled. */
  public boolean isSuperConsoleEnabled() {
    return isSuperConsoleEnabledForActionStats() || isSuperConsoleEnabledForCasStats();
  }

  /** Print CAS statistics to the Super Console. */
  public boolean isSuperConsoleEnabledForCasStats() {
    return getDelegate().getBooleanValue(SECTION, CONSOLE_CAS_ENABLED_CONFIG, true);
  }

  /** Print Action statistics to the Super Console. */
  public boolean isSuperConsoleEnabledForActionStats() {
    return getDelegate().getBooleanValue(SECTION, CONSOLE_ACTIONS_ENABLED_CONFIG, true);
  }
}
