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

package com.facebook.buck.features.alias;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSet;

/** Base class for a Description that point at other {@code BuildTarget}s */
public abstract class AbstractAliasDescription<T extends BuildRuleArg>
    implements DescriptionWithTargetGraph<T>, ImplicitDepsInferringDescription<T>, Flavored {

  public abstract BuildTarget resolveActualBuildTarget(T arg);

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      T args) {
    BuildTarget actual = resolveActualBuildTarget(args);
    return context.getActionGraphBuilder().requireRule(actual);
  }

  /**
   * This method tells the action graph builder that we're intentionally returning a build rule from
   * a different target. Without it our implementation of {@code createBuildRule} would cause an
   * exception to be thrown.
   */
  @Override
  public boolean producesBuildRuleFromOtherTarget() {
    return true;
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      T constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    extraDepsBuilder.add(resolveActualBuildTarget(constructorArg));
  }

  /**
   * @param flavors The set of {@link Flavor}s to consider. All must match.
   * @param toolchainTargetConfiguration
   * @return Whether a {@link com.facebook.buck.core.rules.BuildRule} of the given {@link Flavor}
   *     can be created.
   */
  @Override
  public boolean hasFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    // Flavor verification is done when creating unconfigured nodes, so
    // we can't reliably determine the actual node the alias references.
    // This means we can't determine whether flavors are truly valid,
    // so just accept all flavors for now.
    return true;
  }
}
