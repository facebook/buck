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

package com.facebook.buck.versions;

import com.facebook.buck.util.randomizedtrial.WithProbability;

/**
 * Whether to use the {@link com.facebook.buck.versions.AsyncVersionedTargetGraphBuilder} or {@link
 * com.facebook.buck.versions.ParallelVersionedTargetGraphBuilder}
 */
public enum VersionTargetGraphMode implements WithProbability {
  ENABLED(0.5),
  /** uses graph engine with {@link
   * com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutorWithLocalStack
   */
  ENABLED_LS(0),
  /** uses graph engine with {@link
   * com.facebook.buck.core.graph.transformation.executor.impl.JavaExecutorBackedDefaultDepsAwareExecutor
   */
  ENABLED_JE(0),
  DISABLED(0.5),
  EXPERIMENT(0.0),
  ;

  public static final VersionTargetGraphMode DEFAULT = DISABLED;

  private final double probability;

  VersionTargetGraphMode(double probability) {
    this.probability = probability;
  }

  @Override
  public double getProbability() {
    return probability;
  }
}
