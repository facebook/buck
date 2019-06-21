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

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.actions.FakeAction.FakeActionConstructorArgs;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.function.Supplier;

/** Creates {@link FakeAction}s conveniently for tests */
public class ActionFactoryForTests {
  private final FakeActionAnalysisRegistry actionAnalysisRegistry;
  private final ActionWrapperDataFactory actionWrapperDataFactory;

  public ActionFactoryForTests(BuildTarget buildTarget) {
    this.actionAnalysisRegistry = new FakeActionAnalysisRegistry();
    this.actionWrapperDataFactory =
        new ActionWrapperDataFactory(
            buildTarget, actionAnalysisRegistry, new FakeProjectFilesystem());
  }

  public Artifact declareArtifact(Path path) {
    return actionWrapperDataFactory.declareArtifact(path);
  }

  public Artifact declareArtifact(String path) {
    return declareArtifact(Paths.get(path));
  }

  public FakeAction createFakeAction(
      ImmutableSet<Artifact> inputs,
      ImmutableSet<Artifact> outputs,
      FakeActionConstructorArgs actionFunction)
      throws ActionCreationException {
    return createAction(FakeAction.class, inputs, outputs, actionFunction);
  }

  public FakeAction createFakeAction(
      ImmutableSet<Artifact> inputs,
      ImmutableSet<Artifact> outputs,
      Supplier<ActionExecutionResult> actionFunction)
      throws ActionCreationException {
    return createFakeAction(
        inputs, outputs, (ignored1, ignored2, ignored3) -> actionFunction.get());
  }

  @SuppressWarnings("unchecked")
  public <T extends AbstractAction<U>, U extends AbstractAction.ActionConstructorParams>
      T createAction(
          Class<T> clazz,
          ImmutableSet<Artifact> inputs,
          ImmutableSet<Artifact> outputs,
          U actionArgs)
          throws ActionCreationException {
    try {
      actionWrapperDataFactory.createActionAnalysisData(clazz, inputs, outputs, actionArgs);
      ActionAnalysisData actionAnalysisData =
          Objects.requireNonNull(
              actionAnalysisRegistry
                  .getRegistered()
                  .get(Iterables.getLast(outputs).asBound().asBuildArtifact().getActionDataKey()));

      return (T) ((ActionWrapperData) actionAnalysisData).getAction();

    } finally {
      actionAnalysisRegistry.clear();
    }
  }
}
