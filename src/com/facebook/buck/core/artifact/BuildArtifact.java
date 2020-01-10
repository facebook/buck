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

import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataKey;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import java.nio.file.Path;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/**
 * Represents an {@link Artifact} that is materialized by an {@link
 * com.facebook.buck.core.rules.actions.Action}.
 *
 * <p>This is not intended to be exposed to users, but used only by the build engine.
 */
public interface BuildArtifact extends Artifact {

  /**
   * @return the key to the {@link ActionAnalysisData} that owns this artifact. This is null if this
   *     is a legacy artifact (one that refers to a source path of an old BuildRule.
   */
  @Value.Parameter
  @Nullable
  ActionAnalysisDataKey getActionDataKey();

  /** @return the path to the artifact */
  ExplicitBuildTargetSourcePath getSourcePath();

  /** @return the path the user intends to write to relative to the assigned package path. */
  Path getOutputPath();
}
