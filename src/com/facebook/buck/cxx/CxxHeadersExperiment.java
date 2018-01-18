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

package com.facebook.buck.cxx;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ExperimentEvent;
import com.facebook.buck.util.randomizedtrial.RandomizedTrial;
import com.facebook.buck.util.randomizedtrial.WithProbability;
import com.google.common.annotations.VisibleForTesting;

/**
 * Class used for AB testing of deferring CxxHeaders.checkConflictingHeaders() to build stage for
 * improving action graph construction time.
 *
 * <p>This is temporary and will be deleted
 */
public class CxxHeadersExperiment {

  /** Mode of Experiment */
  public static enum Mode implements WithProbability {
    // 50% chance of running experiment or not
    // Enum with double is safe with out arithmetic operations as assignment will result in the same
    // rounding
    ENABLE_DEFER(0.5),
    DISABLED(0.5),
    DEFAULT(0.0),
    ;

    private final double prob;

    Mode(double prob) {
      this.prob = prob;
    }

    @Override
    public double getProbability() {
      return prob;
    }
  };

  @VisibleForTesting static Mode mode = Mode.DEFAULT;

  /**
   * Starts the experiment, posting an event to eventBus
   *
   * @param eventBus
   */
  public static void startExperiment(BuckEventBus eventBus) {
    if (mode != Mode.DEFAULT) {
      return;
    }
    mode =
        RandomizedTrial.getGroup(
            CxxHeadersExperiment.EXPERIMENT_NAME,
            eventBus.getBuildId().toString(),
            CxxHeadersExperiment.Mode.class);
    eventBus.post(
        new ExperimentEvent(CxxHeadersExperiment.EXPERIMENT_NAME, mode.toString(), "", null, null));
  }

  /** @return whether to run the experiment */
  public static boolean runExperiment() {
    return mode == Mode.ENABLE_DEFER;
  }

  public static final String EXPERIMENT_NAME = "defer_header_experiment";
}
