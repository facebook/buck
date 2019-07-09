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
package com.facebook.buck.core.rules.transformer.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.description.RuleDescription;
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
import com.facebook.buck.core.rules.actions.ActionRegistry;
import com.facebook.buck.core.rules.actions.DefaultActionRegistry;
import com.facebook.buck.core.rules.actions.FakeAction;
import com.facebook.buck.core.rules.actions.FakeActionAnalysisRegistry;
import com.facebook.buck.core.rules.actions.ImmutableActionExecutionSuccess;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.analysis.computation.RuleAnalysisComputation;
import com.facebook.buck.core.rules.analysis.impl.FakeRuleAnalysisComputation;
import com.facebook.buck.core.rules.analysis.impl.ImmutableFakeRuleAnalysisResultImpl;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.facebook.buck.core.rules.config.registry.impl.ConfigurationRuleRegistryFactory;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.core.rules.impl.RuleAnalysisLegacyBuildRuleView;
import com.facebook.buck.core.rules.providers.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.impl.ProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hamcrest.Matchers;
import org.junit.Test;

public class LegacyRuleAnalysisDelegatingTargetNodeToBuildRuleTransformerTest {

  private final ProjectFilesystem fakeFilesystem = new FakeProjectFilesystem();

  @Test
  public void transformDelegatesWhenOldDescription() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    TargetNode<?> targetNode = FakeTargetNodeBuilder.newBuilder(target).build();

    ToolchainProvider toolchainProvider = new ToolchainProviderBuilder().build();
    ActionGraphBuilder actionGraphBuilder = new TestActionGraphBuilder();
    TargetGraph targetGraph = TargetGraph.EMPTY;

    BuildRule rule = new FakeBuildRule(target);

    RuleAnalysisComputation ruleAnalysisComputation =
        new FakeRuleAnalysisComputation(
            ignored -> {
              fail();
              return null;
            });

    TargetNodeToBuildRuleTransformer delegate =
        new TargetNodeToBuildRuleTransformer() {
          @Override
          public <T> BuildRule transform(
              ToolchainProvider tool,
              TargetGraph targetGraph,
              ConfigurationRuleRegistry configurationRuleRegistry,
              ActionGraphBuilder graphBuilder,
              TargetNode<T> node) {
            assertSame(targetNode, node);
            return rule;
          }
        };

    LegacyRuleAnalysisDelegatingTargetNodeToBuildRuleTransformer transformer =
        new LegacyRuleAnalysisDelegatingTargetNodeToBuildRuleTransformer(
            ruleAnalysisComputation, delegate);

