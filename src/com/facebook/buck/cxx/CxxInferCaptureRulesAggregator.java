/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.collect.ImmutableSet;

public class CxxInferCaptureRulesAggregator extends NoopBuildRule {
  private  CxxInferCaptureAndAggregatingRules<CxxInferCaptureRulesAggregator>
      captureAndTransitiveAggregatingRules;

  public CxxInferCaptureRulesAggregator(
      BuildRuleParams params,
      SourcePathResolver resolver,
      CxxInferCaptureAndAggregatingRules<CxxInferCaptureRulesAggregator>
          captureAndTransitiveAggregatingRules) {
    super(params, resolver);
    this.captureAndTransitiveAggregatingRules = captureAndTransitiveAggregatingRules;
  }

  private ImmutableSet<CxxInferCapture> getCaptureRules() {
    return captureAndTransitiveAggregatingRules.captureRules;
  }

  public ImmutableSet<CxxInferCapture> getAllTransitiveCaptures() {
    ImmutableSet.Builder<CxxInferCapture> captureBuilder = ImmutableSet.builder();
    captureBuilder.addAll(captureAndTransitiveAggregatingRules.captureRules);
    for (CxxInferCaptureRulesAggregator aggregator :
        captureAndTransitiveAggregatingRules.aggregatingRules) {
      captureBuilder.addAll(aggregator.getCaptureRules());
    }
    return captureBuilder.build();
  }
}
