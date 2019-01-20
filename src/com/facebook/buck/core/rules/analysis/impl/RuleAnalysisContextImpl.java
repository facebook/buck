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

import com.facebook.buck.core.rules.actions.ActionAnalysisData;
import com.facebook.buck.core.rules.actions.ActionAnalysisData.Key;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.analysis.RuleAnalysisKey;
import com.facebook.buck.core.rules.providers.ProviderInfoCollection;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Implementation of {@link com.facebook.buck.core.rules.analysis.RuleAnalysisContext}. This context
 * is created per rule analysis.
 */
class RuleAnalysisContextImpl implements RuleAnalysisContext {

  private final ImmutableMap<RuleAnalysisKey, ProviderInfoCollection> depProviders;
  private final Map<Key, ActionAnalysisData> actionRegistry = new HashMap<>();

  RuleAnalysisContextImpl(ImmutableMap<RuleAnalysisKey, ProviderInfoCollection> depProviders) {
    this.depProviders = depProviders;
  }

  @Override
  public ImmutableMap<RuleAnalysisKey, ProviderInfoCollection> deps() {
    return depProviders;
  }

  @Override
  public void registerAction(ActionAnalysisData actionAnalysisData) {
    ActionAnalysisData prev =
        actionRegistry.putIfAbsent(actionAnalysisData.getKey(), actionAnalysisData);
    Verify.verify(
        prev == null,
        "Action of key %s was already registered with %s",
        actionAnalysisData.getKey(),
        prev);
  }

  public Map<ActionAnalysisData.Key, ActionAnalysisData> getRegisteredActionData() {
    return actionRegistry;
  }
}
