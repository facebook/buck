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

package com.facebook.buck.core.rules.transformer.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.nameresolver.SingleRootCellNameResolverProvider;
import com.facebook.buck.core.description.RuleDescription;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeArg;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.rules.actions.ActionExecutionResult;
import com.facebook.buck.core.rules.actions.ActionRegistry;
import com.facebook.buck.core.rules.actions.DefaultActionRegistry;
import com.facebook.buck.core.rules.actions.FakeAction;
import com.facebook.buck.core.rules.actions.FakeActionAnalysisRegistry;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.analysis.computation.RuleAnalysisGraph;
import com.facebook.buck.core.rules.analysis.impl.FakeBuiltInProvider;
import com.facebook.buck.core.rules.analysis.impl.FakeInfo;
import com.facebook.buck.core.rules.analysis.impl.FakeLegacyProviderRuleAnalysisResultImpl;
import com.facebook.buck.core.rules.analysis.impl.FakeRuleAnalysisGraph;
import com.facebook.buck.core.rules.analysis.impl.FakeRuleAnalysisResultImpl;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.facebook.buck.core.rules.config.registry.impl.ConfigurationRuleRegistryFactory;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.impl.RuleAnalysisLegacyBuildRuleView;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.TestProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.providers.lib.ImmutableDefaultInfo;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hamcrest.Matchers;
import org.junit.Test;

public class LegacyRuleAnalysisProviderCompatibleTargetNodeToBuildRuleTransformerTest {

  private final ProjectFilesystem fakeFilesystem = new FakeProjectFilesystem();

  @Test
  public void transformDelegatesWithProvidersWhenOldDescription() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    TargetNode<? extends BuildRuleArg> targetNode =
        FakeTargetNodeBuilder.newBuilder(target).build();

    ToolchainProvider toolchainProvider = new ToolchainProviderBuilder().build();
    ActionGraphBuilder actionGraphBuilder = new TestActionGraphBuilder();
    TargetGraph targetGraph = TargetGraph.EMPTY;

    BuildRule rule = new FakeBuildRule(target);
    ProviderInfoCollection expectedProviderInfoCollection =
        TestProviderInfoCollectionImpl.builder()
            .put(new FakeInfo(new FakeBuiltInProvider("blah")))
            .build();

    RuleAnalysisGraph ruleAnalysisComputation =
        new FakeRuleAnalysisGraph(
            key -> {
              if (key.getBuildTarget().equals(target)) {
                return FakeLegacyProviderRuleAnalysisResultImpl.of(
                    target, expectedProviderInfoCollection);
              }
              fail();
              return null;
            });

    TargetNodeToBuildRuleTransformer delegate =
        new TargetNodeToBuildRuleTransformer() {
          @Override
          public <T extends BuildRuleArg> BuildRule transform(
              ToolchainProvider tool,
              TargetGraph targetGraph,
              ConfigurationRuleRegistry configurationRuleRegistry,
              ActionGraphBuilder graphBuilder,
              TargetNode<T> node,
              ProviderInfoCollection providerInfoCollection,
              CellPathResolver cellPathResolver) {
            assertSame(expectedProviderInfoCollection, providerInfoCollection);
            assertSame(targetNode, node);
            return rule;
          }
        };

    LegacyRuleAnalysisProviderCompatibleTargetNodeToBuildRuleTransformer transformer =
        new LegacyRuleAnalysisProviderCompatibleTargetNodeToBuildRuleTransformer(
            ruleAnalysisComputation, delegate);

