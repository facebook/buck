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

package com.facebook.buck.core.model.actiongraph;

import com.facebook.buck.core.build.action.resolver.BuildEngineActionToBuildRuleResolver;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/** Holds an ActionGraph with the BuildRuleResolver that created it. */
@BuckStyleValue
public abstract class ActionGraphAndBuilder {

  public abstract ActionGraph getActionGraph();

  public abstract ActionGraphBuilder getActionGraphBuilder();

  public abstract BuildEngineActionToBuildRuleResolver getBuildEngineActionToBuildRuleResolver();

  public static ActionGraphAndBuilder of(
      ActionGraph actionGraph, ActionGraphBuilder actionGraphBuilder) {
    return of(actionGraph, actionGraphBuilder, new BuildEngineActionToBuildRuleResolver());
  }

  public static ActionGraphAndBuilder of(
      ActionGraph actionGraph,
      ActionGraphBuilder actionGraphBuilder,
      BuildEngineActionToBuildRuleResolver buildEngineActionToBuildRuleResolver) {
    return ImmutableActionGraphAndBuilder.of(
        actionGraph, actionGraphBuilder, buildEngineActionToBuildRuleResolver);
  }
}
