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

package com.facebook.buck.rules.modern.config;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import org.immutables.value.Value;

/** Various configuration for ModernBuildRule behavior. */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractModernBuildRuleConfig implements ConfigView<BuckConfig> {
  public static final String SECTION = "modern_build_rule";

  public static final int DEFAULT_REMOTE_PORT = 19080;

  public Strategy getBuildStrategy() {
    return getDelegate().getEnum(SECTION, "strategy", Strategy.class).orElse(Strategy.DEFAULT);
  }

  public String getRemoteHost() {
    return getDelegate().getValue(SECTION, "remote_host").orElse("localhost");
  }

  public int getRemotePort() {
    return getDelegate().getInteger(SECTION, "remote_port").orElse(19030);
  }

  /**
   * These are the supported strategies.
   *
   * <p>Strategies starting with DEBUG_ aren't particularly useful in production and are just meant
   * for development.
   */
  public enum Strategy {
    NONE,

    GRPC_REMOTE,

    DEBUG_GRPC_SERVICE_IN_PROCESS,

    DEBUG_ISOLATED_OUT_OF_PROCESS,
    DEBUG_ISOLATED_OUT_OF_PROCESS_GRPC,

    DEBUG_ISOLATED_IN_PROCESS,
    // Creates a strategy that serializes and deserializes ModernBuildRules in memory and then
    // builds the deserialized version.
    DEBUG_RECONSTRUCT,
    // Creates a strategy that just forwards to the default behavior.
    DEBUG_PASSTHROUGH;

    private static final Strategy DEFAULT = NONE;
  }
}
