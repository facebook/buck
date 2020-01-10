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
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Check there's only one {@link BuildTarget} for {@link UnconfiguredBuildTargetView}. We do that
 * this until we use target configuration in the output path.
 */
public class TemporaryUnconfiguredTargetToTargetUniquenessChecker {

  private ConcurrentHashMap<UnconfiguredBuildTargetView, BuildTarget>
      targetToUnconfiguredBuildTarget = new ConcurrentHashMap<>();

  /**
   * Register a target, throw if there's already registered target with the same unconfigured
   * target, same flavors but different configuration.
   */
  public void addTarget(BuildTarget buildTarget, DependencyStack dependencyStack) {
    BuildTarget prev =
        targetToUnconfiguredBuildTarget.putIfAbsent(
            buildTarget.getUnconfiguredBuildTargetView(), buildTarget);
    if (prev != null && !prev.equals(buildTarget)) {
      throw new HumanReadableException(
          dependencyStack,
          "Target %s has more than one configurations (%s and %s) with the same set of flavors %s",
          buildTarget.getUnconfiguredBuildTargetView(),
          buildTarget.getTargetConfiguration(),
          prev.getTargetConfiguration(),
          buildTarget.getUnconfiguredBuildTargetView().getFlavors());
    }
  }
}
