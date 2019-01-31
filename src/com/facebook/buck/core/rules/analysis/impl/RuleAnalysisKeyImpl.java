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

import com.facebook.buck.core.graph.transformation.ComputeKey;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.analysis.RuleAnalysisKey;
import org.immutables.value.Value;
import org.immutables.value.Value.Style.ImplementationVisibility;

/**
 * The immutable implementation of a {@link RuleAnalysisKey} used to identify transformations of
 * {@link com.facebook.buck.core.model.targetgraph.TargetNode} to its corresponding analysis result
 */
@Value.Immutable(builder = false, copy = false, prehash = true)
@Value.Style(visibility = ImplementationVisibility.PACKAGE)
abstract class RuleAnalysisKeyImpl implements RuleAnalysisKey {

  /**
   * TODO(bobyf): this should be a ConfiguredBuildTarget
   *
   * @return the {@link BuildTarget}
   */
  @Override
  @Value.Parameter
  public abstract BuildTarget getBuildTarget();

  @Override
  public Class<? extends ComputeKey<?>> getKeyClass() {
    return RuleAnalysisKey.class;
  }
}
