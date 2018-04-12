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

package com.facebook.buck.core.rules.graphbuilder;

import com.facebook.buck.core.graph.transformation.TransformationEnvironment;
import com.facebook.buck.core.rules.provider.BuildRuleInfoProvider;
import com.facebook.buck.core.rules.provider.BuildRuleInfoProviderCollection;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.immutables.value.Value;
import org.immutables.value.Value.Style.ImplementationVisibility;

/**
 * Context information used for construction of ActionGraph in {@link
 * com.facebook.buck.graph.transformationengine.AsyncTransformationEngine}.
 *
 * <p>This wraps the {@link BuildRuleCreationContext} needed for constructing {@link BuildRule}s
 * with {@link TransformationEnvironment} from the {@link
 * com.facebook.buck.graph.transformationengine.AsyncTransformationEngine}.
 *
 * <p>Access to the {@link TransformationEnvironment} is limited to restrict access of BuildRule
 * construction logic to {@link BuildRule}s. Construction phase can only access information to
 * dependencies via {@link BuildRuleInfoProvider}s. Those dependencies not yet created will be
 * implicitly created by this class using the wrapped {@link TransformationEnvironment}.
 *
 * <p>This context is not part of the {@link BuildRuleKey} as {@link TransformationEnvironment}
 * should not be part of the identifier of what {@link BuildRule} to compute.
 *
 * <p>Instances should only be created in {@link
 * com.facebook.buck.graph.transformationengine.AsyncTransformer#transform(Object,
 * TransformationEnvironment)} implementation for ActionGraph construction. Hence, we have
 * package-private implementation which hides constructor.
 */
@Value.Immutable(builder = false, copy = false, prehash = false)
@Value.Style(visibility = ImplementationVisibility.PACKAGE)
public abstract class BuildRuleContextWithEnvironment {

  @Value.Parameter
  protected abstract BuildRuleKey getKey();

  protected BuildRuleCreationContext getCreationContext() {
    return getKey().getBuildRuleCreationContext();
  }

  /** @return the {@link TargetNode} of the current desired {@link BuildRule} */
  protected TargetNode<?, ?> getCurrentNode() {
    return getKey().getTargetNode();
  }

  @Value.Parameter
  protected abstract TransformationEnvironment<BuildRuleKey, BuildRule> getEnv();

  public ProjectFilesystem getProjectFilesystem() {
    return getCreationContext().getProjectFilesystem();
  }

  public CellPathResolver getCellPathResolver() {
    return getCreationContext().getCellPathResolver();
  }

  public ToolchainProvider getToolchainProvider() {
    return getCreationContext().getToolchainProvider();
  }

  /**
   * Access to {@link com.facebook.buck.rules.TargetGraph} and {@link TargetNode} is limited during
   * ActionGraph construction. The list of target graph dependencies can only be accessed through
   * this context via the three methods below.
   */

  /** @return The {@link TargetNode#getDeclaredDeps()} */
  public ImmutableSet<BuildTarget> getDeclaredDeps() {
    return getCurrentNode().getDeclaredDeps();
  }

  /** @return The {@link TargetNode#getExtraDeps()} */
  public ImmutableSortedSet<BuildTarget> getExtraDeps() {
    return getCurrentNode().getExtraDeps();
  }

  /** @return The {@link TargetNode#getTargetGraphOnlyDeps()} ()} */
  public ImmutableSortedSet<BuildTarget> getTargetGraphOnlyDeps() {
    return getCurrentNode().getTargetGraphOnlyDeps();
  }

  /**
   * Access to {@link TransformationEnvironment} and dependencies as {@link BuildRule}s is limited.
   * The two methods below are used to asynchronously retrieve the {@link BuildRuleInfoProvider}s of
   * dependencies and create new {@link BuildRule}s using their information.
   */

  /**
   * A method for Action Graph construction phase to access information from a dependency by
   * retrieving all {@link BuildRuleInfoProvider}s of the dependent {@link BuildRule}.
   *
   * <p>The result is then asynchronously given to the supplied Function, which can use the {@link
   * BuildRuleInfoProvider}s to create the current desired {@link BuildRule}.
   *
   * @param depKey the {@link BuildRuleKey} of the desired dependency
   * @param createBuildRuleWithDep the function that uses the dependencies to create the current
   *     {@link BuildRule}
   * @return a future of the {@link BuildRule} to be created
   */
  public CompletionStage<BuildRule> getProviderCollectionForDep(
      BuildRuleKey depKey,
      Function<BuildRuleInfoProviderCollection, BuildRule> createBuildRuleWithDep) {
    return getEnv()
        .evaluate(
            depKey,
            depBuildRule -> {
              return createBuildRuleWithDep.apply(depBuildRule.getProviderCollection());
            });
  }

  /**
   * A method for Action Graph construction phase to access information from many dependencies by
   * retrieving all {@link BuildRuleInfoProvider}s of the dependencies {@link BuildRule}.
   *
   * <p>The result is then asynchronously given to the supplied Function, which can use the {@link
   * BuildRuleInfoProvider}s of the dependencies as a Map to create the current desired {@link
   * BuildRule}.
   *
   * @param depKeys the {@link BuildRuleKey} of the desired dependency
   * @param createBuildRuleWithDeps the function that uses the dependencies to create the current
   *     {@link BuildRule}
   * @return a future of the {@link BuildRule} to be created
   */
  public CompletionStage<BuildRule> getProviderCollectionForDeps(
      Iterable<BuildRuleKey> depKeys,
      Function<ImmutableMap<BuildRuleKey, BuildRuleInfoProviderCollection>, BuildRule>
          createBuildRuleWithDeps) {
    return getEnv()
        .evaluateAll(
            depKeys,
            depBuildRules -> {
              return createBuildRuleWithDeps.apply(
                  ImmutableMap.copyOf(
                      Maps.transformValues(depBuildRules, rule -> rule.getProviderCollection())));
            });
  }
}
