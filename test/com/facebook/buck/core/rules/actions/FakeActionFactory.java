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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.actions.ActionWrapperDataFactory.DeclaredArtifact;
import com.facebook.buck.core.rules.actions.Artifact.BuildArtifact;
import com.facebook.buck.util.function.TriFunction;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.function.Supplier;

/** Creates {@link FakeAction}s conveniently for tests */
public class FakeActionFactory {
  private final FakeActionAnalysisRegistry actionAnalysisRegistry;
  private final ActionWrapperDataFactory actionWrapperDataFactory;

  public FakeActionFactory() {
    this.actionAnalysisRegistry = new FakeActionAnalysisRegistry();
    this.actionWrapperDataFactory = new ActionWrapperDataFactory(actionAnalysisRegistry);
  }

  public DeclaredArtifact declareArtifact(Path path) {
    return actionWrapperDataFactory.declareArtifact(path);
  }

  public DeclaredArtifact declareArtifact(String path) {
    return declareArtifact(Paths.get(path));
  }

  public FakeAction createFakeAction(
      BuildTarget target,
      ImmutableSet<Artifact> inputs,
      ImmutableSet<DeclaredArtifact> outputs,
      TriFunction<
              ImmutableSet<Artifact>,
              ImmutableSet<BuildArtifact>,
              ActionExecutionContext,
              ActionExecutionResult>
          actionFunction)
      throws ActionCreationException {
    try {
      ImmutableMap<DeclaredArtifact, BuildArtifact> materializedArtifactMap =
          actionWrapperDataFactory.createActionAnalysisData(
              FakeAction.class, target, inputs, outputs, actionFunction);
      BuildArtifact artifact =
          Objects.requireNonNull(
              Iterables.getFirst(materializedArtifactMap.entrySet(), null).getValue());
      ActionAnalysisData actionAnalysisData =
          Objects.requireNonNull(
              actionAnalysisRegistry.getRegistered().get(artifact.getActionDataKey()));

      return (FakeAction) ((ActionWrapperData) actionAnalysisData).getAction();

    } finally {
      actionAnalysisRegistry.clear();
    }
  }

  public FakeAction createFakeAction(
      BuildTarget target,
      ImmutableSet<Artifact> inputs,
      ImmutableSet<DeclaredArtifact> outputs,
      Supplier<ActionExecutionResult> actionFunction)
      throws ActionCreationException {
    return createFakeAction(
        target, inputs, outputs, (ignored1, ignored2, ignored3) -> actionFunction.get());
  }
}
