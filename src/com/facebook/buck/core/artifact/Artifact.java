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

import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.starlark.rule.artifact.SkylarkArtifactApi;

/**
 * An {@link Artifact} is a file used during the build stage. It can either be a source file for the
 * build or a generated file from an action
 *
 * <p>This is the interface exposed to users.
 */
public interface Artifact extends SkylarkArtifactApi, Comparable<Artifact>, AddsToRuleKey {

  /** TODO: we should make the below package protected. */

  /** @return whether the artifact is bound, as described above */
  boolean isBound();

  /** @return a view of this artifact as a {@link BoundArtifact} */
  BoundArtifact asBound();

  /** @return a view of this artifact as a {@link DeclaredArtifact} */
  DeclaredArtifact asDeclared();

  /** @return get this artifact as an {@link OutputArtifact}. Throws if cannot be converted */
  OutputArtifact asOutputArtifact();
}
