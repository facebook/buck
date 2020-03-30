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

package com.facebook.buck.core.rules.analysis.computation;

import com.facebook.buck.core.rules.analysis.RuleAnalysisKey;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.google.common.collect.ImmutableMap;
import java.util.Set;

/**
 * Represents the stage of buck where the {@link
 * com.facebook.buck.core.model.targetgraph.TargetGraph} is analyzed and converted into its
 * corresponding {@link RuleAnalysisResult} graph, which consists of a {@link
 * com.google.devtools.build.lib.packages.Provider} graph and a {@link ActionAnalysisData} graph.
 */
public interface RuleAnalysisGraph {

  /**
   * Finds the {@link RuleAnalysisResult} of the given {@link RuleAnalysisKey}, starting computation
   * as necessary. All dependencies required will also be transformed, and cached.
   *
   * @param lookupKey the {@link RuleAnalysisKey} to find the result for
   * @return the {@link RuleAnalysisResult} from analyzing the given {@link RuleAnalysisKey}
   */
  RuleAnalysisResult get(RuleAnalysisKey lookupKey);

  /** same as {@link #get(RuleAnalysisKey)} but for multiple {@link RuleAnalysisKey}s */
  ImmutableMap<RuleAnalysisKey, RuleAnalysisResult> getAll(Set<RuleAnalysisKey> lookupKeys);
}
