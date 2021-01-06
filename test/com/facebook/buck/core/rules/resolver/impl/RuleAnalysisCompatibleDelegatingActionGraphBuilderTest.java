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

package com.facebook.buck.core.rules.resolver.impl;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.CellProvider;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.nameresolver.SingleRootCellNameResolverProvider;
import com.facebook.buck.core.description.RuleDescription;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.actions.ActionExecutionResult;
import com.facebook.buck.core.rules.actions.ActionRegistry;
import com.facebook.buck.core.rules.actions.DefaultActionRegistry;
import com.facebook.buck.core.rules.actions.FakeAction;
import com.facebook.buck.core.rules.actions.FakeActionAnalysisRegistry;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.analysis.RuleAnalysisKey;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.core.rules.analysis.impl.FakeRuleAnalysisGraph;
import com.facebook.buck.core.rules.analysis.impl.FakeRuleAnalysisResultImpl;
import com.facebook.buck.core.rules.analysis.impl.FakeRuleDescriptionArg;
import com.facebook.buck.core.rules.config.registry.impl.ConfigurationRuleRegistryFactory;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.impl.RuleAnalysisLegacyBuildRuleView;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.TestProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.transformer.impl.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class RuleAnalysisCompatibleDelegatingActionGraphBuilderTest {

  private final ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
  private final CellPathResolver cellPathResolver = TestCellPathResolver.get(projectFilesystem);
  private final CellProvider cellProvider = new TestCellBuilder().build().getCellProvider();
  private final BuildTarget target = BuildTargetFactory.newInstance("//my:foo");

  @Test
  public void actionGraphBuildMethodsDelegatesToActionGraphBuilder() {
    BuildRule expectedRule = new FakeBuildRule(target);

    AtomicBoolean delegateCalled = new AtomicBoolean();

    RuleAnalysisCompatibleDelegatingActionGraphBuilder builder =
        new RuleAnalysisCompatibleDelegatingActionGraphBuilder(
            new DefaultTargetNodeToBuildRuleTransformer(),
            transformer ->
                new TestActionGraphBuilder(transformer) {
                  @Override
                  public BuildRule requireRule(BuildTarget buildTarget) {
                    assertSame(target, buildTarget);
                    delegateCalled.set(true);
                    return expectedRule;
                  }
                },
            new FakeRuleAnalysisGraph(
                key -> {
                  fail("should not call RuleAnalysisComputation");
                  return FakeRuleAnalysisResultImpl.of(
                      target, TestProviderInfoCollectionImpl.builder().build(), ImmutableMap.of());
                }));

    assertSame(expectedRule, builder.requireRule(target));
    assertTrue(delegateCalled.get());
  }

  @Test
  public void actionGraphBuildMethodsDelegatesToRuleAnalysisComputationWithNewRules() {
    TargetNodeFactory targetNodeFactory =
        new TargetNodeFactory(
            new DefaultTypeCoercerFactory(), SingleRootCellNameResolverProvider.INSTANCE);
    RuleDescription<?> ruleDescription =
        new RuleDescription<FakeRuleDescriptionArg>() {
          @Override
          public ProviderInfoCollection ruleImpl(
              RuleAnalysisContext context, BuildTarget target, FakeRuleDescriptionArg args) {
            return TestProviderInfoCollectionImpl.builder().build();
          }

          @Override
          public Class<FakeRuleDescriptionArg> getConstructorArgType() {
            return FakeRuleDescriptionArg.class;
          }
        };
    TargetNode targetNode =
        targetNodeFactory.createFromObject(
            ruleDescription,
            FakeRuleDescriptionArg.builder().setName("foo").build(),
            projectFilesystem,
            target,
            DependencyStack.root(),
            ImmutableSet.of(),
            ImmutableSortedSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of());

    MutableDirectedGraph<TargetNode<?>> mutableDirectedGraph =
        MutableDirectedGraph.createConcurrent();
    mutableDirectedGraph.addNode(targetNode);

    TargetGraph targetGraph =
        new TargetGraph(mutableDirectedGraph, ImmutableMap.of(target, targetNode));

    AtomicBoolean delegateActionGraphBuilderCalled = new AtomicBoolean();
    AtomicBoolean delegateRuleAnalysisComputationCalled = new AtomicBoolean();

    RuleAnalysisCompatibleDelegatingActionGraphBuilder builder =
        new RuleAnalysisCompatibleDelegatingActionGraphBuilder(
            new DefaultTargetNodeToBuildRuleTransformer(),
            transformer ->
                new MultiThreadedActionGraphBuilder(
                    MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
                    targetGraph,
                    ConfigurationRuleRegistryFactory.createRegistry(targetGraph),
                    transformer,
                    cellProvider) {
                  @Override
                  public BuildRule requireRule(BuildTarget buildTarget) {
                    delegateActionGraphBuilderCalled.set(true);
                    return super.requireRule(buildTarget);
                  }
                },
            new FakeRuleAnalysisGraph(
                key -> {
                  delegateRuleAnalysisComputationCalled.set(true);

                  FakeActionAnalysisRegistry actionAnalysisRegistry =
                      new FakeActionAnalysisRegistry();
                  ActionRegistry actionRegistry =
                      new DefaultActionRegistry(target, actionAnalysisRegistry, projectFilesystem);
                  Artifact artifact = actionRegistry.declareArtifact(Paths.get("foo"));

                  FakeAction.FakeActionExecuteLambda actionFunction =
                      (ignored, ignored1, ignored2, ignored3) ->
                          ActionExecutionResult.success(
                              Optional.empty(), Optional.empty(), ImmutableList.of());

                  new FakeAction(
                      actionRegistry,
                      ImmutableSortedSet.of(),
                      ImmutableSortedSet.of(),
                      ImmutableSortedSet.of(artifact),
                      actionFunction);
                  ActionAnalysisData actionAnalysisData =
                      Iterables.getOnlyElement(actionAnalysisRegistry.getRegistered().entrySet())
                          .getValue();

                  return FakeRuleAnalysisResultImpl.of(
                      target,
                      TestProviderInfoCollectionImpl.builder().build(),
                      ImmutableMap.of(actionAnalysisData.getKey().getID(), actionAnalysisData));
                }));

    BuildRule rule = builder.requireRule(target);
    assertTrue(delegateActionGraphBuilderCalled.get());
    assertTrue(delegateRuleAnalysisComputationCalled.get());

    assertTrue(rule instanceof RuleAnalysisLegacyBuildRuleView);
  }

  @Test
  public void ruleAnalysisMethodsDelegatesToRuleAnalysisComputation() {
    AtomicBoolean delegateRuleAnalysisComputationCalled = new AtomicBoolean();

    RuleAnalysisResult ruleAnalysisResult =
        FakeRuleAnalysisResultImpl.of(
            target, TestProviderInfoCollectionImpl.builder().build(), ImmutableMap.of());

    RuleAnalysisCompatibleDelegatingActionGraphBuilder builder =
        new RuleAnalysisCompatibleDelegatingActionGraphBuilder(
            new DefaultTargetNodeToBuildRuleTransformer(),
            transformer ->
                new TestActionGraphBuilder(transformer) {
                  @Override
                  public BuildRule requireRule(BuildTarget buildTarget) {
                    fail("Should not call ActionGraphBuilder");
                    return super.requireRule(buildTarget);
                  }
                },
            new FakeRuleAnalysisGraph(
                key -> {
                  delegateRuleAnalysisComputationCalled.set(true);
                  return ruleAnalysisResult;
                }));

    assertSame(ruleAnalysisResult, builder.get(RuleAnalysisKey.of(target)));
    assertTrue(delegateRuleAnalysisComputationCalled.get());
  }
}
