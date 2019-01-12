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
package com.facebook.buck.core.rules.analysis.computation;

import com.facebook.buck.core.rules.analysis.RuleAnalysisKey;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.google.common.collect.ImmutableMap;
import java.util.Set;

/**
 * Represents the stage of buck where the {@link
 * com.facebook.buck.core.model.targetgraph.TargetGraph} is analyzed and converted into its
 * corresponding {@link RuleAnalysisResult} graph, which consists of a {@link
 * com.google.devtools.build.lib.packages.Provider} graph and a {@link
 * com.facebook.buck.core.rules.actions.ActionAnalysisData} graph.
 */
public interface RuleAnalysisComputation {

  /**
   * Starts to perform the transformation of the given {@link RuleAnalysisKey} to the corresponding
   * {@link RuleAnalysisResult}. All dependencies required will also be transformed, and cached.
   *
   * @param lookupKey the {@link RuleAnalysisKey} to transform
   * @return the {@link RuleAnalysisResult} from analyzing the given {@link RuleAnalysisKey}
   */
  RuleAnalysisResult computeUnchecked(RuleAnalysisKey lookupKey);

  /**
   * same as {@link #computeUnchecked(RuleAnalysisKey)} but for multiple {@link RuleAnalysisKey}s
   */
  ImmutableMap<RuleAnalysisKey, RuleAnalysisResult> computeAllUnchecked(
      Set<RuleAnalysisKey> lookupKeys);
}
