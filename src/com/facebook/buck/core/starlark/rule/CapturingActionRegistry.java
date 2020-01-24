/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.starlark.rule;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.ArtifactDeclarationException;
import com.facebook.buck.core.artifact.OutputArtifact;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.actions.Action;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.rules.actions.ActionRegistry;
import com.facebook.buck.core.rules.providers.lib.DefaultInfo;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.events.Location;
import java.nio.file.Path;

/**
 * Simple wrapper for {@link ActionRegistry} that records all outputs and then forwards calls to the
 * delegate {@link ActionRegistry}. This allows us to construct e.g. a reasonable {@link
 * DefaultInfo} based on registered actions without having to explicitly maintain that list.
 */
class CapturingActionRegistry implements ActionRegistry {
  private final ActionRegistry delegate;
  private ImmutableSet.Builder<Artifact> outputs = ImmutableSet.builder();

  /**
   * Creates an instance of {@link CapturingActionRegistry}
   *
   * @param delegate The actual registry to call for each of the methods in {@link ActionRegistry}
   */
  public CapturingActionRegistry(ActionRegistry delegate) {
    this.delegate = delegate;
  }

  @Override
  public Artifact declareArtifact(String output, Location location)
      throws ArtifactDeclarationException {
    return delegate.declareArtifact(output, location);
  }

  @Override
  public Artifact declareArtifact(Path output, Location location)
      throws ArtifactDeclarationException {
    return delegate.declareArtifact(output, location);
  }

  @Override
  public String registerActionAnalysisDataForAction(Action action) throws ActionCreationException {
    action.getOutputs().stream().map(OutputArtifact::getArtifact).forEach(outputs::add);
    return delegate.registerActionAnalysisDataForAction(action);
  }

  @Override
  public BuildTarget getOwner() {
    return delegate.getOwner();
  }

  @Override
  public void verifyAllArtifactsBound() {
    delegate.verifyAllArtifactsBound();
  }

  public ImmutableSet<Artifact> getOutputs() {
    return outputs.build();
  }
}
