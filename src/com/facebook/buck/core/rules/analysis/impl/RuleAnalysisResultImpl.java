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
package com.facebook.buck.core.rules.analysis.impl;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.actions.ActionAnalysisData;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.facebook.buck.core.rules.providers.ProviderInfoCollection;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.immutables.value.Value;
import org.immutables.value.Value.Style.ImplementationVisibility;

/** An Immutables implementation of the {@link RuleAnalysisResult}. */
@Value.Immutable(builder = false, copy = false)
@Value.Style(visibility = ImplementationVisibility.PACKAGE)
abstract class RuleAnalysisResultImpl implements RuleAnalysisResult {
  @Value.Parameter
  @Override
  public abstract BuildTarget getBuildTarget();

  /** @return a {@link ProviderInfoCollection} exported by the rule */
  @Value.Parameter
  @Override
  public abstract ProviderInfoCollection getProviderInfos();

  @Override
  @Value.Parameter
  public abstract ImmutableMap<ActionAnalysisData.ID, ActionAnalysisData> getRegisteredActions();

  /** @return if the given key exists in the look up */
  @Override
  public boolean actionExists(ActionAnalysisData.ID key) {
    return getRegisteredActions().containsKey(key);
  }

  /** @return the {@link ActionAnalysisData} if exists, else {@code Optional.empty} */
  @Override
  public Optional<ActionAnalysisData> getActionOptional(ActionAnalysisData.ID key) {
    return Optional.ofNullable(getRegisteredActions().get(key));
  }
}
