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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.BuildArtifact;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.rules.actions.ActionWrapperData;
import com.facebook.buck.core.rules.actions.FakeAction;
import com.facebook.buck.core.rules.actions.ImmutableActionExecutionSuccess;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData.ID;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataKey;
import com.facebook.buck.core.rules.providers.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.impl.ProviderInfoCollectionImpl;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
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
  public void getDepsReturnCorrectDeps() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    ImmutableMap<BuildTarget, ProviderInfoCollection> deps = ImmutableMap.of();
    assertSame(deps, new RuleAnalysisContextImpl(target, deps, fakeFilesystem, eventBus).deps());

    deps =
        ImmutableMap.of(
            BuildTargetFactory.newInstance("//my:foo"),
            ProviderInfoCollectionImpl.builder().build());
    assertSame(deps, new RuleAnalysisContextImpl(target, deps, fakeFilesystem, eventBus).deps());
  }

  @Test
  public void registerActionRegistersToGivenActionRegistry() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//my:foo");

    RuleAnalysisContextImpl context =
        new RuleAnalysisContextImpl(buildTarget, ImmutableMap.of(), fakeFilesystem, eventBus);

    ActionAnalysisData actionAnalysisData1 =
        new ActionAnalysisData() {
          private final ActionAnalysisDataKey key = getNewKey(buildTarget, new ID() {});

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
          private final ActionAnalysisDataKey key = getNewKey(buildTarget, new ID() {});

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
          private final ID id = new ID() {};

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
  public void createActionViaFactoryInContext() throws ActionCreationException {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");

    RuleAnalysisContextImpl context =
        new RuleAnalysisContextImpl(target, ImmutableMap.of(), fakeFilesystem, eventBus);

    ImmutableSet<Artifact> inputs = ImmutableSet.of();
    ImmutableSet<Artifact> outputs =
        ImmutableSet.of(context.actionRegistry().declareArtifact(Paths.get("output")));
    FakeAction.FakeActionExecuteLambda actionFunction =
        (inputs1, outputs1, ctx) ->
            ImmutableActionExecutionSuccess.of(Optional.empty(), Optional.empty());

    new FakeAction(context.actionRegistry(), inputs, outputs, actionFunction);

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
    assertEquals(outputs, actionWrapperData.getAction().getOutputs());

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
