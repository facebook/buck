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

package com.facebook.buck.core.rules.analysis.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.nameresolver.SingleRootCellNameResolverProvider;
import com.facebook.buck.core.description.RuleDescription;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.graph.transformation.impl.FakeComputationEnvironment;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.analysis.RuleAnalysisException;
import com.facebook.buck.core.rules.analysis.RuleAnalysisKey;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.TestProviderInfoCollectionImpl;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import org.junit.Test;

public class RuleAnalysisComputationTest {

  private final ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
  private final CellPathResolver cellPathResolver = TestCellPathResolver.get(projectFilesystem);
  private final TargetNodeFactory targetNodeFactory =
      new TargetNodeFactory(
          new DefaultTypeCoercerFactory(), SingleRootCellNameResolverProvider.INSTANCE);
  private final BuckEventBus eventBus = BuckEventBusForTests.newInstance();

  @Test
  public void getDepsWhenNoDeps() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//my:target");

    RuleDescription<FakeRuleDescriptionArg> ruleDescription =
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
    TargetNode<?> targetNode =
        targetNodeFactory.createFromObject(
            ruleDescription,
            FakeRuleDescriptionArg.builder().setName("target").build(),
            projectFilesystem,
            buildTarget,
            DependencyStack.root(),
            ImmutableSet.of(),
            ImmutableSortedSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of());
    MutableDirectedGraph<TargetNode<?>> graph = new MutableDirectedGraph<>();
    graph.addNode(targetNode);
    ImmutableMap<BuildTarget, TargetNode<?>> targetNodeIndex =
        ImmutableMap.of(buildTarget, targetNode);
    TargetGraph targetGraph = new TargetGraph(graph, targetNodeIndex);

