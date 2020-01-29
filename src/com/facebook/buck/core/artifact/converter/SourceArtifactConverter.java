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
import com.facebook.buck.core.artifact.SourceArtifactImpl;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.Set;

/**
 * Utility class to resolve specified {@link com.facebook.buck.core.sourcepath.SourcePath} into
 * {@link Artifact}s.
 */
public class SourceArtifactConverter {

  private SourceArtifactConverter() {}

  /**
   * @param src the object representing the sources of a rule attribute
   * @param deps the {@link ProviderInfoCollection} from the dependencies of a rule
   * @return the {@link Artifact}s representing the sources.
   */
  public static Artifact getArtifactsFromSrc(
      SourcePath src, ImmutableMap<BuildTarget, ProviderInfoCollection> deps) {
    if (src instanceof BuildTargetSourcePath) {
      BuildTargetWithOutputs targetWithOutputs =
          ((BuildTargetSourcePath) src).getTargetWithOutputs();
      ProviderInfoCollection providerInfos = deps.get(targetWithOutputs.getBuildTarget());
      if (providerInfos == null) {
        throw new IllegalStateException(String.format("Deps %s did not contain %s", deps, src));
      }
      Set<Artifact> artifacts =
          DefaultInfoArtifactsRetriever.getArtifacts(
              providerInfos.getDefaultInfo(), targetWithOutputs);
      if (artifacts.size() != 1) {
        throw new IllegalStateException(
            String.format(
                "%s must have exactly one output, but had %s outputs", src, artifacts.size()));
      }
      return Iterables.getOnlyElement(artifacts);
    } else if (src instanceof PathSourcePath) {
      return SourceArtifactImpl.of((PathSourcePath) src);
    } else {
      throw new IllegalStateException(
          String.format("%s must either be a source file, or a BuildTarget", src));
    }
  }

  /**
   * @param srcs the set of the sources of a rule attribute
   * @param deps the {@link ProviderInfoCollection} from the dependencies of a rule
   * @return the {@link Artifact}s representing the sources.
   */
  public static ImmutableSortedSet<Artifact> getArtifactsFromSrcs(
      Iterable<SourcePath> srcs, ImmutableMap<BuildTarget, ProviderInfoCollection> deps) {
    ImmutableSortedSet.Builder<Artifact> artifacts = ImmutableSortedSet.naturalOrder();
    for (SourcePath src : srcs) {
      artifacts.add(SourceArtifactConverter.getArtifactsFromSrc(src, deps));
    }
    return artifacts.build();
  }
}
