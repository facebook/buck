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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.graph.transformation.TransformationEnvironment;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContext;
import com.facebook.buck.core.rules.provider.BuildRuleInfoProvider;
import com.facebook.buck.core.rules.provider.BuildRuleInfoProviderCollection;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Context information used for construction of ActionGraph in {@link
 * com.facebook.buck.core.graph.transformation.AsyncTransformationEngine}.
 *
 * <p>This wraps the {@link BuildRuleCreationContext} needed for constructing {@link BuildRule}s
 * with {@link TransformationEnvironment} from the {@link
 * com.facebook.buck.core.graph.transformation.AsyncTransformationEngine}.
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
 * com.facebook.buck.core.graph.transformation.AsyncTransformer#transform(Object,
 * TransformationEnvironment)} implementation for ActionGraph construction. Hence, we have
 * package-private implementation which hides constructor.
 */
public interface BuildRuleContextWithEnvironment {

  ProjectFilesystem getProjectFilesystem();

  CellPathResolver getCellPathResolver();

  ToolchainProvider getToolchainProvider();

  /**
   * Access to {@link TargetGraph} and {@link TargetNode} is limited during ActionGraph
   * construction. The list of target graph dependencies can only be accessed through this context
   * via the three methods below.
   */

  /** @return The {@link TargetNode#getDeclaredDeps()} */
  ImmutableSet<BuildTarget> getDeclaredDeps();

  /** @return The {@link TargetNode#getExtraDeps()} */
  ImmutableSortedSet<BuildTarget> getExtraDeps();

  /** @return The {@link TargetNode#getTargetGraphOnlyDeps()} ()} */
  ImmutableSortedSet<BuildTarget> getTargetGraphOnlyDeps();

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
  CompletionStage<BuildRule> getProviderCollectionForDep(
      BuildRuleKey depKey,
      Function<BuildRuleInfoProviderCollection, BuildRule> createBuildRuleWithDep);

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
  CompletionStage<BuildRule> getProviderCollectionForDeps(
      Iterable<BuildRuleKey> depKeys,
      Function<ImmutableMap<BuildRuleKey, BuildRuleInfoProviderCollection>, BuildRule>
          createBuildRuleWithDeps);
}