    assertSame(
        rule,
        transformer.transform(
            toolchainProvider,
            targetGraph,
            ConfigurationRuleRegistryFactory.createRegistry(targetGraph),
            actionGraphBuilder,
            targetNode,
            TestCellPathResolver.get(fakeFilesystem)));
  }

  private static class FakeTargetNodeRuleDescription implements RuleDescription<FakeTargetNodeArg> {
    @Override
    public ProviderInfoCollection ruleImpl(
        RuleAnalysisContext context, BuildTarget target, FakeTargetNodeArg args) {
      return TestProviderInfoCollectionImpl.builder().build();
    }

    @Override
    public Class<FakeTargetNodeArg> getConstructorArgType() {
      return FakeTargetNodeArg.class;
    }
  };

  @Test
  public void transformDelegatesWhenNewDescription() throws ActionCreationException {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");

    TargetNodeFactory nodeCopier =
        new TargetNodeFactory(
            new DefaultTypeCoercerFactory(), SingleRootCellNameResolverProvider.INSTANCE);
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    FakeTargetNodeRuleDescription description = new FakeTargetNodeRuleDescription();

    TargetNode<? extends BuildRuleArg> targetNode =
        nodeCopier.createFromObject(
            description,
            FakeTargetNodeArg.builder().setName("name").build(),
            projectFilesystem,
            target,
            DependencyStack.root(),
            ImmutableSet.of(),
            ImmutableSortedSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of());

    ToolchainProvider toolchainProvider = new ToolchainProviderBuilder().build();
    ActionGraphBuilder actionGraphBuilder = new TestActionGraphBuilder();
    TargetGraph targetGraph = TargetGraph.EMPTY;

    FakeActionAnalysisRegistry fakeActionAnalysisRegistry = new FakeActionAnalysisRegistry();

    Path output = Paths.get("foo.output");

    ActionRegistry actionRegistry =
        new DefaultActionRegistry(target, fakeActionAnalysisRegistry, fakeFilesystem);
    Artifact artifact = actionRegistry.declareArtifact(output);

    new FakeAction(
        actionRegistry,
        ImmutableSortedSet.of(),
        ImmutableSortedSet.of(),
        ImmutableSortedSet.of(artifact),
        (srcs, ins, outs, ctx) ->
            ActionExecutionResult.success(Optional.empty(), Optional.empty(), ImmutableList.of()));

    AtomicBoolean ruleAnalysisCalled = new AtomicBoolean();
    RuleAnalysisGraph ruleAnalysisComputation =
        new FakeRuleAnalysisGraph(
            ruleAnalysisKey -> {
              ruleAnalysisCalled.set(true);
              assertSame(target, ruleAnalysisKey.getBuildTarget());
              return FakeRuleAnalysisResultImpl.of(
                  target,
                  TestProviderInfoCollectionImpl.builder()
                      .build(
                          new ImmutableDefaultInfo(SkylarkDict.empty(), ImmutableSet.of(artifact))),
                  fakeActionAnalysisRegistry.getRegistered().entrySet().stream()
                      .collect(
                          ImmutableMap.toImmutableMap(
                              entry -> entry.getKey().getID(), entry -> entry.getValue())));
            });

    TargetNodeToBuildRuleTransformer delegate =
        new TargetNodeToBuildRuleTransformer() {
          @Override
          public <T extends BuildRuleArg> BuildRule transform(
              ToolchainProvider tool,
              TargetGraph targetGraph,
              ConfigurationRuleRegistry configurationRuleRegistry,
              ActionGraphBuilder graphBuilder,
              TargetNode<T> node,
              ProviderInfoCollection providerInfoCollection,
              CellPathResolver cellPathResolver) {
            fail();
            return null;
          }
        };

    LegacyRuleAnalysisDelegatingTargetNodeToBuildRuleTransformer transformer =
        new LegacyRuleAnalysisDelegatingTargetNodeToBuildRuleTransformer(
            ruleAnalysisComputation, delegate);
    BuildRule rule =
        transformer.transform(
            toolchainProvider,
            targetGraph,
            ConfigurationRuleRegistryFactory.createRegistry(targetGraph),
            actionGraphBuilder,
            targetNode,
            TestCellPathResolver.get(projectFilesystem));

    assertTrue(ruleAnalysisCalled.get());
    assertSame(target, rule.getBuildTarget());
    assertEquals(
        ExplicitBuildTargetSourcePath.of(
            target, BuildPaths.getGenDir(fakeFilesystem, target).resolve(output)),
        rule.getSourcePathToOutput());
    assertEquals(ImmutableSet.of(), rule.getBuildDeps());
    assertEquals(rule.getType(), "fake_target_node");

    assertThat(rule, Matchers.instanceOf(RuleAnalysisLegacyBuildRuleView.class));
  }
}
