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
package com.facebook.buck.core.rules.actions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.BuildArtifactApi;
import com.facebook.buck.core.artifact.ImmutableSourceArtifact;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.rules.actions.AbstractAction.ActionConstructorParams;
import com.facebook.buck.core.rules.actions.FakeAction.FakeActionConstructorArgs;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataKey;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ActionWrapperDataFactoryTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  private final ProjectFilesystem filesystem = new FakeProjectFilesystem();
  private FakeActionAnalysisRegistry actionAnalysisDataRegistry;

  @Before
  public void setUp() {

    actionAnalysisDataRegistry = new FakeActionAnalysisRegistry();
  }

  @Test
  public void canCreateActionWrapperData() throws ActionCreationException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    ActionWrapperDataFactory actionWrapperDataFactory =
        new ActionWrapperDataFactory(target, actionAnalysisDataRegistry, filesystem);
    ImmutableSet<Artifact> inputs =
        ImmutableSet.of(
            ImmutableSourceArtifact.of(PathSourcePath.of(filesystem, Paths.get("myinput"))));

    Artifact output = actionWrapperDataFactory.declareArtifact(Paths.get("myoutput"));
    ImmutableSet<Artifact> outputs = ImmutableSet.of(output);

    FakeActionConstructorArgs executeFunc =
        (inputs1, outputs1, executionContext) ->
            ImmutableActionExecutionSuccess.of(Optional.empty(), Optional.empty());

    actionWrapperDataFactory.createActionAnalysisData(
        FakeAction.class, inputs, outputs, executeFunc);

    BuildArtifactApi buildArtifact = Objects.requireNonNull(output.asBound().asBuildArtifact());

    ImmutableMap<ActionAnalysisDataKey, ActionAnalysisData> registered =
        actionAnalysisDataRegistry.getRegistered();

    ActionAnalysisData analysisData = registered.get(buildArtifact.getActionDataKey());
    assertNotNull(analysisData);
    assertThat(analysisData, Matchers.instanceOf(ActionWrapperData.class));

    ActionWrapperData data = (ActionWrapperData) analysisData;

    Action action = data.getAction();
    assertThat(action, Matchers.instanceOf(FakeAction.class));

    assertThat(action.getOutputs(), Matchers.hasSize(1));
    assertEquals(
        ExplicitBuildTargetSourcePath.of(
            target, BuildPaths.getGenDir(filesystem, target).resolve("myoutput")),
        output.asBound().getSourcePath());

    assertSame(data.getKey(), buildArtifact.getActionDataKey());

    assertSame(inputs, action.getInputs());

    assertSame(executeFunc, ((FakeAction) action).getExecuteFunction());
  }

  @Test
  public void createThrowsWhenAbstractClass() throws ActionCreationException {
    expectedException.expect(ActionCreationException.class);

    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");

    ActionWrapperDataFactory actionWrapperDataFactory =
        new ActionWrapperDataFactory(target, actionAnalysisDataRegistry, filesystem);

    ImmutableSet<Artifact> inputs = ImmutableSet.of();
    ImmutableSet<Artifact> outputs =
        ImmutableSet.of(actionWrapperDataFactory.declareArtifact(Paths.get("myoutput")));

    actionWrapperDataFactory.createActionAnalysisData(
        AbstractAction.class, inputs, outputs, new ActionConstructorParams() {});
  }

  @Test
  public void createsActionOutputUsingBasePath() throws ActionCreationException {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");

    ActionWrapperDataFactory actionWrapperDataFactory =
        new ActionWrapperDataFactory(target, actionAnalysisDataRegistry, filesystem);

    ImmutableSet<Artifact> inputs = ImmutableSet.of();
    Artifact output = actionWrapperDataFactory.declareArtifact(Paths.get("myoutput"));

    Path expectedBasePath = BuildPaths.getGenDir(filesystem, target);

    FakeActionConstructorArgs executeFunc =
        (inputs1, outputs1, executionContext) ->
            ImmutableActionExecutionSuccess.of(Optional.empty(), Optional.empty());

    actionWrapperDataFactory.createActionAnalysisData(
        FakeAction.class, inputs, ImmutableSet.of(output), executeFunc);

    BuildArtifactApi builtArtifact = Objects.requireNonNull(output.asBound().asBuildArtifact());
    assertEquals(
        ExplicitBuildTargetSourcePath.of(target, expectedBasePath.resolve("myoutput")),
        builtArtifact.getSourcePath());
  }
}
