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

package com.facebook.buck.core.model.actiongraph.computation;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import java.util.Map;
import org.immutables.value.Value;
import org.immutables.value.Value.Immutable;

@BuckStyleImmutable
@Immutable(builder = false, copy = false)
public abstract class AbstractActionGraphConfig implements ConfigView<BuckConfig> {

  @Override
  @Value.Parameter
  public abstract BuckConfig getDelegate();

  @Value.Derived
  public IncrementalActionGraphMode getIncrementalActionGraphMode() {
    return getDelegate()
        .getEnum("cache", "incremental_action_graph", IncrementalActionGraphMode.class)
        .orElse(IncrementalActionGraphMode.DEFAULT);
  }

  @Value.Derived
  public Map<IncrementalActionGraphMode, Double> getIncrementalActionGraphExperimentGroups() {
    return getDelegate()
        .getExperimentGroups(
            "cache", "incremental_action_graph_experiment", IncrementalActionGraphMode.class);
  }
}
