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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.actions.ActionAnalysisData;
import com.facebook.buck.core.rules.actions.ActionAnalysisData.ID;
import com.facebook.buck.core.rules.actions.ActionAnalysisDataKey;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.rules.actions.ActionExecutionContext;
import com.facebook.buck.core.rules.actions.ActionExecutionResult;
import com.facebook.buck.core.rules.actions.ActionWrapperData;
import com.facebook.buck.core.rules.actions.ActionWrapperDataFactory.DeclaredArtifact;
import com.facebook.buck.core.rules.actions.Artifact;
import com.facebook.buck.core.rules.actions.Artifact.BuildArtifact;
import com.facebook.buck.core.rules.actions.FakeAction;
import com.facebook.buck.core.rules.actions.ImmutableActionExecutionSuccess;
import com.facebook.buck.core.rules.analysis.ImmutableRuleAnalysisKey;
import com.facebook.buck.core.rules.analysis.RuleAnalysisKey;
import com.facebook.buck.core.rules.providers.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.impl.ProviderInfoCollectionImpl;
import com.facebook.buck.util.function.TriFunction;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.nio.file.Paths;
import java.util.Optional;
import javax.annotation.Nullable;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RuleAnalysisContextImplTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void getDepsReturnCorrectDeps() {
    ImmutableMap<RuleAnalysisKey, ProviderInfoCollection> deps = ImmutableMap.of();
    assertSame(deps, new RuleAnalysisContextImpl(deps).deps());

    deps =
        ImmutableMap.of(
            ImmutableRuleAnalysisKey.of(BuildTargetFactory.newInstance("//my:foo")),
            ProviderInfoCollectionImpl.builder().build());
    assertSame(deps, new RuleAnalysisContextImpl(deps).deps());
  }

  @Test
  public void registerActionRegistersToGivenActionRegistry() {
    RuleAnalysisContextImpl context = new RuleAnalysisContextImpl(ImmutableMap.of());

    ActionAnalysisData actionAnalysisData1 =
        new ActionAnalysisData() {
          private final ActionAnalysisDataKey key =
              getNewKey(BuildTargetFactory.newInstance("//my:foo"), new ID() {});

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
          private final ActionAnalysisDataKey key =
              getNewKey(BuildTargetFactory.newInstance("//my:foo"), new ID() {});

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

    RuleAnalysisContextImpl context = new RuleAnalysisContextImpl(ImmutableMap.of());

    ActionAnalysisDataKey key =
        new ActionAnalysisDataKey() {
          private final ID id = new ID() {};

          @Override
          public BuildTarget getBuildTarget() {
            return BuildTargetFactory.newInstance("//my:target");
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
    RuleAnalysisContextImpl context = new RuleAnalysisContextImpl(ImmutableMap.of());

    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    ImmutableSet<Artifact> inputs = ImmutableSet.of();
    ImmutableSet<DeclaredArtifact> outputs =
        ImmutableSet.of(context.actionFactory().declareArtifact(Paths.get("output")));
    TriFunction<
            ImmutableSet<Artifact>,
            ImmutableSet<BuildArtifact>,
            ActionExecutionContext,
            ActionExecutionResult>
        actionFunction =
            (inputs1, outputs1, ctx) ->
                ImmutableActionExecutionSuccess.of(Optional.empty(), Optional.empty());
    ImmutableMap<DeclaredArtifact, BuildArtifact> materializedArtifacts =
        context
            .actionFactory()
            .createActionAnalysisData(FakeAction.class, target, inputs, outputs, actionFunction);

    assertThat(materializedArtifacts.entrySet(), Matchers.hasSize(1));
    @Nullable BuildArtifact artifact = materializedArtifacts.get(Iterables.getOnlyElement(outputs));
    assertNotNull(artifact);

    assertThat(context.getRegisteredActionData().entrySet(), Matchers.hasSize(1));
    @Nullable
    ActionAnalysisData actionAnalysisData =
        context.getRegisteredActionData().get(artifact.getActionDataKey().getID());
    assertNotNull(actionAnalysisData);
    assertThat(actionAnalysisData, Matchers.instanceOf(ActionWrapperData.class));

    ActionWrapperData actionWrapperData = (ActionWrapperData) actionAnalysisData;
    assertSame(target, actionWrapperData.getAction().getOwner());
    assertSame(inputs, actionWrapperData.getAction().getInputs());
    assertEquals(materializedArtifacts.values(), actionWrapperData.getAction().getOutputs());

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
