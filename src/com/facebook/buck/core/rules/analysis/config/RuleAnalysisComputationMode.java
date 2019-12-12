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

package com.facebook.buck.core.rules.analysis.config;

/**
 * Indicates what mode of {@link
 * com.facebook.buck.core.rules.analysis.computation.RuleAnalysisGraph} should be ran.
 */
public enum RuleAnalysisComputationMode {
  /** don't run {@link com.facebook.buck.core.rules.analysis.computation.RuleAnalysisGraph} */
  DISABLED,

  /**
   * run in combination with existing {@link com.facebook.buck.core.rules.ActionGraphBuilder} for
   * compatibility
   */
  COMPATIBLE,

  /**
   * same as {@link #COMPATIBLE}, but also with the ability for rule analysis to depend on providers
   * from action graph
   */
  PROVIDER_COMPATIBLE;

  public static final RuleAnalysisComputationMode DEFAULT = DISABLED;
}
