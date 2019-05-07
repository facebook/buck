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
package com.facebook.buck.core.build.action.resolver;

import com.facebook.buck.core.build.action.BuildEngineAction;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRuleResolver;

/**
 * This provides mechanism for looking up a {@link BuildEngineAction} via the {@link BuildTarget}
 * that {@link BuildEngineAction#getDependencies()} provides.
 *
 * <p>This lets the {@link com.facebook.buck.core.build.engine.BuildEngine} find and schedule
 * dependencies to be build.
 */
public class BuildTargetToBuildEngineActionResolver {

  private final BuildRuleResolver buildRuleResolver;

  public BuildTargetToBuildEngineActionResolver(BuildRuleResolver buildRuleResolver) {
    this.buildRuleResolver = buildRuleResolver;
  }

  public BuildEngineAction resolve(BuildTarget target) {
    /**
     * TODO (bobyf): change this to return {@link Action} if the {@link BuildTarget} if of a rule
     * from {@link RuleAnalysisComputation}.
     */
    return buildRuleResolver.getRule(target);
  }
}
