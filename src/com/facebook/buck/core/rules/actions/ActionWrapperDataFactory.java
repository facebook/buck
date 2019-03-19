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
import com.facebook.buck.core.rules.actions.ActionAnalysisData.ID;
import com.facebook.buck.core.rules.actions.Artifact.BuildArtifact;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import java.nio.file.Path;
import org.immutables.value.Value;
import org.immutables.value.Value.Style.ImplementationVisibility;

/**
 * The factory for creating {@link ActionWrapperData}.
 *
 * <p>This helps to hide and enforce the relationship between {@link ActionAnalysisDataKey}, {@link
 * ActionAnalysisData}, {@link Action}, and {@link BuildArtifact}
 */
public class ActionWrapperDataFactory {

  private final ActionAnalysisDataRegistry actionRegistry;

  public ActionWrapperDataFactory(ActionAnalysisDataRegistry actionRegistry) {
    this.actionRegistry = actionRegistry;
  }

  /** @return a {@link DeclaredArtifact} for the given path */
  public static DeclaredArtifact declareArtifact(Path output) {
    return ImmutableDeclaredArtifact.of(output);
  }

  /**
   * The {@link DeclaredArtifact} is the promise during the rule implementation that an {@link
   * Action} will be created to generate an output at the corresponding path.
   *
   * <p>This is not an {@link Artifact} in itself, and cannot be used as such for any {@link Action}
   * lookup, since it is not fully materialized with a corresponding {@link Action}.
   *
   * <p>This {@link DeclaredArtifact} becomes materialized once {@link
   * #createActionAnalysisData(Class, BuildTarget, ImmutableSet, ImmutableSet, Object...)} has been
   * called with this as an output of an {@link Action}.
   */
  @Value.Immutable(builder = false, copy = false)
  @Value.Style(visibility = ImplementationVisibility.PACKAGE)
  public abstract static class DeclaredArtifact {
    @Value.Parameter
    public abstract Path getOutputPath();

    private BuildArtifact materialize(ActionAnalysisDataKey key) {
      return ImmutableBuildArtifact.of(
          key, ExplicitBuildTargetSourcePath.of(key.getBuildTarget(), getOutputPath()));
    }
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
   * @param target the built target
   * @param inputs the inputs to the {@link Action}
   * @param outputs the declared outputs of the {@link Action}
   * @param args the arguments for construction the {@link Action}
   * @param <T> the concrete type of the {@link Action}
   * @return a map of the given {@link DeclaredArtifact} to the corresponding {@link BuildArtifact}
   *     to propagate to other rules
   */
  @SuppressWarnings("unchecked")
  public <T extends AbstractAction>
      ImmutableMap<DeclaredArtifact, BuildArtifact> createActionAnalysisData(
          Class<T> actionClazz,
          BuildTarget target,
          ImmutableSet<Artifact> inputs,
          ImmutableSet<DeclaredArtifact> outputs,
          Object... args)
          throws ActionCreationException {
    ActionAnalysisDataKey key = ImmutableActionAnalysisDataKey.of(target, new ID() {});
    ImmutableMap<DeclaredArtifact, BuildArtifact> materializedOutputsMap =
        ImmutableMap.copyOf(Maps.toMap(outputs, declared -> declared.materialize(key)));
    Object[] fullArgs = new Object[args.length + 3];
    fullArgs[0] = target;
    fullArgs[1] = inputs;
    fullArgs[2] = materializedOutputsMap.values();
    System.arraycopy(args, 0, fullArgs, 3, args.length);
    try {
      // TODO(bobyf): we can probably do some stuff here with annotation processing to generate
      // typed creation
      T action = (T) actionClazz.getConstructors()[0].newInstance(fullArgs);
      ActionWrapperData actionAnalysisData = ImmutableActionWrapperData.of(key, action);
      actionRegistry.registerAction(actionAnalysisData);

      return materializedOutputsMap;
    } catch (Exception e) {
      throw new ActionCreationException(e, actionClazz, fullArgs);
    }
  }
}
