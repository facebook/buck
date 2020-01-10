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
import com.facebook.buck.core.artifact.BuildArtifact;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataKey;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataRegistry;
import com.google.devtools.build.lib.events.Location;
import java.nio.file.Path;

/**
 * The registry for {@link Action}s, which creates its corresponding {@link ActionWrapperData}.
 *
 * <p>This helps to hide and enforce the relationship between {@link ActionAnalysisDataKey}, {@link
 * ActionAnalysisData}, {@link Action}, and {@link BuildArtifact}.
 *
 * <p>There is one registry per {@link BuildTarget}, such that all {@link Action}s registered by
 * this are considered to be associated with the build target.
 */
public interface ActionRegistry {

  /**
   * @param output the output {@link Path} relative to the package path for the current rule that
   *     the {@link Action}s are being created for
   * @return a {@link Artifact} for the given path
   * @throws ArtifactDeclarationException if the provided output path is invalid
   */
  default Artifact declareArtifact(Path output) throws ArtifactDeclarationException {
    return declareArtifact(output, Location.BUILTIN);
  }

  /**
   * Simple helper behaves like {@link #declareArtifact(Path, Location)}, but validates that the
   * string is a valid path
   *
   * @param output the output path relative to the package path for hte current rule that the {@link
   *     Action}s are being created for
   * @param location if provided, the location within the extension file where this artifact was
   *     declared
   * @return a {@link Artifact} for hte given path
   * @throws ArtifactDeclarationException if the provided output path is invalid
   */
  Artifact declareArtifact(String output, Location location) throws ArtifactDeclarationException;

  /**
   * @param output the output {@link Path} relative to the package path for the current rule that
   *     the {@link Action}s are being created for
   * @param location if provided, the location within the extension file where this artifact was
   *     declared
   * @return a {@link Artifact} for the given path
   * @throws ArtifactDeclarationException if the provided output path is invalid
   */
  Artifact declareArtifact(Path output, Location location) throws ArtifactDeclarationException;

  /**
   * Creates the {@link ActionWrapperData} from its {@link Action} and registers the {@link
   * ActionWrapperData} to the {@link ActionAnalysisDataRegistry}.
   *
   * <p>This will materialize the declared {@link Artifact}s and bind them to the action. These
   * {@link Artifact}s that can be passed via {@link
   * com.google.devtools.build.lib.packages.Provider}s to be consumed.
   *
   * @param action the {@link Action} to create an {@link ActionWrapperData} for and registers it
   * @return the assigned unique ID for this {@link Action}
   */
  String registerActionAnalysisDataForAction(Action action) throws ActionCreationException;

  /**
   * @return the {@link BuildTarget} responsible for all the {@link Action}s registered to this
   *     factory.
   */
  BuildTarget getOwner();

  /**
   * Verifies that all the {@link Artifact}s declared via {@link #declareArtifact(Path)} has been
   * bound with an {@link Action} via {@link #registerActionAnalysisDataForAction(Action)}
   */
  void verifyAllArtifactsBound();
}
