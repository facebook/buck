/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.config;

import com.facebook.buck.util.randomizedtrial.WithProbability;

public enum ActionGraphParallelizationMode implements WithProbability {
  ENABLED(0.5),
  DISABLED(0.5),
  EXPERIMENT(0.0),
  EXPERIMENT_UNSTABLE(0.0),
  ;

  public static ActionGraphParallelizationMode DEFAULT = DISABLED;

  private final double probability;

  ActionGraphParallelizationMode(double probability) {
    this.probability = probability;
  }

  @Override
  public double getProbability() {
    return probability;
  }
}
