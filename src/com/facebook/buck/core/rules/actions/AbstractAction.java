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
import com.facebook.buck.core.rules.actions.Artifact.BuildArtifact;
import com.google.common.collect.ImmutableSet;

public abstract class AbstractAction implements Action {

  protected final BuildTarget owner;
  protected final ImmutableSet<Artifact> inputs;
  protected final ImmutableSet<BuildArtifact> outputs;

  protected AbstractAction(
      BuildTarget owner, ImmutableSet<Artifact> inputs, ImmutableSet<BuildArtifact> outputs) {
    this.owner = owner;
    this.inputs = inputs;
    this.outputs = outputs;
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
  public final ImmutableSet<BuildArtifact> getOutputs() {
    return outputs;
  }
}
