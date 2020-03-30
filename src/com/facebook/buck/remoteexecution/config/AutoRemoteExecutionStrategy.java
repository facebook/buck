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

/**
 * Strategy used to determine whether to enable Remote Execution automatically for the current
 * build. If it passes, experimental_strategy setting will be used for the build. Note: if
 * remoteexecution.strategy is explicitly set, this always takes priority over auto RE settings
 * (i.e. remoteexecution.strategy=NONE means RE is always disabled)
 */
public enum AutoRemoteExecutionStrategy {
  DISABLED,

  // Use Remote Execution if experiments.remote_execution_beta_test=true
  RE_IF_EXPERIMENT_ENABLED,

  // Use Remote Execution if target being built passes auto_re_build_projects_whitelist
  // (and user isn't blacklisted)
  RE_IF_WHITELIST_MATCH,

  // Use Remote Execution if conditions for both RE_IF_EXPERIMENT_ENABLED and RE_IF_WHITELIST_MATCH
  // are true.
  RE_IF_EXPERIMENT_ENABLED_AND_WHITELIST_MATCH,

  // Use Remote Execution if either condition for RE_IF_EXPERIMENT_ENABLED or RE_IF_WHITELIST_MATCH
  // are true.
  RE_IF_EXPERIMENT_ENABLED_OR_WHITELIST_MATCH;

  public static final AutoRemoteExecutionStrategy DEFAULT =
      RE_IF_EXPERIMENT_ENABLED_OR_WHITELIST_MATCH;
}
