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

package com.facebook.buck.core.artifact.converter;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rules.providers.lib.DefaultInfo;
import com.google.common.base.Preconditions;
import java.util.Set;

/**
 * Utility class for getting artifacts from {@link
 * com.facebook.buck.core.rules.providers.lib.DefaultInfo}.
 */
public class DefaultInfoArtifactsRetriever {
  private DefaultInfoArtifactsRetriever() {}

  /**
   * Returns the {@link Artifact} instances associated with the given {@link BuildTarget} and {@link
   * OutputLabel}.
   */
  public static Set<Artifact> getArtifacts(
      DefaultInfo defaultInfo, BuildTargetWithOutputs buildTargetWithOutputs) {
    OutputLabel outputLabel = buildTargetWithOutputs.getOutputLabel();
    if (outputLabel.isDefault()) {
      return defaultInfo.defaultOutputs();
    }
    Set<Artifact> namedArtifacts =
        defaultInfo.namedOutputs().get(OutputLabel.internals().getLabel(outputLabel));
    Preconditions.checkNotNull(
        namedArtifacts,
        "Cannot find output label [%s] for target %s",
        outputLabel,
        buildTargetWithOutputs.getBuildTarget());
    return namedArtifacts;
  }
}
