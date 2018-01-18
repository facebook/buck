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

package com.facebook.buck.rules;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ExperimentEvent;
import com.facebook.buck.util.randomizedtrial.RandomizedTrial;
import com.facebook.buck.util.randomizedtrial.WithProbability;

/**
 * Class used for AB testing of CxxSymlinkTreeHeader.getDeps() caching for improving action graph
 * construction time.
 *
 * <p>This is temporary and will be deleted
 */
public class CxxSymlinkTreeHeadersExperiment {

  /** Mode of Experiment */
  public static enum Mode implements WithProbability {
    // 50% chance of running experiment or not.
    // ENUM with double is safe without arithmetic operation as assignment will result in the same
    // rounding.
    ENABLE_CACHE(0.5),
    DISABLED(0.5),
    DEFAULT(0),
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

  private static Mode mode = Mode.DEFAULT;

  /**
   * Starts the experiment, posting an event to eventBus
   *
   * @param eventBus
   */
  public static void startExperiment(BuckEventBus eventBus) {
    mode =
        RandomizedTrial.getGroup(
            CxxSymlinkTreeHeadersExperiment.EXPERIMENT_NAME,
            eventBus.getBuildId().toString(),
            CxxSymlinkTreeHeadersExperiment.Mode.class);
    eventBus.post(
        new ExperimentEvent(
            CxxSymlinkTreeHeadersExperiment.EXPERIMENT_NAME, mode.toString(), "", null, null));
  }

  /** @return whether to run the experiment */
  public static boolean runExperiment() {
    return mode == Mode.ENABLE_CACHE;
  }

  public static final String EXPERIMENT_NAME = "symlink_header_experiment";
}
