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
import com.facebook.buck.core.artifact.BuildArtifact;
import com.facebook.buck.core.artifact.DeclaredArtifact;
import com.facebook.buck.core.artifact.ImmutableDeclaredArtifact;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.rules.actions.AbstractAction.ActionConstructorParams;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData.ID;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataKey;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataRegistry;
import com.facebook.buck.core.rules.analysis.action.ImmutableActionAnalysisDataKey;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import java.nio.file.Path;

/**
 * The factory for creating {@link ActionWrapperData}.
 *
 * <p>This helps to hide and enforce the relationship between {@link ActionAnalysisDataKey}, {@link
 * ActionAnalysisData}, {@link Action}, and {@link BuildArtifact}.
 *
 * <p>There is one factory per {@link BuildTarget}, such that all {@link Action}s created by this
 * factory are considered to be associated with the build target.
 */
public class ActionWrapperDataFactory {

  private final BuildTarget buildTarget;
  private final ActionAnalysisDataRegistry actionRegistry;
  private final Path packagePath;

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
    this.buildTarget = buildTarget;
    this.actionRegistry = actionRegistry;
    this.packagePath = BuildPaths.getGenDir(filesystem, buildTarget);
  }

  /**
   * @param output the output {@link Path} relative to the package path for the current rule that
   *     the {@link Action}s are being created for
   * @return a {@link DeclaredArtifact} for the given path
   */
  public DeclaredArtifact declareArtifact(Path output) {
    Preconditions.checkState(!output.isAbsolute());
    return ImmutableDeclaredArtifact.of(packagePath, output);
  }

  /**
   * Creates the {@link ActionWrapperData} and its related {@link Action} from a set of {@link
   * DeclaredArtifact}s. The created {@link ActionWrapperData} is registered to the {@link
   * ActionAnalysisDataRegistry}.
   *
   * <p>This will materialize the declared {@link DeclaredArtifact}s into {@link BuildArtifact}s
   * that can be passed via {@link com.google.devtools.build.lib.packages.Provider}s to be consumed.
   *
   * @param actionClazz
   * @param inputs the inputs to the {@link Action}
   * @param outputs the declared outputs of the {@link Action}
   * @param args the arguments for construction the {@link Action}
   * @param <T> the concrete type of the {@link Action}
   * @return a map of the given {@link DeclaredArtifact} to the corresponding {@link BuildArtifact}
   *     to propagate to other rules
   */
  @SuppressWarnings("unchecked")
  public <T extends AbstractAction<U>, U extends ActionConstructorParams>
      ImmutableMap<DeclaredArtifact, BuildArtifact> createActionAnalysisData(
          Class<T> actionClazz,
          ImmutableSet<Artifact> inputs,
          ImmutableSet<DeclaredArtifact> outputs,
          U args)
          throws ActionCreationException {
    ActionAnalysisDataKey key = ImmutableActionAnalysisDataKey.of(buildTarget, new ID() {});
    ImmutableMap<DeclaredArtifact, BuildArtifact> materializedOutputsMap =
        ImmutableMap.copyOf(Maps.toMap(outputs, declared -> declared.materialize(key)));
    try {
      // TODO(bobyf): we can probably do some stuff here with annotation processing to generate
      // typed creation instead of reflection
      T action =
          (T)
              actionClazz.getConstructors()[0].newInstance(
                  buildTarget, inputs, materializedOutputsMap.values(), args);
      ActionWrapperData actionAnalysisData = ImmutableActionWrapperData.of(key, action);
      actionRegistry.registerAction(actionAnalysisData);

      return materializedOutputsMap;
    } catch (Exception e) {
      throw new ActionCreationException(e, actionClazz, buildTarget, inputs, outputs, args);
    }
  }
}
