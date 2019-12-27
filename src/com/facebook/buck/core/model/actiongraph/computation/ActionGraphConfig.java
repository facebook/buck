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

package com.facebook.buck.core.model.actiongraph.computation;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.util.Map;
import org.immutables.value.Value;

@BuckStyleValue
public abstract class ActionGraphConfig implements ConfigView<BuckConfig> {

  public static ActionGraphConfig of(BuckConfig delegate) {
    return ImmutableActionGraphConfig.of(delegate);
  }

  @Override
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

  @Value.Derived
  public boolean isActionGraphCheckingEnabled() {
    return getDelegate().getBooleanValue("cache", "action_graph_cache_check_enabled", false);
  }

  /**
   * @return whether the current invocation of Buck should skip the Action Graph cache, leaving the
   *     cached Action Graph in memory for the next request and creating a fresh Action Graph for
   *     the current request (which will be garbage-collected when the current request is complete).
   *     Commonly, a one-off request, like from a linter, will specify this option so that it does
   *     not invalidate the primary in-memory Action Graph that the user is likely relying on for
   *     fast iterative builds.
   */
  @Value.Derived
  public boolean isSkipActionGraphCache() {
    return getDelegate().getBooleanValue("client", "skip-action-graph-cache", false);
  }
}
