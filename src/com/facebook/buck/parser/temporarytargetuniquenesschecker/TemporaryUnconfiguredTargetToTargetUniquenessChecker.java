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

package com.facebook.buck.parser.temporarytargetuniquenesschecker;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Check there's only one {@link BuildTarget} for {@link
 * com.facebook.buck.core.model.UnconfiguredBuildTarget}. We do that this until we use target
 * configuration in the output path.
 */
public class TemporaryUnconfiguredTargetToTargetUniquenessChecker {

  private final ConcurrentHashMap<UnconfiguredBuildTarget, BuildTarget>
      targetToUnconfiguredBuildTarget = new ConcurrentHashMap<>();

  /**
   * Register a target, check if there's already registered target with the same unconfigured
   * target, same flavors but different configuration.
   */
  public void addTarget(TargetNode<?> node, DependencyStack dependencyStack) {
    // If a node uses hashed buck-out paths, no need to check for uniqueness of the unconfigured
    // target.
    if (node.getFilesystem()
        .getBuckPaths()
        .shouldIncludeTargetConfigHash(node.getBuildTarget().getCellRelativeBasePath())) {
      return;
    }

    BuildTarget buildTarget = node.getBuildTarget();
    BuildTarget prev =
        targetToUnconfiguredBuildTarget.putIfAbsent(
            buildTarget.getUnconfiguredBuildTarget(), buildTarget);
    if (prev != null && !prev.equals(buildTarget)) {
      throw new HumanReadableException(
          dependencyStack,
          "Target %s has more than one configurations (%s and %s) with the same set of flavors %s",
          buildTarget.getUnconfiguredBuildTarget(),
          buildTarget.getTargetConfiguration(),
          prev.getTargetConfiguration(),
          buildTarget.getUnconfiguredBuildTarget().getFlavors().getSet());
    }
  }
}
