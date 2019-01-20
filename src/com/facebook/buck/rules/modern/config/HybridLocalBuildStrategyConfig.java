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

/** Configuration for the "hybrid_local" build strategy. */
public class HybridLocalBuildStrategyConfig {
  private final int localJobs;
  private final int delegateJobs;
  private final ModernBuildRuleStrategyConfig delegate;

  public HybridLocalBuildStrategyConfig(
      int localJobs, int delegateJobs, ModernBuildRuleStrategyConfig delegate) {
    this.localJobs = localJobs;
    this.delegateJobs = delegateJobs;
    this.delegate = delegate;
  }

  public ModernBuildRuleStrategyConfig getDelegateConfig() {
    return delegate;
  }

  public int getLocalJobs() {
    return localJobs;
  }

  public int getDelegateJobs() {
    return delegateJobs;
  }
}
