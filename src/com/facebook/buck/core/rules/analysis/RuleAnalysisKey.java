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

package com.facebook.buck.core.rules.analysis;

import com.facebook.buck.core.graph.transformation.model.ClassBasedComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.core.util.immutables.BuckStylePrehashedValue;

/**
 * The key of a computation of the {@link com.facebook.buck.core.model.targetgraph.TargetGraph} to
 * its corresponding {@link com.google.devtools.build.lib.packages.Provider}s and {@link
 * ActionAnalysisData}.
 *
 * <p>This key will be used to indicate which rule's analysis we are currently interested in.
 */
@BuckStylePrehashedValue
public abstract class RuleAnalysisKey implements ComputeKey<RuleAnalysisResult> {

  public static final ComputationIdentifier<RuleAnalysisResult> IDENTIFIER =
      ClassBasedComputationIdentifier.of(RuleAnalysisKey.class, RuleAnalysisResult.class);

  /**
   * TODO(bobyf) this really should be a ConfiguredBuildTarget
   *
   * @return the {@link BuildTarget} of this key
   */
  public abstract BuildTarget getBuildTarget();

  @Override
  public ComputationIdentifier<RuleAnalysisResult> getIdentifier() {
    return IDENTIFIER;
  }

  public static RuleAnalysisKey of(BuildTarget buildTarget) {
    return ImmutableRuleAnalysisKey.of(buildTarget);
  }
}
