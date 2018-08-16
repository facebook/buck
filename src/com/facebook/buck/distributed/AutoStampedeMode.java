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

package com.facebook.buck.distributed;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.randomizedtrial.RandomizedTrial;
import com.facebook.buck.util.randomizedtrial.WithProbability;
import com.google.common.base.Preconditions;

/** Enum for config setting to auto-enable stampede builds with a certain probability. */
public enum AutoStampedeMode implements WithProbability {
  TRUE(0.7),
  FALSE(0.3),
  EXPERIMENTAL_STABLE(0.0),
  EXPERIMENTAL(0.0),
  ;

  private static final Logger LOG = Logger.get(AutoStampedeMode.class);

  public static final AutoStampedeMode DEFAULT = FALSE;

  private final double probability;

  AutoStampedeMode(double probability) {
    this.probability = probability;
  }

  @Override
  public double getProbability() {
    return probability;
  }

  /** Method to resolve the experiment value to one of the final values. */
  public AutoStampedeMode resolveExperiment(BuildId buildId) {
    AutoStampedeMode value = this;
    switch (value) {
      case EXPERIMENTAL:
        value =
            RandomizedTrial.getGroup(
                AutoStampedeMode.class.getName(), buildId.toString(), AutoStampedeMode.class);
        LOG.debug("Resolved %s experiment to %s", AutoStampedeMode.class.getName(), value);
        break;
      case EXPERIMENTAL_STABLE:
        value =
            RandomizedTrial.getGroupStable(
                AutoStampedeMode.class.getName(), AutoStampedeMode.class);
        LOG.debug("Resolved %s experiment to %s", AutoStampedeMode.class.getName(), value);
        break;
      case TRUE:
      case FALSE:
        break;
    }

    Preconditions.checkState(value == TRUE || value == FALSE);
    return value;
  }
}
