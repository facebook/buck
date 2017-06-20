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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.NoopBuildRule;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.SortedSet;

/**
 * CxxInferCaptureRulesAggregator is used to aggregate all of the per-source CxxInferCapture rules
 * per-library, so that we can look them up in the build graph while only having a reference to our
 * cxx_library dependencies, rather than needing to traverse into per-source details of our
 * dependencies.
 *
 * <p>This is a pure metadata rule - all of the information in it could either be derived by
 * traversing the action graph, or could be kept in-memory using the correct datastructures, but
 * actually doing that refactoring is non-trivial. It cannot actually be built (nor even depended
 * on), but the CxxInferEnhancer relies on being able to look it up.
 */
public class CxxInferCaptureRulesAggregator extends NoopBuildRule {
  private CxxInferCaptureAndAggregatingRules<CxxInferCaptureRulesAggregator>
      captureAndTransitiveAggregatingRules;

  public CxxInferCaptureRulesAggregator(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      CxxInferCaptureAndAggregatingRules<CxxInferCaptureRulesAggregator>
          captureAndTransitiveAggregatingRules) {
    super(buildTarget, projectFilesystem);
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

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return ImmutableSortedSet.of();
  }
}
