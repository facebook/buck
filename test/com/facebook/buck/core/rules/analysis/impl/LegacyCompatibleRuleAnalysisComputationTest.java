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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.nameresolver.SingleRootCellNameResolverProvider;
import com.facebook.buck.core.description.RuleDescription;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.graph.transformation.ComputationEnvironment;
import com.facebook.buck.core.graph.transformation.impl.FakeComputationEnvironment;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeArg;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ProviderCreationContext;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class LegacyCompatibleRuleAnalysisComputationTest {

  private final ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
  private final CellPathResolver cellPathResolver = TestCellPathResolver.get(projectFilesystem);
  private final TargetNodeFactory targetNodeFactory =
      new TargetNodeFactory(
          new DefaultTypeCoercerFactory(), SingleRootCellNameResolverProvider.INSTANCE);
  private final BuckEventBus eventBus = BuckEventBusForTests.newInstance();

  @Test
  public void getDepsReturnsEmptyForLegacyRules() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//my:target");

    TargetNode<?> targetNode =
        FakeTargetNodeBuilder.newBuilder(new FakeTargetNodeBuilder.FakeDescription(), buildTarget)
            .build();

    MutableDirectedGraph<TargetNode<?>> graph = new MutableDirectedGraph<>();
    ImmutableMap<BuildTarget, TargetNode<?>> targetNodeIndex =
        ImmutableMap.of(buildTarget, targetNode);
    TargetGraph targetGraph = new TargetGraph(graph, targetNodeIndex);

    LegacyCompatibleRuleAnalysisComputation transformer =
        new LegacyCompatibleRuleAnalysisComputation(
            new RuleAnalysisComputation(targetGraph, eventBus) {
              @Override
              public ImmutableSet<RuleAnalysisKey> discoverPreliminaryDeps(RuleAnalysisKey key) {
                fail("Should not call into delegate");
                return ImmutableSet.of();
              }

              @Override
              public ImmutableSet<RuleAnalysisKey> discoverDeps(
                  RuleAnalysisKey key, ComputationEnvironment env) {
                fail("Should not call into delegate");
                return ImmutableSet.of();
              }
            },
            targetGraph);

    assertEquals(
        ImmutableSet.of(), transformer.discoverPreliminaryDeps(RuleAnalysisKey.of(buildTarget)));

    assertEquals(
        ImmutableSet.of(),
        transformer.discoverDeps(
            RuleAnalysisKey.of(buildTarget), new FakeComputationEnvironment(ImmutableMap.of())));
  }

  @Test
  public void getDepsDiscoveryDelegatesForCompatibleRules() {

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//my:target");

    TargetNode<?> targetNode =
        FakeTargetNodeBuilder.newBuilder(
                new FakeTargetNodeBuilder.LegacyProviderFakeRuleDescription() {
                  @Override
                  public ProviderInfoCollection createProviders(
                      ProviderCreationContext context,
                      BuildTarget buildTarget,
                      FakeTargetNodeArg args) {
                    return TestProviderInfoCollectionImpl.builder().build();
                  }
                },
                buildTarget)
            .build();

    MutableDirectedGraph<TargetNode<?>> graph = new MutableDirectedGraph<>();
    ImmutableMap<BuildTarget, TargetNode<?>> targetNodeIndex =
        ImmutableMap.of(buildTarget, targetNode);
    TargetGraph targetGraph = new TargetGraph(graph, targetNodeIndex);

    AtomicBoolean pdepsCalled = new AtomicBoolean();
    AtomicBoolean depsCalled = new AtomicBoolean();

    ImmutableSet<RuleAnalysisKey> pdepsKeys =
        ImmutableSet.of(RuleAnalysisKey.of(BuildTargetFactory.newInstance("//some:pdep")));
    ImmutableSet<RuleAnalysisKey> depsKeys =
        ImmutableSet.of(RuleAnalysisKey.of(BuildTargetFactory.newInstance("//some:dep")));

    LegacyCompatibleRuleAnalysisComputation transformer =
        new LegacyCompatibleRuleAnalysisComputation(
            new RuleAnalysisComputation(targetGraph, eventBus) {
              @Override
              public ImmutableSet<RuleAnalysisKey> discoverPreliminaryDeps(RuleAnalysisKey key) {
                pdepsCalled.set(true);
                return pdepsKeys;
              }

              @Override
              public ImmutableSet<RuleAnalysisKey> discoverDeps(
                  RuleAnalysisKey key, ComputationEnvironment env) {
                depsCalled.set(true);
                return depsKeys;
              }
            },
            targetGraph);

    assertSame(pdepsKeys, transformer.discoverPreliminaryDeps(RuleAnalysisKey.of(buildTarget)));
    assertTrue(pdepsCalled.get());

    assertSame(
        depsKeys,
        transformer.discoverDeps(
            RuleAnalysisKey.of(buildTarget), new FakeComputationEnvironment(ImmutableMap.of())));
    assertTrue(depsCalled.get());
  }

  @Test
  public void transformDelegatesWhenRuleDescription()
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

    AtomicBoolean transformCalled = new AtomicBoolean();
    LegacyCompatibleRuleAnalysisComputation transformer =
        new LegacyCompatibleRuleAnalysisComputation(
            new RuleAnalysisComputation(targetGraph, eventBus) {
              @Override
              public RuleAnalysisResult transform(RuleAnalysisKey key, ComputationEnvironment env)
                  throws ActionCreationException, RuleAnalysisException {
                transformCalled.set(true);
                return super.transform(key, env);
              }
            },
            targetGraph);

    RuleAnalysisResult ruleAnalysisResult =
        transformer.transform(
            RuleAnalysisKey.of(buildTarget), new FakeComputationEnvironment(ImmutableMap.of()));

    // We shouldn't be making copies of the providers or build target in our transformation. It
    // should be as given.
    assertSame(expectedProviders, ruleAnalysisResult.getProviderInfos());
    assertSame(buildTarget, ruleAnalysisResult.getBuildTarget());

    assertTrue(transformCalled.get());
  }

  @Test
  public void transformReturnsLegacyProviderRuleAnalysisResultForLegacyDescription()
      throws ActionCreationException, RuleAnalysisException {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//my:target");

    ProviderInfoCollection expectedProviders =
        TestProviderInfoCollectionImpl.builder()
            .put(new FakeInfo(new FakeBuiltInProvider("myprovider")))
            .build();

    AtomicBoolean createProvidersCalled = new AtomicBoolean();
    DescriptionWithTargetGraph<?> descriptionWithTargetGraph =
        new FakeTargetNodeBuilder.LegacyProviderFakeRuleDescription() {
          @Override
          public ProviderInfoCollection createProviders(
              ProviderCreationContext context, BuildTarget buildTarget, FakeTargetNodeArg args) {
            createProvidersCalled.set(true);
            return expectedProviders;
          }
        };

    TargetNode<?> targetNode =
        targetNodeFactory.createFromObject(
            descriptionWithTargetGraph,
            FakeTargetNodeArg.builder().setName("//my:target").build(),
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

    LegacyCompatibleRuleAnalysisComputation transformer =
        new LegacyCompatibleRuleAnalysisComputation(
            new RuleAnalysisComputation(targetGraph, eventBus) {
              @Override
              public RuleAnalysisResult transform(RuleAnalysisKey key, ComputationEnvironment env)
                  throws ActionCreationException, RuleAnalysisException {
                fail("Should not get to RuleAnalysisComputation delegate");
                return null;
              }
            },
            targetGraph);

    RuleAnalysisResult ruleAnalysisResult =
        transformer.transform(
            RuleAnalysisKey.of(buildTarget), new FakeComputationEnvironment(ImmutableMap.of()));

    // We shouldn't be making copies of the providers or build target in our transformation. It
    // should be as given.
    assertSame(expectedProviders, ruleAnalysisResult.getProviderInfos());
    assertSame(buildTarget, ruleAnalysisResult.getBuildTarget());

    assertTrue(createProvidersCalled.get());

    assertTrue(ruleAnalysisResult instanceof LegacyProviderRuleAnalysisResult);
  }
}
