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
package com.facebook.buck.core.rules.resolver.impl;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.CellProvider;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.description.RuleDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.actions.ActionAnalysisData;
import com.facebook.buck.core.rules.actions.ActionExecutionContext;
import com.facebook.buck.core.rules.actions.ActionExecutionResult;
import com.facebook.buck.core.rules.actions.ActionWrapperDataFactory;
import com.facebook.buck.core.rules.actions.ActionWrapperDataFactory.DeclaredArtifact;
import com.facebook.buck.core.rules.actions.Artifact;
import com.facebook.buck.core.rules.actions.Artifact.BuildArtifact;
import com.facebook.buck.core.rules.actions.FakeAction;
import com.facebook.buck.core.rules.actions.FakeActionAnalysisRegistry;
import com.facebook.buck.core.rules.actions.ImmutableActionExecutionSuccess;
import com.facebook.buck.core.rules.analysis.ImmutableRuleAnalysisKey;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.facebook.buck.core.rules.analysis.impl.FakeRuleAnalysisComputation;
import com.facebook.buck.core.rules.analysis.impl.FakeRuleDescriptionArg;
import com.facebook.buck.core.rules.analysis.impl.ImmutableFakeRuleAnalysisResultImpl;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.impl.RuleAnalysisLegacyBuildRuleView;
import com.facebook.buck.core.rules.providers.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.impl.ProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.transformer.impl.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.util.function.TriFunction;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.nio.file.Paths;
import java.util.Optional;
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
            new FakeRuleAnalysisComputation(
                key -> {
                  fail("should not call RuleAnalysisComputation");
                  return ImmutableFakeRuleAnalysisResultImpl.of(
                      target, ProviderInfoCollectionImpl.builder().build(), ImmutableMap.of());
                }));

    assertSame(expectedRule, builder.requireRule(target));
    assertTrue(delegateCalled.get());
  }

  @Test
  public void actionGraphBuildMethodsDelegatesToRuleAnalysisComputationWithNewRules() {
    TargetNodeFactory targetNodeFactory = new TargetNodeFactory(new DefaultTypeCoercerFactory());
    RuleDescription<?> ruleDescription =
        new RuleDescription<FakeRuleDescriptionArg>() {
          @Override
          public ProviderInfoCollection ruleImpl(
              RuleAnalysisContext context, BuildTarget target, FakeRuleDescriptionArg args) {
            return ProviderInfoCollectionImpl.builder().build();
          }

          @Override
          public Class<FakeRuleDescriptionArg> getConstructorArgType() {
            return FakeRuleDescriptionArg.class;
          }
        };
    TargetNode targetNode =
        targetNodeFactory.createFromObject(
            ruleDescription,
            FakeRuleDescriptionArg.builder().build(),
            projectFilesystem,
            target,
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            cellPathResolver);

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
                new SingleThreadedActionGraphBuilder(targetGraph, transformer, cellProvider) {
                  @Override
                  public BuildRule requireRule(BuildTarget buildTarget) {
                    delegateActionGraphBuilderCalled.set(true);
                    return super.requireRule(buildTarget);
                  }
                },
            new FakeRuleAnalysisComputation(
                key -> {
                  delegateRuleAnalysisComputationCalled.set(true);

                  FakeActionAnalysisRegistry actionAnalysisRegistry =
                      new FakeActionAnalysisRegistry();
                  ActionWrapperDataFactory actionWrapperDataFactory =
                      new ActionWrapperDataFactory(actionAnalysisRegistry);
                  DeclaredArtifact artifact =
                      actionWrapperDataFactory.declareArtifact(Paths.get("foo"));

                  TriFunction<
                          ImmutableSet<Artifact>,
                          ImmutableSet<BuildArtifact>,
                          ActionExecutionContext,
                          ActionExecutionResult>
                      actionFunction =
                          (ignored, ignored2, ignored3) ->
                              ImmutableActionExecutionSuccess.of(
                                  Optional.empty(), Optional.empty());

                  actionWrapperDataFactory.createActionAnalysisData(
                      FakeAction.class,
                      target,
                      ImmutableSet.of(),
                      ImmutableSet.of(artifact),
                      actionFunction);
                  ActionAnalysisData actionAnalysisData =
                      Iterables.getOnlyElement(actionAnalysisRegistry.getRegistered().entrySet())
                          .getValue();

                  return ImmutableFakeRuleAnalysisResultImpl.of(
                      target,
                      ProviderInfoCollectionImpl.builder().build(),
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
        ImmutableFakeRuleAnalysisResultImpl.of(
            target, ProviderInfoCollectionImpl.builder().build(), ImmutableMap.of());

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
            new FakeRuleAnalysisComputation(
                key -> {
                  delegateRuleAnalysisComputationCalled.set(true);
                  return ruleAnalysisResult;
                }));

    assertSame(ruleAnalysisResult, builder.computeUnchecked(ImmutableRuleAnalysisKey.of(target)));
    assertTrue(delegateRuleAnalysisComputationCalled.get());
  }
}