    assertSame(
        rule,
        transformer.transform(
            toolchainProvider,
            targetGraph,
            ConfigurationRuleRegistryFactory.createRegistry(targetGraph),
            actionGraphBuilder,
            targetNode));
  }

  @Test
  public void transformDelegatesWhenNewDescription() throws ActionCreationException {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");

    TargetNodeFactory nodeCopier = new TargetNodeFactory(new DefaultTypeCoercerFactory());
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    RuleDescription<?> description =
        new RuleDescription() {
          @Override
          public ProviderInfoCollection ruleImpl(
              RuleAnalysisContext context, BuildTarget target, Object args) {
            return ProviderInfoCollectionImpl.builder().build();
          }

          @Override
          public Class<FakeTargetNodeArg> getConstructorArgType() {
            return FakeTargetNodeArg.class;
          }
        };

    TargetNode<?> targetNode =
        nodeCopier.createFromObject(
            description,
            FakeTargetNodeArg.builder().setName("name").build(),
            projectFilesystem,
            target,
            ImmutableSet.of(),
            ImmutableSortedSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            DefaultCellPathResolver.of(Paths.get(""), ImmutableMap.of()));

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
        ImmutableSet.of(),
        ImmutableSet.of(artifact),
        (ins, outs, ctx) -> ImmutableActionExecutionSuccess.of(Optional.empty(), Optional.empty()));

    AtomicBoolean ruleAnalysisCalled = new AtomicBoolean();
    RuleAnalysisComputation ruleAnalysisComputation =
        new FakeRuleAnalysisComputation(
            ruleAnalysisKey -> {
              ruleAnalysisCalled.set(true);
              assertSame(target, ruleAnalysisKey.getBuildTarget());
              return ImmutableFakeRuleAnalysisResultImpl.of(
                  target,
                  ProviderInfoCollectionImpl.builder().build(),
                  fakeActionAnalysisRegistry.getRegistered().entrySet().stream()
                      .collect(
                          ImmutableMap.toImmutableMap(
                              entry -> entry.getKey().getID(), entry -> entry.getValue())));
            });

    TargetNodeToBuildRuleTransformer delegate =
        new TargetNodeToBuildRuleTransformer() {
          @Override
          public <T> BuildRule transform(
              ToolchainProvider tool,
              TargetGraph targetGraph,
              ConfigurationRuleRegistry configurationRuleRegistry,
              ActionGraphBuilder graphBuilder,
              TargetNode<T> node) {
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
            targetNode);

    assertTrue(ruleAnalysisCalled.get());
    assertSame(target, rule.getBuildTarget());
    assertEquals(
        ExplicitBuildTargetSourcePath.of(
            target, BuildPaths.getGenDir(fakeFilesystem, target).resolve(output)),
        rule.getSourcePathToOutput());
    assertEquals(ImmutableSet.of(), rule.getBuildDeps());

    assertThat(rule, Matchers.instanceOf(RuleAnalysisLegacyBuildRuleView.class));
  }

  @Test
  public void transformCreatesNoopRuleWhenNewRuleHasNoActions() throws ActionCreationException {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");

    TargetNodeFactory nodeCopier = new TargetNodeFactory(new DefaultTypeCoercerFactory());
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    RuleDescription<?> description =
        new RuleDescription() {
          @Override
          public ProviderInfoCollection ruleImpl(
              RuleAnalysisContext context, BuildTarget target, Object args) {
            return ProviderInfoCollectionImpl.builder().build();
          }

          @Override
          public Class<FakeTargetNodeArg> getConstructorArgType() {
            return FakeTargetNodeArg.class;
          }
        };

    TargetNode<?> targetNode =
        nodeCopier.createFromObject(
            description,
            FakeTargetNodeArg.builder().setName("name").build(),
            projectFilesystem,
            target,
            ImmutableSet.of(),
            ImmutableSortedSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            DefaultCellPathResolver.of(Paths.get(""), ImmutableMap.of()));

    ToolchainProvider toolchainProvider = new ToolchainProviderBuilder().build();
    ActionGraphBuilder actionGraphBuilder = new TestActionGraphBuilder();
    TargetGraph targetGraph = TargetGraph.EMPTY;

    AtomicBoolean ruleAnalysisCalled = new AtomicBoolean();
    RuleAnalysisComputation ruleAnalysisComputation =
        new FakeRuleAnalysisComputation(
            ruleAnalysisKey -> {
              ruleAnalysisCalled.set(true);
              assertSame(target, ruleAnalysisKey.getBuildTarget());
              return ImmutableFakeRuleAnalysisResultImpl.of(
                  target, ProviderInfoCollectionImpl.builder().build(), ImmutableMap.of());
            });

    TargetNodeToBuildRuleTransformer delegate =
        new TargetNodeToBuildRuleTransformer() {
          @Override
          public <T> BuildRule transform(
              ToolchainProvider tool,
              TargetGraph targetGraph,
              ConfigurationRuleRegistry configurationRuleRegistry,
              ActionGraphBuilder graphBuilder,
              TargetNode<T> node) {
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
            targetNode);

    assertTrue(ruleAnalysisCalled.get());
    assertSame(target, rule.getBuildTarget());
    assertNull(rule.getSourcePathToOutput());
    assertEquals(ImmutableSet.of(), rule.getBuildDeps());

    assertThat(rule, Matchers.instanceOf(NoopBuildRule.class));
  }
}
