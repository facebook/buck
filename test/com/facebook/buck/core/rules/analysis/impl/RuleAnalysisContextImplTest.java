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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.BuildArtifact;
import com.facebook.buck.core.artifact.BuildArtifactFactoryForTests;
import com.facebook.buck.core.artifact.OutputArtifact;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.rules.actions.ActionExecutionResult;
import com.facebook.buck.core.rules.actions.ActionWrapperData;
import com.facebook.buck.core.rules.actions.FakeAction;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData.ID;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataKey;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.TestProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.providers.lib.ImmutableDefaultInfo;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RuleAnalysisContextImplTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();
  private final ProjectFilesystem fakeFilesystem = new FakeProjectFilesystem();
  private final BuckEventBus eventBus = BuckEventBusForTests.newInstance();

  @Test
  public void resolveDepsReturnCorrectDeps() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    ImmutableMap<BuildTarget, ProviderInfoCollection> deps =
        ImmutableMap.of(
            BuildTargetFactory.newInstance("//my:foo"),
            TestProviderInfoCollectionImpl.builder().build());
    assertEquals(
        deps,
        new RuleAnalysisContextImpl(target, deps, fakeFilesystem, eventBus)
            .resolveDeps(ImmutableSet.of(target)));
  }

  @Test
  public void getSrcsReturnCorrectSrcs() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    BuildArtifactFactoryForTests artifactFactory =
        new BuildArtifactFactoryForTests(target, fakeFilesystem);
    BuildArtifact output =
        artifactFactory.createBuildArtifact(Paths.get("output"), Location.BUILTIN);
    ImmutableMap<BuildTarget, ProviderInfoCollection> deps =
        ImmutableMap.of(
            BuildTargetFactory.newInstance("//my:foo"),
            TestProviderInfoCollectionImpl.builder()
                .build(new ImmutableDefaultInfo(SkylarkDict.empty(), ImmutableSet.of(output))));

    assertEquals(
        ImmutableSortedSet.of(output),
        new RuleAnalysisContextImpl(target, deps, fakeFilesystem, eventBus)
            .resolveSrcs(
                ImmutableSet.of(
                    DefaultBuildTargetSourcePath.of(
                        BuildTargetWithOutputs.of(target, OutputLabel.defaultLabel())))));
  }

  @Test
  public void registerActionRegistersToGivenActionRegistry() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//my:foo");

    RuleAnalysisContextImpl context =
        new RuleAnalysisContextImpl(buildTarget, ImmutableMap.of(), fakeFilesystem, eventBus);

    ActionAnalysisData actionAnalysisData1 =
        new ActionAnalysisData() {
          private final ActionAnalysisDataKey key = getNewKey(buildTarget, new ID("a"));

          @Override
          public ActionAnalysisDataKey getKey() {
            return key;
          }
        };

    context.registerAction(actionAnalysisData1);

    assertSame(
        actionAnalysisData1,
        context.getRegisteredActionData().get(actionAnalysisData1.getKey().getID()));

    ActionAnalysisData actionAnalysisData2 =
        new ActionAnalysisData() {
          private final ActionAnalysisDataKey key = getNewKey(buildTarget, new ID("a"));

          @Override
          public ActionAnalysisDataKey getKey() {
            return key;
          }
        };

    context.registerAction(actionAnalysisData2);

    assertSame(
        actionAnalysisData2,
        context.getRegisteredActionData().get(actionAnalysisData2.getKey().getID()));
    assertSame(
        actionAnalysisData1,
        context.getRegisteredActionData().get(actionAnalysisData1.getKey().getID()));
  }

  @Test
  public void registerConflictingActionsThrows() {
    expectedException.expect(VerifyException.class);

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//my:target");

    RuleAnalysisContextImpl context =
        new RuleAnalysisContextImpl(buildTarget, ImmutableMap.of(), fakeFilesystem, eventBus);

    ActionAnalysisDataKey key =
        new ActionAnalysisDataKey() {
          private final ID id = new ID("a");

          @Override
          public BuildTarget getBuildTarget() {
            return buildTarget;
          }

          @Override
          public ID getID() {
            return id;
          }
        };

    ActionAnalysisData actionAnalysisData1 = () -> key;

    context.registerAction(actionAnalysisData1);

    ActionAnalysisData actionAnalysisData2 = () -> key;

    context.registerAction(actionAnalysisData2);
  }

  @Test
  public void throwsWhenGettingActionDataOnNonFinalizedFactory() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//my:target");

    RuleAnalysisContextImpl context =
        new RuleAnalysisContextImpl(buildTarget, ImmutableMap.of(), fakeFilesystem, eventBus);

    context.actionRegistry().declareArtifact(Paths.get("some/path"));

    expectedException.expect(HumanReadableException.class);
    context.getRegisteredActionData();
  }

  @Test
  public void createActionViaFactoryInContext() throws ActionCreationException {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");

    RuleAnalysisContextImpl context =
        new RuleAnalysisContextImpl(target, ImmutableMap.of(), fakeFilesystem, eventBus);

    ImmutableSortedSet<Artifact> inputs = ImmutableSortedSet.of();
    ImmutableSortedSet<Artifact> outputs =
        ImmutableSortedSet.of(context.actionRegistry().declareArtifact(Paths.get("output")));
    FakeAction.FakeActionExecuteLambda actionFunction =
        (srcs, inputs1, outputs1, ctx) ->
            ActionExecutionResult.success(Optional.empty(), Optional.empty(), ImmutableList.of());

    new FakeAction(
        context.actionRegistry(), ImmutableSortedSet.of(), inputs, outputs, actionFunction);

    BuildArtifact artifact =
        Objects.requireNonNull(Iterables.getOnlyElement(outputs).asBound().asBuildArtifact());

    assertThat(context.getRegisteredActionData().entrySet(), Matchers.hasSize(1));
    @Nullable
    ActionAnalysisData actionAnalysisData =
        context.getRegisteredActionData().get(artifact.getActionDataKey().getID());
    assertNotNull(actionAnalysisData);
    assertThat(actionAnalysisData, Matchers.instanceOf(ActionWrapperData.class));

    ActionWrapperData actionWrapperData = (ActionWrapperData) actionAnalysisData;
    assertSame(target, actionWrapperData.getAction().getOwner());
    assertSame(inputs, actionWrapperData.getAction().getInputs());
    assertEquals(
        outputs,
        actionWrapperData.getAction().getOutputs().stream()
            .map(OutputArtifact::getArtifact)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())));

    assertSame(actionFunction, ((FakeAction) actionWrapperData.getAction()).getExecuteFunction());
  }

  private static ActionAnalysisDataKey getNewKey(BuildTarget target, ActionAnalysisData.ID id) {
    return new ActionAnalysisDataKey() {
      @Override
      public BuildTarget getBuildTarget() {
        return target;
      }

      @Override
      public ID getID() {
        return id;
      }
    };
  }
}
