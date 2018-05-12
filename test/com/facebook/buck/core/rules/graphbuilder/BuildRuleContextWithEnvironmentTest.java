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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.graph.transformation.TestTransformationEnvironment;
import com.facebook.buck.core.graph.transformation.TransformationEnvironment;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.provider.BuildRuleInfoProviderCollection;
import com.facebook.buck.core.rules.provider.DefaultBuildRuleInfoProvider;
import com.facebook.buck.core.rules.provider.FakeBuildRuleInfoProvider;
import com.facebook.buck.core.rules.provider.FakeBuildRuleWithProviders;
import com.facebook.buck.core.rules.resolver.impl.TestBuildRuleResolver;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeTargetNodeBuilder;
import com.facebook.buck.rules.ImmutableBuildRuleCreationContext;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.junit.Before;
import org.junit.Test;

/**
 * Test and demonstration of {@link BuildRuleContextWithEnvironment} to verify that it calls into
 * {@link TransformationEnvironment} and responds with correct {@link
 * BuildRuleInfoProviderCollection}.
 */
public class BuildRuleContextWithEnvironmentTest {

  private MutableDirectedGraph<TargetNode<?, ?>> mutableTargetGraph;
  private BuildRuleResolver ruleResolver;
  private ProjectFilesystem projectFilesystem;
  private CellPathResolver cellPathResolver;
  private ToolchainProvider toolchainProvider;
  private BuildRuleInfoProviderCollection.Builder providerCollectionBuilder;

  @Before
  public void setUp() {
    mutableTargetGraph = new MutableDirectedGraph<>();
    ruleResolver = new TestBuildRuleResolver();
    projectFilesystem = new FakeProjectFilesystem();
    cellPathResolver = new TestCellBuilder().build().getCellPathResolver();
    toolchainProvider = new ToolchainProviderBuilder().build();

    providerCollectionBuilder =
        BuildRuleInfoProviderCollection.builder()
            .put(
                DefaultBuildRuleInfoProvider.of(
                    FakeBuildRuleWithProviders.class,
                    BuildTargetFactory.newInstance("//fake:rule"),
                    null,
                    projectFilesystem));

    mutableTargetGraph.addNode(
        FakeTargetNodeBuilder.build(
            new FakeBuildRuleWithProviders(providerCollectionBuilder.build())));
  }

  @Test
  public void canRetrieveSingleDependency() {
    BuildTarget fakeKeyTarget = BuildTargetFactory.newInstance("//fake:key");
    TargetNode fakeTargetNode = FakeTargetNodeBuilder.build(new FakeBuildRule(fakeKeyTarget));
    mutableTargetGraph.addNode(fakeTargetNode);

    BuildRuleKey key =
        ImmutableBuildRuleKey.of(
            fakeKeyTarget,
            ImmutableBuildRuleCreationContext.of(
                new TargetGraph(mutableTargetGraph, ImmutableMap.of(fakeKeyTarget, fakeTargetNode)),
                ruleResolver,
                projectFilesystem,
                cellPathResolver,
                toolchainProvider));

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//fake:fake");
    BuildRule expectedRule = new FakeBuildRule(buildTarget);

    BuildRule returnedRule = new FakeBuildRuleWithProviders(providerCollectionBuilder.build());

    TransformationEnvironment<BuildRuleKey, BuildRule> environment =
        new TestTransformationEnvironment<BuildRuleKey, BuildRule>() {
          @Override
          public CompletionStage<BuildRule> evaluate(
              BuildRuleKey buildRuleKey, Function<BuildRule, BuildRule> asyncTransformation) {
            Preconditions.checkArgument(key.equals(buildRuleKey));
            return CompletableFuture.completedFuture(asyncTransformation.apply(returnedRule));
          }
        };

    BuildRuleContextWithEnvironment context =
        ImmutableBuildRuleContextWithEnvironment.of(key, environment);
    assertEquals(
        expectedRule,
        Futures.getUnchecked(
            context
                .getProviderCollectionForDep(
                    key,
                    providerCollection -> {
                      assertEquals(returnedRule.getProviderCollection(), providerCollection);
                      return expectedRule;
                    })
                .toCompletableFuture()));
  }

  @Test
  public void canRetrieveMultipleDependencies() {
    BuildTarget fakeKeyTarget1 = BuildTargetFactory.newInstance("//fake:key1");
    BuildTarget fakeKeyTarget2 = BuildTargetFactory.newInstance("//fake:key2");

    TargetNode fakeTargetNode1 = FakeTargetNodeBuilder.build(new FakeBuildRule(fakeKeyTarget1));
    mutableTargetGraph.addNode(fakeTargetNode1);
    TargetNode fakeTargetNode2 = FakeTargetNodeBuilder.build(new FakeBuildRule(fakeKeyTarget2));
    mutableTargetGraph.addNode(fakeTargetNode2);

    TargetGraph targetGraph =
        new TargetGraph(
            mutableTargetGraph,
            ImmutableMap.of(fakeKeyTarget1, fakeTargetNode1, fakeKeyTarget2, fakeTargetNode2));

    BuildRuleKey key1 =
        ImmutableBuildRuleKey.of(
            fakeKeyTarget1,
            ImmutableBuildRuleCreationContext.of(
                targetGraph, ruleResolver, projectFilesystem, cellPathResolver, toolchainProvider));

    BuildRuleKey key2 =
        ImmutableBuildRuleKey.of(
            fakeKeyTarget2,
            ImmutableBuildRuleCreationContext.of(
                targetGraph, ruleResolver, projectFilesystem, cellPathResolver, toolchainProvider));

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//fake:fake");
    BuildRule expectedRule = new FakeBuildRule(buildTarget);

    BuildRule returnedRule1 = new FakeBuildRuleWithProviders(providerCollectionBuilder.build());
    BuildRule returnedRule2 =
        new FakeBuildRuleWithProviders(
            providerCollectionBuilder.put(new FakeBuildRuleInfoProvider(1)).build());

    TransformationEnvironment<BuildRuleKey, BuildRule> environment =
        new TestTransformationEnvironment<BuildRuleKey, BuildRule>() {
          @Override
          public CompletionStage<BuildRule> evaluateAll(
              Iterable<BuildRuleKey> buildRuleKeys,
              Function<ImmutableMap<BuildRuleKey, BuildRule>, BuildRule> asyncTransformation) {
            Preconditions.checkArgument(ImmutableSet.of(key1, key2).equals(buildRuleKeys));
            return CompletableFuture.completedFuture(
                asyncTransformation.apply(
                    ImmutableMap.of(key1, returnedRule1, key2, returnedRule2)));
          }
        };

    BuildRuleContextWithEnvironment context =
        ImmutableBuildRuleContextWithEnvironment.of(key1, environment);
    assertEquals(
        expectedRule,
        Futures.getUnchecked(
            context
                .getProviderCollectionForDeps(
                    ImmutableSet.of(key1, key2),
                    providerCollectionImmutableMap -> {
                      assertEquals(2, providerCollectionImmutableMap.size());
                      assertEquals(
                          returnedRule1.getProviderCollection(),
                          providerCollectionImmutableMap.get(key1));
                      assertEquals(
                          returnedRule2.getProviderCollection(),
                          providerCollectionImmutableMap.get(key2));
                      return expectedRule;
                    })
                .toCompletableFuture()));
  }
}
