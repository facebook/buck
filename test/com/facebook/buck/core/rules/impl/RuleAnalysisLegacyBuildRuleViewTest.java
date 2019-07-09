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
package com.facebook.buck.core.rules.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.rules.actions.ActionRegistry;
import com.facebook.buck.core.rules.actions.ActionWrapperData;
import com.facebook.buck.core.rules.actions.DefaultActionRegistry;
import com.facebook.buck.core.rules.actions.FakeAction;
import com.facebook.buck.core.rules.actions.FakeActionAnalysisRegistry;
import com.facebook.buck.core.rules.actions.ImmutableActionExecutionSuccess;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData.ID;
import com.facebook.buck.core.rules.analysis.impl.FakeBuiltInProvider;
import com.facebook.buck.core.rules.analysis.impl.FakeInfo;
import com.facebook.buck.core.rules.analysis.impl.ImmutableFakeRuleAnalysisResultImpl;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.facebook.buck.core.rules.providers.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.impl.ProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hamcrest.Matchers;
import org.junit.Test;

public class RuleAnalysisLegacyBuildRuleViewTest {

  private ProjectFilesystem filesystem = new FakeProjectFilesystem();

  @Test
  public void buildRuleViewReturnsCorrectInformation()
      throws ActionCreationException, IOException, InterruptedException {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//my:foo");
    BuildTarget depTarget = BuildTargetFactory.newInstance("//my:bar");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    BuildRule fakeDepRule = new FakeBuildRule(depTarget);
    TargetNode<?> depNode = FakeTargetNodeBuilder.build(fakeDepRule);
    MutableDirectedGraph<TargetNode<?>> graph = MutableDirectedGraph.createConcurrent();
    graph.addNode(depNode);
    TargetGraph targetGraph = new TargetGraph(graph, ImmutableMap.of(depTarget, depNode));

    ActionGraphBuilder actionGraphBuilder =
        new TestActionGraphBuilder(
            targetGraph,
            new TargetNodeToBuildRuleTransformer() {
              @Override
              public <T> BuildRule transform(
                  ToolchainProvider toolchainProvider,
                  TargetGraph targetGraph,
                  ConfigurationRuleRegistry configurationRuleRegistry,
                  ActionGraphBuilder graphBuilder,
                  TargetNode<T> targetNode) {
                assertSame(depNode, targetNode);
                return fakeDepRule;
              }
            });
    actionGraphBuilder.requireRule(depTarget);

    FakeActionAnalysisRegistry actionAnalysisRegistry = new FakeActionAnalysisRegistry();

    FakeAction.FakeActionExecuteLambda depActionFunction =
        (ins, outs, ctx) -> ImmutableActionExecutionSuccess.of(Optional.empty(), Optional.empty());

    ActionRegistry actionRegistry =
        new DefaultActionRegistry(depTarget, actionAnalysisRegistry, filesystem);
    Artifact depArtifact = actionRegistry.declareArtifact(Paths.get("bar.output"));

    new FakeAction(
        actionRegistry, ImmutableSet.of(), ImmutableSet.of(depArtifact), depActionFunction);

    Path outpath = Paths.get("foo.output");
    Path packagePath = BuildPaths.getGenDir(filesystem, buildTarget);

    AtomicBoolean functionCalled = new AtomicBoolean();
    FakeAction.FakeActionExecuteLambda actionFunction =
        (ins, outs, ctx) -> {
          assertEquals(ImmutableSet.of(depArtifact), ins);
          assertEquals(
              buildTarget,
              Objects.requireNonNull(Iterables.getOnlyElement(outs).asBound().asBuildArtifact())
                  .getActionDataKey()
                  .getBuildTarget());
          assertEquals(
              ExplicitBuildTargetSourcePath.of(buildTarget, packagePath.resolve(outpath)),
              Iterables.getOnlyElement(outs).asBound().getSourcePath());
          functionCalled.set(true);
          return ImmutableActionExecutionSuccess.of(Optional.empty(), Optional.empty());
        };

    actionRegistry = new DefaultActionRegistry(buildTarget, actionAnalysisRegistry, filesystem);
    Artifact artifact = actionRegistry.declareArtifact(outpath);

    new FakeAction(
        actionRegistry, ImmutableSet.of(depArtifact), ImmutableSet.of(artifact), actionFunction);

    ProviderInfoCollection providerInfoCollection =
        ProviderInfoCollectionImpl.builder()
            .put(new FakeInfo(new FakeBuiltInProvider("foo")))
            .build();

    Map<ID, ActionAnalysisData> actionAnalysisDataMap =
        actionAnalysisRegistry.getRegistered().entrySet().stream()
            .collect(
                ImmutableMap.toImmutableMap(
                    entry -> entry.getKey().getID(), entry -> entry.getValue()));
    RuleAnalysisResult ruleAnalysisResult =
        ImmutableFakeRuleAnalysisResultImpl.of(
            buildTarget, providerInfoCollection, actionAnalysisDataMap);

    ActionWrapperData actionWrapperData =
        (ActionWrapperData)
            actionAnalysisDataMap.get(
                Objects.requireNonNull(artifact.asBound().asBuildArtifact())
                    .getActionDataKey()
                    .getID());

    BuildRule buildRule =
        new RuleAnalysisLegacyBuildRuleView(
            "my_type",
            ruleAnalysisResult.getBuildTarget(),
            actionWrapperData.getAction(),
            actionGraphBuilder,
            projectFilesystem);

    assertSame(buildTarget, buildRule.getBuildTarget());
    assertSame(projectFilesystem, buildRule.getProjectFilesystem());
    assertEquals("my_type", buildRule.getType());
    assertEquals(
        ExplicitBuildTargetSourcePath.of(buildTarget, packagePath.resolve("foo.output")),
        buildRule.getSourcePathToOutput());

    assertEquals(ImmutableSortedSet.of(fakeDepRule), buildRule.getBuildDeps());
    FakeBuildableContext buildableContext = new FakeBuildableContext();
    ImmutableList<? extends Step> steps =
        buildRule.getBuildSteps(FakeBuildContext.NOOP_CONTEXT, buildableContext);
    assertEquals(
        ImmutableSet.of(packagePath.resolve("foo.output")),
        buildableContext.getRecordedArtifacts());
    assertThat(steps, Matchers.hasSize(1));

    Step step = Iterables.getOnlyElement(steps);
    step.execute(TestExecutionContext.newInstance());

    assertTrue(functionCalled.get());
  }
}
