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
package com.facebook.buck.core.rules.analysis.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.description.RuleDescription;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.rules.analysis.ImmutableRuleAnalysisKey;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.facebook.buck.core.rules.analysis.cache.RuleAnalysisCache;
import com.facebook.buck.core.rules.providers.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.impl.ProviderInfoCollectionImpl;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RuleAnalysisComputationImplTest {
  private DepsAwareExecutor<? super ComputeResult, ?> depsAwareExecutor;
  private RuleAnalysisCache cache;

  private final ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
  private final CellPathResolver cellPathResolver = TestCellPathResolver.get(projectFilesystem);
  private final TargetNodeFactory targetNodeFactory =
      new TargetNodeFactory(new DefaultTypeCoercerFactory());

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
        ProviderInfoCollectionImpl.builder()
            .put(new FakeInfo(new FakeBuiltInProvider("myprovider", FakeInfo.class)))
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
            FakeRuleDescriptionArg.builder().build(),
            projectFilesystem,
            buildTarget,
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            cellPathResolver);
    MutableDirectedGraph<TargetNode<?>> graph = new MutableDirectedGraph<>();
    graph.addNode(targetNode);
    ImmutableMap<BuildTarget, TargetNode<?>> targetNodeIndex =
        ImmutableMap.of(buildTarget, targetNode);
    TargetGraph targetGraph = new TargetGraph(graph, targetNodeIndex);

    RuleAnalysisComputationImpl ruleAnalysisComputation =
        RuleAnalysisComputationImpl.of(targetGraph, depsAwareExecutor, cache);

    RuleAnalysisResult ruleAnalysisResult =
        ruleAnalysisComputation.computeUnchecked(ImmutableRuleAnalysisKey.of(buildTarget));

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
        ProviderInfoCollectionImpl.builder()
            .put(new FakeInfo(new FakeBuiltInProvider("myprovider", FakeInfo.class)))
            .build();

    RuleDescription<FakeRuleDescriptionArg> ruleDescription =
        new RuleDescription<FakeRuleDescriptionArg>() {
          @Override
          public ProviderInfoCollection ruleImpl(
              RuleAnalysisContext context, BuildTarget target, FakeRuleDescriptionArg args) {
            // here we use the deps
            assertEquals(buildTarget, target);
            return context.deps().get(ImmutableRuleAnalysisKey.of(buildTarget2));
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
            FakeRuleDescriptionArg.builder().build(),
            projectFilesystem,
            buildTarget,
            ImmutableSet.of(buildTarget2),
            ImmutableSet.of(),
            ImmutableSet.of(),
            cellPathResolver);
    TargetNode<?> targetNode2 =
        targetNodeFactory.createFromObject(
            ruleDescription2,
            FakeRuleDescriptionArg.builder().build(),
            projectFilesystem,
            buildTarget2,
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            cellPathResolver);

    MutableDirectedGraph<TargetNode<?>> graph = new MutableDirectedGraph<>();
    graph.addNode(targetNode);
    graph.addNode(targetNode2);
    graph.addEdge(targetNode, targetNode2);
    ImmutableMap<BuildTarget, TargetNode<?>> targetNodeIndex =
        ImmutableMap.of(buildTarget, targetNode, buildTarget2, targetNode2);
    TargetGraph targetGraph = new TargetGraph(graph, targetNodeIndex);

    RuleAnalysisComputationImpl ruleAnalysisComputation =
        RuleAnalysisComputationImpl.of(targetGraph, depsAwareExecutor, cache);

    RuleAnalysisResult ruleAnalysisResult =
        ruleAnalysisComputation.computeUnchecked(ImmutableRuleAnalysisKey.of(buildTarget));

    // We shouldn't be making copies of the providers or build target in our transformation. It
    // should be as given.
    assertSame(expectedProviders, ruleAnalysisResult.getProviderInfos());
    assertSame(buildTarget, ruleAnalysisResult.getBuildTarget());
  }
}
