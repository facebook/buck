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

package com.facebook.buck.core.rules.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.actions.Action;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.rules.actions.ActionExecutionResult;
import com.facebook.buck.core.rules.actions.ActionRegistry;
import com.facebook.buck.core.rules.actions.ActionWrapperData;
import com.facebook.buck.core.rules.actions.DefaultActionRegistry;
import com.facebook.buck.core.rules.actions.FakeAction;
import com.facebook.buck.core.rules.actions.FakeActionAnalysisRegistry;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData.ID;
import com.facebook.buck.core.rules.analysis.impl.FakeBuiltInProvider;
import com.facebook.buck.core.rules.analysis.impl.FakeInfo;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.TestProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.providers.lib.ImmutableDefaultInfo;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.starlark.compatible.BuckStarlark;
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
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.BazelLibrary;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RuleAnalysisLegacyBuildRuleViewTest {
  @Rule public final ExpectedException exception = ExpectedException.none();

  private ProjectFilesystem filesystem = new FakeProjectFilesystem();

  @Test
  public void buildRuleViewReturnsCorrectInformation()
      throws ActionCreationException, IOException, InterruptedException, EvalException {
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
              public <T extends BuildRuleArg> BuildRule transform(
                  ToolchainProvider toolchainProvider,
                  TargetGraph targetGraph,
                  ConfigurationRuleRegistry configurationRuleRegistry,
                  ActionGraphBuilder graphBuilder,
                  TargetNode<T> targetNode,
                  ProviderInfoCollection providerInfoCollection,
                  CellPathResolver cellPathResolver) {
                assertSame(depNode, targetNode);
                return fakeDepRule;
              }
            });
    actionGraphBuilder.requireRule(depTarget);

    FakeActionAnalysisRegistry actionAnalysisRegistry = new FakeActionAnalysisRegistry();

    FakeAction.FakeActionExecuteLambda depActionFunction =
        (srcs, ins, outs, ctx) ->
            ActionExecutionResult.success(Optional.empty(), Optional.empty(), ImmutableList.of());

    ActionRegistry actionRegistry =
        new DefaultActionRegistry(depTarget, actionAnalysisRegistry, filesystem);
    Artifact depArtifact = actionRegistry.declareArtifact(Paths.get("bar.output"));

    new FakeAction(
        actionRegistry,
        ImmutableSortedSet.of(),
        ImmutableSortedSet.of(),
        ImmutableSortedSet.of(depArtifact),
        depActionFunction);

    Path outpath = Paths.get("foo.output");
    Path packagePath = BuildPaths.getGenDir(filesystem, buildTarget);

    AtomicBoolean functionCalled = new AtomicBoolean();
    FakeAction.FakeActionExecuteLambda actionFunction =
        (srcs, ins, outs, ctx) -> {
          assertEquals(ImmutableSortedSet.of(depArtifact), ins);
          assertEquals(
              buildTarget,
              Objects.requireNonNull(Iterables.getOnlyElement(outs).asBound().asBuildArtifact())
                  .getActionDataKey()
                  .getBuildTarget());
          assertEquals(
              ExplicitBuildTargetSourcePath.of(buildTarget, packagePath.resolve(outpath)),
              Iterables.getOnlyElement(outs).asBound().getSourcePath());
          functionCalled.set(true);
          return ActionExecutionResult.success(
              Optional.empty(), Optional.empty(), ImmutableList.of());
        };

    actionRegistry = new DefaultActionRegistry(buildTarget, actionAnalysisRegistry, filesystem);
    Artifact artifact = actionRegistry.declareArtifact(outpath);

    createFakeAction(
        actionRegistry,
        ImmutableSortedSet.of(depArtifact),
        ImmutableSortedSet.of(artifact),
        actionFunction);

    ProviderInfoCollection providerInfoCollection =
        createProviderInfoCollection(ImmutableMap.of(), ImmutableSet.of(artifact));

    RuleAnalysisLegacyBuildRuleView buildRule =
        createRuleAnalysisLegacyBuildRuleView(
            buildTarget,
            projectFilesystem,
            actionGraphBuilder,
            Optional.of(new ActionCreationInput(actionAnalysisRegistry, artifact)),
            providerInfoCollection);

    assertSame(buildTarget, buildRule.getBuildTarget());
    assertSame(projectFilesystem, buildRule.getProjectFilesystem());
    assertSame(providerInfoCollection, buildRule.getProviderInfos());
    assertEquals("my_type", buildRule.getType());
    assertEquals(
        ExplicitBuildTargetSourcePath.of(buildTarget, packagePath.resolve("foo.output")),
        buildRule.getSourcePathToOutput());

    assertEquals(ImmutableSortedSet.of(fakeDepRule), buildRule.getBuildDeps());
    FakeBuildableContext buildableContext = new FakeBuildableContext();
    ImmutableList<? extends Step> steps =
        buildRule.getBuildSteps(FakeBuildContext.NOOP_CONTEXT, buildableContext);
    assertEquals(
        ImmutableSortedSet.of(packagePath.resolve("foo.output")),
        buildableContext.getRecordedArtifacts());
    assertThat(steps, Matchers.hasSize(1));

    Step step = Iterables.getOnlyElement(steps);
    step.execute(TestExecutionContext.newInstance());

    assertTrue(functionCalled.get());
  }

  @Test
  public void canGetSourcePathsForMultipleOutputs() throws ActionCreationException, EvalException {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//my:foo");
    FakeActionAnalysisRegistry actionAnalysisRegistry = new FakeActionAnalysisRegistry();

    Path setOneOutputOne = Paths.get("foo.set1.output1");
    Path setTwoOutputOne = Paths.get("foo.set2.output1");
    Path setTwoOutputTwo = Paths.get("foo.set2.output2");
    Path defaultOutput = Paths.get("default");
    Path defaultOutput2 = Paths.get("default2");
    Path packagePath = BuildPaths.getGenDir(filesystem, buildTarget);

    ActionRegistry actionRegistry =
        new DefaultActionRegistry(buildTarget, actionAnalysisRegistry, filesystem);
    Artifact setOneArtifactOne = actionRegistry.declareArtifact(setOneOutputOne);
    Artifact setTwoArtifactOne = actionRegistry.declareArtifact(setTwoOutputOne);
    Artifact setTwoArtifactTwo = actionRegistry.declareArtifact(setTwoOutputTwo);
    Artifact defaultArtifact = actionRegistry.declareArtifact(defaultOutput);
    Artifact defaultArtifact2 = actionRegistry.declareArtifact(defaultOutput2);

    createFakeAction(
        actionRegistry,
        ImmutableSortedSet.of(),
        ImmutableSortedSet.of(
            setOneArtifactOne,
            setTwoArtifactOne,
            setTwoArtifactTwo,
            defaultArtifact,
            defaultArtifact2));

    ProviderInfoCollection providerInfoCollection =
        createProviderInfoCollection(
            ImmutableMap.of(
                "setOne",
                ImmutableSet.of(setOneArtifactOne),
                "setTwo",
                ImmutableSet.of(setTwoArtifactOne, setTwoArtifactTwo)),
            ImmutableSet.of(defaultArtifact, defaultArtifact2));

    RuleAnalysisLegacyBuildRuleView buildRule =
        createRuleAnalysisLegacyBuildRuleView(
            buildTarget,
            new FakeProjectFilesystem(),
            new TestActionGraphBuilder(),
            Optional.of(new ActionCreationInput(actionAnalysisRegistry, defaultArtifact)),
            providerInfoCollection);

    assertThat(
        buildRule.getSourcePathToOutput(OutputLabel.of("setOne")),
        Matchers.contains(
            ExplicitBuildTargetSourcePath.of(
                buildTarget, packagePath.resolve("foo.set1.output1"))));
    assertThat(
        buildRule.getSourcePathToOutput(OutputLabel.of("setTwo")),
        Matchers.contains(
            ExplicitBuildTargetSourcePath.of(buildTarget, packagePath.resolve("foo.set2.output1")),
            ExplicitBuildTargetSourcePath.of(
                buildTarget, packagePath.resolve("foo.set2.output2"))));
    assertThat(
        buildRule.getSourcePathToOutput(OutputLabel.defaultLabel()),
        Matchers.contains(
            ExplicitBuildTargetSourcePath.of(buildTarget, packagePath.resolve("default")),
            ExplicitBuildTargetSourcePath.of(buildTarget, packagePath.resolve("default2"))));
  }

  @Test
  public void invalidLabelThrows() throws ActionCreationException, EvalException {
    exception.expect(NullPointerException.class);
    exception.expectMessage("Cannot find output label [nonexistent] for target //my:foo");

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//my:foo");
    FakeActionAnalysisRegistry actionAnalysisRegistry = new FakeActionAnalysisRegistry();

    Path defaultOutput = Paths.get("default");

    ActionRegistry actionRegistry =
        new DefaultActionRegistry(buildTarget, actionAnalysisRegistry, filesystem);
    Artifact defaultArtifact = actionRegistry.declareArtifact(defaultOutput);

    createFakeAction(
        actionRegistry, ImmutableSortedSet.of(), ImmutableSortedSet.of(defaultArtifact));

    ProviderInfoCollection providerInfoCollection =
        createProviderInfoCollection(ImmutableMap.of(), ImmutableSet.of(defaultArtifact));

    RuleAnalysisLegacyBuildRuleView buildRule =
        createRuleAnalysisLegacyBuildRuleView(
            buildTarget,
            new FakeProjectFilesystem(),
            new TestActionGraphBuilder(),
            Optional.of(new ActionCreationInput(actionAnalysisRegistry, defaultArtifact)),
            providerInfoCollection);

    buildRule.getSourcePathToOutput(OutputLabel.of("nonexistent"));
  }

  @Test
  public void canGetOutputLabels() throws EvalException {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//my:foo");

    ProviderInfoCollection providerInfoCollection =
        createProviderInfoCollection(
            ImmutableMap.of("setOne", ImmutableSet.of(), "setTwo", ImmutableSet.of()),
            ImmutableSet.of());

    RuleAnalysisLegacyBuildRuleView buildRule =
        createRuleAnalysisLegacyBuildRuleView(
            buildTarget,
            new FakeProjectFilesystem(),
            new TestActionGraphBuilder(),
            Optional.empty(),
            providerInfoCollection);

    ImmutableSet<OutputLabel> actual = buildRule.getOutputLabels();
    assertThat(
        actual,
        Matchers.containsInAnyOrder(
            OutputLabel.of("setOne"), OutputLabel.of("setTwo"), OutputLabel.defaultLabel()));
  }

  private static FakeAction createFakeAction(
      ActionRegistry actionRegistry,
      ImmutableSortedSet<Artifact> deps,
      ImmutableSortedSet<Artifact> outputs) {
    return createFakeAction(
        actionRegistry,
        deps,
        outputs,
        (srcs, ins, outs, ctx) ->
            ActionExecutionResult.success(Optional.empty(), Optional.empty(), ImmutableList.of()));
  }

  private static FakeAction createFakeAction(
      ActionRegistry actionRegistry,
      ImmutableSortedSet<Artifact> deps,
      ImmutableSortedSet<Artifact> outputs,
      FakeAction.FakeActionExecuteLambda actionExecuteLambda) {
    return new FakeAction(
        actionRegistry, ImmutableSortedSet.of(), deps, outputs, actionExecuteLambda);
  }

  private static ProviderInfoCollection createProviderInfoCollection(
      ImmutableMap<String, ImmutableSet<Artifact>> namedOutputs,
      ImmutableSet<Artifact> defaultOutputs)
      throws EvalException {
    SkylarkDict<String, Set<Artifact>> dict;
    try (Mutability mutability = Mutability.create("test")) {
      Environment env =
          Environment.builder(mutability)
              .setGlobals(BazelLibrary.GLOBALS)
              .setSemantics(BuckStarlark.BUCK_STARLARK_SEMANTICS)
              .build();
      dict = SkylarkDict.of(env);
      for (Map.Entry<String, ImmutableSet<Artifact>> entry : namedOutputs.entrySet()) {
        dict.put(entry.getKey(), entry.getValue(), Location.BUILTIN, mutability);
      }
    }
    return TestProviderInfoCollectionImpl.builder()
        .put(new FakeInfo(new FakeBuiltInProvider("foo")))
        .build(new ImmutableDefaultInfo(dict, defaultOutputs));
  }

  /**
   * Returns a {@link RuleAnalysisLegacyBuildRuleView} for test.
   *
   * @param buildTarget the target associated with the build rule
   * @param projectFilesystem the project filesystem associated with the build rule
   * @param actionGraphBuilder builder to use for constructing the action graph
   * @param actionCreationInput inputs needed to create an action associated with the {@code
   *     RuleAnalysisLegacyBuildRuleView} under test. If not present, no action is constructed for
   *     this {@code RuleAnalysisLegacyBuildRuleView}
   * @param providerInfoCollection providers associated with this build rule
   */
  private static RuleAnalysisLegacyBuildRuleView createRuleAnalysisLegacyBuildRuleView(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder actionGraphBuilder,
      Optional<ActionCreationInput> actionCreationInput,
      ProviderInfoCollection providerInfoCollection) {

    Optional<Action> action =
        actionCreationInput.map(
            input -> {
              Map<ID, ActionAnalysisData> actionAnalysisDataMap =
                  input.registry.getRegistered().entrySet().stream()
                      .collect(
                          ImmutableMap.toImmutableMap(
                              entry -> entry.getKey().getID(), entry -> entry.getValue()));
              ActionWrapperData actionWrapperData =
                  (ActionWrapperData)
                      actionAnalysisDataMap.get(
                          Objects.requireNonNull(input.artifact.asBound().asBuildArtifact())
                              .getActionDataKey()
                              .getID());
              return actionWrapperData.getAction();
            });

    return new RuleAnalysisLegacyBuildRuleView(
        "my_type",
        buildTarget,
        action,
        actionGraphBuilder,
        projectFilesystem,
        providerInfoCollection);
  }

  /** Helper for holding inputs required to create {@link Action} instances for tests. */
  private static class ActionCreationInput {
    private final FakeActionAnalysisRegistry registry;
    private final Artifact artifact;

    private ActionCreationInput(FakeActionAnalysisRegistry registry, Artifact artifact) {
      this.registry = registry;
      this.artifact = artifact;
    }
  }
}
