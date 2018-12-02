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

/**
 * These are the supported strategies.
 *
 * <p>Strategies starting with DEBUG_ aren't particularly useful in production and are just meant
 * for development.
 */
public enum ModernBuildRuleBuildStrategy {
  NONE,

  // This is used for all remote execution-based strategies.
  REMOTE,
  HYBRID_LOCAL,

  // Creates a strategy that serializes and deserializes ModernBuildRules in memory and then
  // builds the deserialized version.
  DEBUG_RECONSTRUCT,
  // Creates a strategy that just forwards to the default behavior.
  DEBUG_PASSTHROUGH,

  // The following are all deprecated. They should be configured with a combination of
  // modern_build_rule.strategy=remote and an appropriate value for remoteexecution.type.
  GRPC_REMOTE,
  DEBUG_GRPC_SERVICE_IN_PROCESS,
  /**
   * This strategy will construct a separate isolated build directory for each rule. The rule will
   * be serialized to data files in that directory, and all inputs required (including buck configs)
   * will be materialized there. Buck's own classpath will also be materialized there and then a
   * separate subprocess will be created to deserialize and run the rule. Outputs will then be
   * copied back to the real build directory.
   */
  DEBUG_ISOLATED_OUT_OF_PROCESS_GRPC,
  ;

  static final ModernBuildRuleBuildStrategy DEFAULT = NONE;
}
