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

import com.facebook.buck.core.rules.analysis.RuleAnalysisKey;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.facebook.buck.core.rules.analysis.computation.RuleAnalysisGraph;
import com.facebook.buck.util.function.ThrowingFunction;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.Set;
import java.util.function.Function;

public class FakeRuleAnalysisGraph implements RuleAnalysisGraph {

  private final Function<RuleAnalysisKey, RuleAnalysisResult> mappingFunc;

  public FakeRuleAnalysisGraph(
      ThrowingFunction<RuleAnalysisKey, RuleAnalysisResult, Exception> mappingFunc) {
    this.mappingFunc = mappingFunc.asFunction();
  }

  @Override
  public RuleAnalysisResult get(RuleAnalysisKey lookupKey) {
    return mappingFunc.apply(lookupKey);
  }

  @Override
  public ImmutableMap<RuleAnalysisKey, RuleAnalysisResult> getAll(Set<RuleAnalysisKey> lookupKeys) {
    return Maps.toMap(lookupKeys, mappingFunc::apply);
  }
}
