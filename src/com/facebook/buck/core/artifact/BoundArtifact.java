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

import com.facebook.buck.core.sourcepath.SourcePath;
import javax.annotation.Nullable;

/**
 * Represents an {@link Artifact} that will be materialized by the action execution phase. This can
 * either be a source file or a file produced by an action.
 *
 * <p>A bound artifact is only either an {@link SourceArtifact} or {@link BuildArtifact}
 *
 * <p>This interface is not intended to be used by users, but only by the framework.
 */
public interface BoundArtifact extends Artifact {

  /**
   * @return this artifact as a {@link SourceArtifact}, null if this is not a {@link SourceArtifact}
   */
  @Nullable
  SourceArtifact asSource();

  /**
   * @return this artifact as a {@link BuildArtifact}, null if this is not a {@link BuildArtifact}
   */
  @Nullable
  BuildArtifact asBuildArtifact();

  /** @return the {@link SourcePath} that represents where this artifact is. */
  SourcePath getSourcePath();
}
