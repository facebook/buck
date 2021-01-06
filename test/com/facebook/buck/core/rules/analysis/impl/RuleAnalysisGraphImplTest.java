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
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeArg;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.rules.ProviderCreationContext;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.analysis.RuleAnalysisKey;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.facebook.buck.core.rules.analysis.cache.RuleAnalysisCache;
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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RuleAnalysisGraphImplTest {
  private DepsAwareExecutor<? super ComputeResult, ?> depsAwareExecutor;
  private RuleAnalysisCache cache;

  private final ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
  private final CellPathResolver cellPathResolver = TestCellPathResolver.get(projectFilesystem);
  private final TargetNodeFactory targetNodeFactory =
      new TargetNodeFactory(
          new DefaultTypeCoercerFactory(), SingleRootCellNameResolverProvider.INSTANCE);
  private final BuckEventBus eventBus = BuckEventBusForTests.newInstance();

  @Before
  public void setUp() {
    depsAwareExecutor = DefaultDepsAwareExecutor.of(4);
    cache = new RuleAnalysisCacheImpl();
  }

  @After
  public void cleanUp() {
    depsAwareExecutor.close();
  }

  @Test
  public void transformNodeWithNoDepsCorrectly() {
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

    RuleAnalysisGraphImpl ruleAnalysisComputation =
        RuleAnalysisGraphImpl.of(targetGraph, depsAwareExecutor, cache, eventBus);

    RuleAnalysisResult ruleAnalysisResult =
        ruleAnalysisComputation.get(RuleAnalysisKey.of(buildTarget));

    // We shouldn't be making copies of the providers or build target in our transformation. It
    // should be as given.
    assertSame(expectedProviders, ruleAnalysisResult.getProviderInfos());
    assertSame(buildTarget, ruleAnalysisResult.getBuildTarget());
  }

  @Test
  public void transformNodeWithDepsCorrectly() {
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
            ImmutableSet.of(buildTarget2),
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

    RuleAnalysisGraphImpl ruleAnalysisComputation =
        RuleAnalysisGraphImpl.of(targetGraph, depsAwareExecutor, cache, eventBus);

    RuleAnalysisResult ruleAnalysisResult =
        ruleAnalysisComputation.get(RuleAnalysisKey.of(buildTarget));

    // We shouldn't be making copies of the providers or build target in our transformation. It
    // should be as given.
    assertSame(expectedProviders, ruleAnalysisResult.getProviderInfos());
    assertSame(buildTarget, ruleAnalysisResult.getBuildTarget());
  }

  @Test
  public void transformNodeWithLegacyDelegationCorrectly() {
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
    FakeTargetNodeBuilder.FakeDescription legacyDescription =
        new FakeTargetNodeBuilder.LegacyProviderFakeRuleDescription() {
          @Override
          public ProviderInfoCollection createProviders(
              ProviderCreationContext context, BuildTarget buildTarget, FakeTargetNodeArg args) {
            return expectedProviders;
          }
        };

    TargetNode<?> targetNode =
        targetNodeFactory.createFromObject(
            ruleDescription,
            FakeRuleDescriptionArg.builder().setName("target").build(),
            projectFilesystem,
            buildTarget,
            DependencyStack.root(),
            ImmutableSet.of(buildTarget2),
            ImmutableSortedSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of());
    TargetNode<?> targetNode2 =
        targetNodeFactory.createFromObject(
            legacyDescription,
            FakeTargetNodeArg.builder().setName("target2").build(),
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

    RuleAnalysisGraphImpl ruleAnalysisComputation =
        RuleAnalysisGraphImpl.of(
            new LegacyCompatibleRuleAnalysisComputation(
                new RuleAnalysisComputation(targetGraph, eventBus), targetGraph),
            targetGraph,
            depsAwareExecutor,
            cache);

    RuleAnalysisResult ruleAnalysisResult =
        ruleAnalysisComputation.get(RuleAnalysisKey.of(buildTarget));

    // We shouldn't be making copies of the providers or build target in our transformation. It
    // should be as given.
    assertSame(expectedProviders, ruleAnalysisResult.getProviderInfos());
    assertSame(buildTarget, ruleAnalysisResult.getBuildTarget());
  }
}
