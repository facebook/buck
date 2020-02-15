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
import java.util.concurrent.ConcurrentHashMap;

/** Deny non-unique targets; use when hashed buck-out disabled. */
class TemporaryUnconfiguredTargetToTargetUniquenessCheckerDeny
    implements TemporaryUnconfiguredTargetToTargetUniquenessChecker {

  private ConcurrentHashMap<UnconfiguredBuildTarget, BuildTarget> targetToUnconfiguredBuildTarget =
      new ConcurrentHashMap<>();

  @Override
  public void addTarget(BuildTarget buildTarget, DependencyStack dependencyStack) {
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
