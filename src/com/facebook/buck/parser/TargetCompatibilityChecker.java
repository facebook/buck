/*
 * Copyright 2019-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.parser;

import com.facebook.buck.core.description.arg.HasTargetCompatibleWith;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.platform.ConstraintResolver;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.platform.PlatformResolver;
import com.facebook.buck.core.model.platform.impl.ConstraintBasedPlatform;
import java.util.stream.Collectors;

/**
 * Checks whether a list of constraints listed in {@code target_compatible_with} attribute of a
 * target is compatible with a given platform.
 */
class TargetCompatibilityChecker {

  /**
   * @return {@code true} if the given target node argument is compatible with the provided
   *     platform.
   */
  public static boolean targetNodeArgMatchesPlatform(
      ConstraintResolver constraintResolver,
      PlatformResolver platformResolver,
      Object targetNodeArg,
      Platform platform) {
    if (!(targetNodeArg instanceof HasTargetCompatibleWith)) {
      return true;
    }
    HasTargetCompatibleWith argWithTargetCompatible = (HasTargetCompatibleWith) targetNodeArg;

    boolean matchesConstraints =
        platform.matchesAll(
            argWithTargetCompatible.getTargetCompatibleWith().stream()
                .map(BuildTarget::getUnconfiguredBuildTargetView)
                .map(constraintResolver::getConstraintValue)
                .collect(Collectors.toList()));

    if (!matchesConstraints) {
      return false;
    }

    if (argWithTargetCompatible.getTargetCompatiblePlatforms().isEmpty()) {
      return true;
    }

    for (UnconfiguredBuildTargetView compatiblePlatformTarget :
        argWithTargetCompatible.getTargetCompatiblePlatforms()) {
      ConstraintBasedPlatform compatiblePlatform =
          (ConstraintBasedPlatform) platformResolver.getPlatform(compatiblePlatformTarget);

      if (platform.matchesAll(compatiblePlatform.getConstraintValues())) {
        return true;
      }
    }

    return false;
  }
}
