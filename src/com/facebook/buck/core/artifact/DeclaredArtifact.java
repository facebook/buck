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

package com.facebook.buck.core.artifact;

import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataKey;

/**
 * Represents an {@link Artifact} that is just declared by a rule implemention, with no {@link
 * com.facebook.buck.core.rules.actions.Action}s attached to build it.
 *
 * <p>This is not intended to be used by users, but only by the build engine.
 */
interface DeclaredArtifact extends Artifact {

  /**
   * Binds an corresponding {@link com.facebook.buck.core.rules.actions.Action} as represented by
   * the {@link ActionAnalysisDataKey} to this {@link Artifact}.
   *
   * @param key the {@link ActionAnalysisDataKey} to attach to this {@link Artifact}
   * @return the {@link BuildArtifact} of this instance after attaching the {@link
   *     ActionAnalysisDataKey}.
   */
  BuildArtifact materialize(ActionAnalysisDataKey key);

  /** Intended for framework use only, comparing order for {@link DeclaredArtifact} */
  int compareDeclared(DeclaredArtifact artifact);
}
