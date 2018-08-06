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

package com.facebook.buck.android;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ExperimentEvent;
import com.facebook.buck.util.randomizedtrial.RandomizedTrial;
import com.facebook.buck.util.randomizedtrial.WithProbability;
import java.util.Optional;

public class AndroidInstallConfig {
  private final BuckConfig delegate;

  public enum ConcurrentInstall implements WithProbability {
    ENABLED(0.5),
    DISABLED(0.5),
    EXPERIMENT(0.0);

    private final double probability;

    ConcurrentInstall(double probability) {
      this.probability = probability;
    }

    @Override
    public double getProbability() {
      return probability;
    }
  }

  private static final ConcurrentInstall CONCURRENT_INSTALL_DEFAULT = ConcurrentInstall.DISABLED;

  public AndroidInstallConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  /** @return Whether to enable concurrent install. */
  public boolean getConcurrentInstallEnabled(Optional<BuckEventBus> eventBus) {
    ConcurrentInstall state =
        delegate
            .getEnum("install", "concurrent_install", ConcurrentInstall.class)
            .orElse(CONCURRENT_INSTALL_DEFAULT);
    if (state == ConcurrentInstall.EXPERIMENT) {
      state = RandomizedTrial.getGroupStable("concurrent_install", ConcurrentInstall.class);
      ExperimentEvent event =
          new ExperimentEvent("concurrent_install", state.toString(), "", null, null);
      eventBus.ifPresent((bus) -> bus.post(event));
    }
    return state.equals(ConcurrentInstall.ENABLED);
  }
}
