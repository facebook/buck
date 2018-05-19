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

package com.facebook.buck.core.model.targetgraph;

import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.description.Description;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.rules.BuildRule;

/**
 * The Source of Truth about a {@link BuildRule}, providing mechanisms to expose the arguments that
 * rules derived from the Buildable take and providing a factory for those BuildRules. It is
 * expected that instances of this class are stateless.
 *
 * @param <T> The object describing the parameters to be passed to the {@link BuildRule}. How this
 *     is processed is described in the class level javadoc of {@link
 *     com.facebook.buck.rules.coercer.ConstructorArgMarshaller}.
 */
public interface DescriptionWithTargetGraph<T> extends Description<T> {

  /**
   * Create a {@link BuildRule} for the given {@link BuildRuleParams}. Note that the {@link
   * BuildTarget} referred to in the {@code params} contains the {@link Flavor} to create.
   *
   * @param buildTarget
   * @param args A constructor argument, of type as returned by {@link #getConstructorArgType()}.
   * @return The {@link BuildRule} that describes the default flavour of the rule being described.
   */
  BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      T args);

  /**
   * Whether or not the build rule subgraph produced by this {@code Description} is safe to cache in
   * {@link com.facebook.buck.core.model.actiongraph.computation.IncrementalActionGraphGenerator}
   * for incremental action graph generation.
   */
  @Override
  default boolean producesCacheableSubgraph() {
    return false;
  }
}
