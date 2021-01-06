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

package com.facebook.buck.core.rules.actions;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.ArtifactDeclarationException;
import com.facebook.buck.core.artifact.BuildArtifactFactory;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData.ID;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataKey;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataRegistry;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.devtools.build.lib.events.Location;
import java.nio.file.Path;

/** The action registry that should be used throughout Buck to handle registering actions. */
public class DefaultActionRegistry extends BuildArtifactFactory implements ActionRegistry {

  private final ActionAnalysisDataRegistry actionRegistry;

  private final Multiset<String> registeredShortNameIDs;

  /**
   * @param buildTarget the {@link BuildTarget} for which all of the {@link Action}s created are for
   * @param actionRegistry the {@link ActionAnalysisDataRegistry} that all actions created are
   *     registered to
   * @param filesystem the {@link ProjectFilesystem} to use for generating paths
   */
  public DefaultActionRegistry(
      BuildTarget buildTarget,
      ActionAnalysisDataRegistry actionRegistry,
      ProjectFilesystem filesystem) {
    super(buildTarget, filesystem);
    this.actionRegistry = actionRegistry;
    this.registeredShortNameIDs = HashMultiset.create();
  }

  @Override
  public Artifact declareArtifact(String output, Location location)
      throws ArtifactDeclarationException {
    return createDeclaredArtifact(output, location);
  }

  @Override
  public Artifact declareArtifact(Path output, Location location)
      throws ArtifactDeclarationException {
    return createDeclaredArtifact(output, location);
  }

  @Override
  public String registerActionAnalysisDataForAction(Action action) throws ActionCreationException {

    // require all inputs to be bound for now. We could change this.
    for (Artifact input : action.getInputs()) {
      if (!input.isBound()) {
        throw new ActionCreationException(
            action,
            target,
            "Input Artifact %s should be bound to an Action, but is actually not",
            input);
      }
    }

    String normalizedShortName = action.getShortName().toLowerCase().replaceAll("\\s", "_");

    int uniqueCounter = registeredShortNameIDs.add(normalizedShortName, 1);
    String uniqueID = normalizedShortName.concat(String.format("-%s", uniqueCounter));

    ActionAnalysisDataKey key = ActionAnalysisDataKey.of(target, new ID(uniqueID));
    action.getOutputs().forEach(artifact -> bindtoBuildArtifact(key, artifact.getArtifact()));

    ActionWrapperData actionAnalysisData = ImmutableActionWrapperData.of(key, action);
    actionRegistry.registerAction(actionAnalysisData);

    return uniqueID;
  }

  @Override
  public BuildTarget getOwner() {
    return target;
  }

  @Override
  public void verifyAllArtifactsBound() {
    super.verifyAllArtifactsBound();
  }
}
