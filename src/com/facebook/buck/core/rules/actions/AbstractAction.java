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
import com.facebook.buck.core.artifact.BoundArtifact;
import com.facebook.buck.core.artifact.BuildArtifact;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.actions.AbstractAction.ActionConstructorParams;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.util.Objects;

/**
 * Base implementation of an {@link Action}
 *
 * @param <T> the constructor parameters provided for creating the {@link Action}
 */
public abstract class AbstractAction<T extends ActionConstructorParams> implements Action {

  protected final BuildTarget owner;
  protected final ImmutableSet<Artifact> inputs;
  protected final ImmutableSet<Artifact> outputs;
  protected final T params;

  /**
   * @param owner the {@link BuildTarget} that resulted in the creation of this {@link Action}
   * @param inputs the input {@link Artifact} for this {@link Action}. They can be either outputs of
   *     other {@link Action}s or be source files
   * @param outputs the outputs for this {@link Action}
   * @param params the {@link ActionConstructorParams} for this action. This is here mainly to
   *     enforce the type signature of the implementations.
   */
  protected AbstractAction(
      BuildTarget owner, ImmutableSet<Artifact> inputs, ImmutableSet<Artifact> outputs, T params) {
    this.owner = owner;
    this.inputs = inputs;
    this.outputs = outputs;
    this.params = params;
  }

  @Override
  public final BuildTarget getOwner() {
    return owner;
  }

  @Override
  public final ImmutableSet<Artifact> getInputs() {
    return inputs;
  }

  @Override
  public final ImmutableSet<Artifact> getOutputs() {
    return outputs;
  }

  @Override
  public ImmutableSet<SourcePath> getSourcePathOutputs() {
    return ImmutableSet.copyOf(
        Iterables.transform(getOutputs(), artifact -> artifact.asBound().getSourcePath()));
  }

  @Override
  public BuildTarget getBuildTarget() {
    return getOwner();
  }

  @Override
  public ImmutableSet<BuildTarget> getDependencies() {
    return getInputs().stream()
        .map(Artifact::asBound)
        .map(BoundArtifact::asBuildArtifact)
        .filter(Objects::nonNull)
        .map(BuildArtifact::getSourcePath)
        .map(ExplicitBuildTargetSourcePath::getTarget)
        .collect(ImmutableSet.toImmutableSet());
  }

  /**
   * The additional constructor parameters that are passed to the constructor of the {@link Action}
   * being created
   */
  public interface ActionConstructorParams {}
}
