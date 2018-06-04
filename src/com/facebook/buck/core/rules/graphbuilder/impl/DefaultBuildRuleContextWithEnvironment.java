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

package com.facebook.buck.core.rules.graphbuilder.impl;

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.graph.transformation.TransformationEnvironment;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContext;
import com.facebook.buck.core.rules.graphbuilder.BuildRuleContextWithEnvironment;
import com.facebook.buck.core.rules.graphbuilder.BuildRuleKey;
import com.facebook.buck.core.rules.provider.BuildRuleInfoProviderCollection;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.immutables.value.Value;
import org.immutables.value.Value.Style.ImplementationVisibility;

/** Default abstract implementation of a {@link BuildRuleContextWithEnvironment}. */
@Value.Immutable(builder = false, copy = false)
@Value.Style(visibility = ImplementationVisibility.PACKAGE)
public abstract class DefaultBuildRuleContextWithEnvironment
    implements BuildRuleContextWithEnvironment {

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

  @Override
  public ProjectFilesystem getProjectFilesystem() {
    return getCreationContext().getProjectFilesystem();
  }

  @Override
  public CellPathResolver getCellPathResolver() {
    return getCreationContext().getCellPathResolver();
  }

  @Override
  public ToolchainProvider getToolchainProvider() {
    return getCreationContext().getToolchainProvider();
  }

  @Override
  public ImmutableSet<BuildTarget> getDeclaredDeps() {
    return getCurrentNode().getDeclaredDeps();
  }

  @Override
  public ImmutableSortedSet<BuildTarget> getExtraDeps() {
    return getCurrentNode().getExtraDeps();
  }

  @Override
  public ImmutableSortedSet<BuildTarget> getTargetGraphOnlyDeps() {
    return getCurrentNode().getTargetGraphOnlyDeps();
  }

  @Override
  public CompletionStage<BuildRule> getProviderCollectionForDep(
      BuildRuleKey depKey,
      Function<BuildRuleInfoProviderCollection, BuildRule> createBuildRuleWithDep) {
    return getEnv()
        .evaluate(
            depKey,
            depBuildRule -> createBuildRuleWithDep.apply(depBuildRule.getProviderCollection()));
  }

  @Override
  public CompletionStage<BuildRule> getProviderCollectionForDeps(
      Iterable<BuildRuleKey> depKeys,
      Function<ImmutableMap<BuildRuleKey, BuildRuleInfoProviderCollection>, BuildRule>
          createBuildRuleWithDeps) {
    return getEnv()
        .evaluateAll(
            depKeys,
            depBuildRules ->
                createBuildRuleWithDeps.apply(
                    ImmutableMap.copyOf(
                        Maps.transformValues(
                            depBuildRules, rule -> rule.getProviderCollection()))));
  }
}