    RuleAnalysisComputation transformer = new RuleAnalysisComputation(targetGraph, eventBus);
    assertEquals(
        ImmutableSet.of(), transformer.discoverPreliminaryDeps(RuleAnalysisKey.of(buildTarget)));
  }

  @Test
  public void getDepsWhenManyDeps() {
    BuildTarget buildTarget1 = BuildTargetFactory.newInstance("//my:target1");
    BuildTarget buildTarget2 = BuildTargetFactory.newInstance("//my:target2");
    BuildTarget buildTarget3 = BuildTargetFactory.newInstance("//my:target3");

    RuleDescription<FakeRuleDescriptionArg> ruleDescription =
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
    TargetNode<?> targetNode1 =
        targetNodeFactory.createFromObject(
            ruleDescription,
            FakeRuleDescriptionArg.builder().setName("target1").build(),
            projectFilesystem,
            buildTarget1,
            DependencyStack.root(),
            ImmutableSet.of(buildTarget2, buildTarget3),
            ImmutableSortedSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of());
    TargetNode<?> targetNode2 =
        targetNodeFactory.createFromObject(
            ruleDescription,
            FakeRuleDescriptionArg.builder().setName("target2").build(),
            projectFilesystem,
            buildTarget2,
            DependencyStack.root(),
            ImmutableSet.of(),
            ImmutableSortedSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of());
    TargetNode<?> targetNode3 =
        targetNodeFactory.createFromObject(
            ruleDescription,
            FakeRuleDescriptionArg.builder().setName("target3").build(),
            projectFilesystem,
            buildTarget3,
            DependencyStack.root(),
            ImmutableSet.of(),
            ImmutableSortedSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of());
    MutableDirectedGraph<TargetNode<?>> graph = new MutableDirectedGraph<>();
    graph.addNode(targetNode1);
    graph.addNode(targetNode2);
    graph.addNode(targetNode3);
    ImmutableMap<BuildTarget, TargetNode<?>> targetNodeIndex =
        ImmutableMap.of(
            buildTarget1, targetNode1, buildTarget2, targetNode2, buildTarget3, targetNode3);
    TargetGraph targetGraph = new TargetGraph(graph, targetNodeIndex);

    RuleAnalysisComputation transformer = new RuleAnalysisComputation(targetGraph, eventBus);
    assertEquals(
        ImmutableSet.of(RuleAnalysisKey.of(buildTarget2), RuleAnalysisKey.of(buildTarget3)),
        transformer.discoverPreliminaryDeps(RuleAnalysisKey.of(buildTarget1)));
  }

  @Test
  public void transformNodeWithNoDepsCorrectly()
      throws ActionCreationException, RuleAnalysisException {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//my:target");

    ProviderInfoCollection expectedProviders =
        TestProviderInfoCollectionImpl.builder()
            .put(new FakeInfo(new FakeBuiltInProvider("myprovider")))
            .build();

    RuleDescription<FakeRuleDescriptionArg> ruleDescription =
        new RuleDescription<FakeRuleDescriptionArg>() {
          @Override
          public ProviderInfoCollection ruleImpl(
              RuleAnalysisContext context, BuildTarget target, FakeRuleDescriptionArg args) {
            assertEquals(buildTarget, target);
            return expectedProviders;
          }

          @Override
          public Class<FakeRuleDescriptionArg> getConstructorArgType() {
            return FakeRuleDescriptionArg.class;
          }
        };

    TargetNode<?> targetNode =
        targetNodeFactory.createFromObject(
            ruleDescription,
            FakeRuleDescriptionArg.builder().setName("target").build(),
            projectFilesystem,
            buildTarget,
            DependencyStack.root(),
            ImmutableSet.of(),
            ImmutableSortedSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of());
    MutableDirectedGraph<TargetNode<?>> graph = new MutableDirectedGraph<>();
    graph.addNode(targetNode);
    ImmutableMap<BuildTarget, TargetNode<?>> targetNodeIndex =
        ImmutableMap.of(buildTarget, targetNode);
    TargetGraph targetGraph = new TargetGraph(graph, targetNodeIndex);

    RuleAnalysisComputation transformer = new RuleAnalysisComputation(targetGraph, eventBus);

    RuleAnalysisResult ruleAnalysisResult =
        transformer.transform(
            RuleAnalysisKey.of(buildTarget), new FakeComputationEnvironment(ImmutableMap.of()));

    // We shouldn't be making copies of the providers or build target in our transformation. It
    // should be as given.
    assertSame(expectedProviders, ruleAnalysisResult.getProviderInfos());
    assertSame(buildTarget, ruleAnalysisResult.getBuildTarget());
  }

  @Test
  public void transformNodeWithDepsCorrectly()
      throws ActionCreationException, RuleAnalysisException {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//my:target");
    BuildTarget buildTarget2 = BuildTargetFactory.newInstance("//my:target2");

    ProviderInfoCollection expectedProviders =
        TestProviderInfoCollectionImpl.builder()
            .put(new FakeInfo(new FakeBuiltInProvider("myprovider")))
            .build();

    RuleDescription<FakeRuleDescriptionArg> ruleDescription =
        new RuleDescription<FakeRuleDescriptionArg>() {
          @Override
          public ProviderInfoCollection ruleImpl(
              RuleAnalysisContext context, BuildTarget target, FakeRuleDescriptionArg args) {
            // here we use the deps
            assertEquals(buildTarget, target);
            return context.resolveDep(buildTarget2);
          }

          @Override
          public Class<FakeRuleDescriptionArg> getConstructorArgType() {
            return FakeRuleDescriptionArg.class;
          }
        };
    RuleDescription<FakeRuleDescriptionArg> ruleDescription2 =
        new RuleDescription<FakeRuleDescriptionArg>() {
          @Override
          public ProviderInfoCollection ruleImpl(
              RuleAnalysisContext context, BuildTarget target, FakeRuleDescriptionArg args) {
            return expectedProviders;
          }

          @Override
          public Class<FakeRuleDescriptionArg> getConstructorArgType() {
            return FakeRuleDescriptionArg.class;
          }
        };

    TargetNode<?> targetNode =
        targetNodeFactory.createFromObject(
            ruleDescription,
            FakeRuleDescriptionArg.builder().setName("target").build(),
            projectFilesystem,
            buildTarget,
            DependencyStack.root(),
            ImmutableSet.of(),
            ImmutableSortedSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of());
    TargetNode<?> targetNode2 =
        targetNodeFactory.createFromObject(
            ruleDescription2,
            FakeRuleDescriptionArg.builder().setName("target2").build(),
            projectFilesystem,
            buildTarget2,
            DependencyStack.root(),
            ImmutableSet.of(),
            ImmutableSortedSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of());

    MutableDirectedGraph<TargetNode<?>> graph = new MutableDirectedGraph<>();
    graph.addNode(targetNode);
    graph.addNode(targetNode2);
    graph.addEdge(targetNode, targetNode2);
    ImmutableMap<BuildTarget, TargetNode<?>> targetNodeIndex =
        ImmutableMap.of(buildTarget, targetNode, buildTarget2, targetNode2);
    TargetGraph targetGraph = new TargetGraph(graph, targetNodeIndex);

    RuleAnalysisComputation transformer = new RuleAnalysisComputation(targetGraph, eventBus);

    RuleAnalysisResult ruleAnalysisResult =
        transformer.transform(
            RuleAnalysisKey.of(buildTarget),
            // here we provide the deps via the TransformationEnvironment
            new FakeComputationEnvironment(
                ImmutableMap.of(
                    RuleAnalysisKey.of(buildTarget2),
                    ImmutableRuleAnalysisResultImpl.of(
                        buildTarget2, expectedProviders, ImmutableMap.of()))));

    // We shouldn't be making copies of the providers or build target in our transformation. It
    // should be as given.
    assertSame(expectedProviders, ruleAnalysisResult.getProviderInfos());
    assertSame(buildTarget, ruleAnalysisResult.getBuildTarget());
  }
}
