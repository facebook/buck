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

package com.facebook.buck.core.rules.analysis.impl;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

/** An Immutables implementation of the {@link RuleAnalysisResult}. */
@BuckStyleValue
abstract class RuleAnalysisResultImpl implements RuleAnalysisResult {

  @Override
  public abstract BuildTarget getBuildTarget();

  /** @return a {@link ProviderInfoCollection} exported by the rule */
  @Override
  public abstract ProviderInfoCollection getProviderInfos();

  @Override
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
