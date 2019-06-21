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
import com.facebook.buck.core.artifact.BuildArtifactFactory;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.actions.AbstractAction.ActionConstructorParams;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData.ID;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataKey;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataRegistry;
import com.facebook.buck.core.rules.analysis.action.ImmutableActionAnalysisDataKey;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.lang.reflect.Constructor;
import java.nio.file.Path;

/**
 * The factory for creating {@link ActionWrapperData}.
 *
 * <p>This helps to hide and enforce the relationship between {@link ActionAnalysisDataKey}, {@link
 * ActionAnalysisData}, {@link Action}, and {@link
 * com.facebook.buck.core.artifact.BuildArtifactApi}.
 *
 * <p>There is one factory per {@link BuildTarget}, such that all {@link Action}s created by this
 * factory are considered to be associated with the build target.
 */
public class ActionWrapperDataFactory extends BuildArtifactFactory {

  private final ActionAnalysisDataRegistry actionRegistry;

  /**
   * @param buildTarget the {@link BuildTarget} for which all of the {@link Action}s created are for
   * @param actionRegistry the {@link ActionAnalysisDataRegistry} that all actions created are
   *     registered to
   * @param filesystem the {@link ProjectFilesystem} to use for generating paths
   */
  public ActionWrapperDataFactory(
      BuildTarget buildTarget,
      ActionAnalysisDataRegistry actionRegistry,
      ProjectFilesystem filesystem) {
    super(buildTarget, filesystem);
    this.actionRegistry = actionRegistry;
  }

  /**
   * @param output the output {@link Path} relative to the package path for the current rule that
   *     the {@link Action}s are being created for
   * @return a {@link Artifact} for the given path
   */
  public Artifact declareArtifact(Path output) {
    return createDeclaredArtifact(output);
  }

  /**
   * Creates the {@link ActionWrapperData} and its related {@link Action} from a set of {@link
   * Artifact}s. The created {@link ActionWrapperData} is registered to the {@link
   * ActionAnalysisDataRegistry}.
   *
   * <p>This will materialize the declared {@link Artifact}s and bind them to the action. These
   * {@link Artifact}s that can be passed via {@link
   * com.google.devtools.build.lib.packages.Provider}s to be consumed.
   *
   * @param <T> the concrete type of the {@link Action}
   * @param actionClazz
   * @param inputs the inputs to the {@link Action}
   * @param outputs the declared outputs of the {@link Action}
   * @param args the arguments for construction the {@link Action}
   */
  @SuppressWarnings("unchecked")
  public <T extends AbstractAction<U>, U extends ActionConstructorParams>
      void createActionAnalysisData(
          Class<T> actionClazz,
          ImmutableSet<Artifact> inputs,
          ImmutableSet<Artifact> outputs,
          U args)
          throws ActionCreationException {

    // require all inputs to be bound for now. We could change this.
    for (Artifact input : inputs) {
      if (!input.isBound()) {
        throw new ActionCreationException(
            actionClazz,
            target,
            inputs,
            outputs,
            args,
            "Input Artifact %s should be bound to an Action, but is actually not",
            input);
      }
    }

    ActionAnalysisDataKey key = ImmutableActionAnalysisDataKey.of(target, new ID() {});
    ImmutableSet<Artifact> materializedOutputs =
        ImmutableSet.copyOf(
            Iterables.transform(outputs, artifact -> bindtoBuildArtifact(key, artifact)));

    try {
      // TODO(bobyf): we can probably do some stuff here with annotation processing to generate
      // typed creation instead of reflection
      Constructor<?>[] constructors = actionClazz.getConstructors();
      Preconditions.checkArgument(
          constructors.length == 1,
          "Action classes must be public, and have exactly one public constructor");
      T action = (T) constructors[0].newInstance(target, inputs, materializedOutputs, args);
      ActionWrapperData actionAnalysisData = ImmutableActionWrapperData.of(key, action);
      actionRegistry.registerAction(actionAnalysisData);
    } catch (Exception e) {
      throw new ActionCreationException(e, actionClazz, target, inputs, outputs, args);
    }
  }
}
