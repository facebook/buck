/*
 * Copyright 2019-present Facebook, Inc.
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

import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ProviderCreationContext;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * An implementation of the {@link RuleAnalysisResult} for legacy provider results from {@link
 * com.facebook.buck.core.rules.DescriptionWithTargetGraph#createProviders(ProviderCreationContext,
 * BuildTarget, ConstructorArg)}
 *
 * <p>This implementation throws on any operation relating to {@link ActionAnalysisData}.
 */
@Value.Immutable(builder = false, copy = false)
@Value.Style(visibility = Value.Style.ImplementationVisibility.PACKAGE)
public abstract class LegacyProviderRuleAnalysisResult implements RuleAnalysisResult {
  @Value.Parameter
  @Override
  public abstract BuildTarget getBuildTarget();

  /** @return a {@link ProviderInfoCollection} exported by the rule */
  @Value.Parameter
  @Override
  public abstract ProviderInfoCollection getProviderInfos();

  private IllegalStateException noActionsMethodOnLegacyProviders() {
    return new IllegalStateException(
        "Should not call Action related methods on RuleAnalysisResult for legacy Providers");
  }

  @Override
  public ImmutableMap<ActionAnalysisData.ID, ActionAnalysisData> getRegisteredActions() {
    throw noActionsMethodOnLegacyProviders();
  }

  @Override
  public boolean actionExists(ActionAnalysisData.ID key) {
    throw noActionsMethodOnLegacyProviders();
  }

  @Override
  public Optional<ActionAnalysisData> getActionOptional(ActionAnalysisData.ID key) {
    throw noActionsMethodOnLegacyProviders();
  }
}
