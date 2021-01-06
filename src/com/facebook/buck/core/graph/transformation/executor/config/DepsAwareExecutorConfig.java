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

package com.facebook.buck.core.graph.transformation.executor.config;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.graph.transformation.executor.factory.DepsAwareExecutorType;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.util.Map;

/**
 * Configuration for {@link com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor}s
 */
@BuckStyleValue
public abstract class DepsAwareExecutorConfig implements ConfigView<BuckConfig> {

  private static final String SECTION = "depsawareexecutor";

  @Override
  public abstract BuckConfig getDelegate();

  public static DepsAwareExecutorConfig of(BuckConfig delegate) {
    return ImmutableDepsAwareExecutorConfig.of(delegate);
  }

  /** @return the {@link DepsAwareExecutorType} we should use based on the buckconfig */
  public Map<DepsAwareExecutorType, Double> getExecutorType() {
    return getDelegate().getExperimentGroups(SECTION, "type", DepsAwareExecutorType.class);
  }
}
