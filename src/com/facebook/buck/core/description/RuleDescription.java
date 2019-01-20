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
package com.facebook.buck.core.description;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.providers.ProviderInfoCollection;

/**
 * The new modern {@link Description} that we will use during the analysis of a rule.
 *
 * <p>The {@link RuleDescription} will offer {@link #ruleImpl(RuleAnalysisContext, BuildTarget,
 * Object)} method, which provides a set of restricted information via the {@link
 * RuleAnalysisContext} to run the rule implementation.
 *
 * @param <T> the type of args that the rule implementation uses
 */
public interface RuleDescription<T> extends Description<T> {

  @Override
  default boolean producesCacheableSubgraph() {
    // This is more of a goal for now. Where we aim to make it such that all rules we convert to the
    // new construction model has a cacheable subgraph. We also probably actually won't use this
    // method at all in the new model.
    return true;
  }

  /**
   * Runs the rule implementation during the analysis phase. The rule implementation should create
   * the propagated {@link com.google.devtools.build.lib.packages.Provider}s and corresponding
   * {@link com.google.devtools.build.lib.packages.InfoInterface}s, and register its corresponding
   * actions.
   *
   * @param context a {@link RuleAnalysisContext} containing all the information usable by this rule
   *     for it's analysis and constructive of its corresponding {@link
   *     com.google.devtools.build.lib.packages.Provider} and {@link
   *     com.facebook.buck.core.rules.BuildRule} graph.
   * @param target the {@link BuildTarget} of this rule
   * @param args The args of type {@code T} that this rule uses to rule its analysis
   * @return a {@link ProviderInfoCollection} that contains all the {@link
   *     com.google.devtools.build.lib.packages.Provider} and the corresponding {@link
   *     com.google.devtools.build.lib.packages.InfoInterface} to be propagated by this rule.
   */
  ProviderInfoCollection ruleImpl(RuleAnalysisContext context, BuildTarget target, T args);
}
