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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.util.Optional;

/**
 * Unconfigured target graph version of {@link com.facebook.buck.rules.coercer.NeededCoverageSpec}.
 */
@BuckStyleValue
public abstract class UnconfiguredNeededCoverageSpec {

  /**
   * Gets the coverage ratio that is required for a test
   *
   * @return The coverage ratio required for the build target, represented in a percentage integer
   *     (e.g., return value 42 means 42% coverage ratio)
   */
  public abstract int getNeededCoverageRatioPercentage();

  public abstract UnconfiguredBuildTarget getBuildTarget();

  public abstract Optional<String> getPathName();

  public static UnconfiguredNeededCoverageSpec of(
      int neededCoverageRatioPercentage,
      UnconfiguredBuildTarget buildTarget,
      Optional<String> pathName) {
    return ImmutableUnconfiguredNeededCoverageSpec.ofImpl(
        neededCoverageRatioPercentage, buildTarget, pathName);
  }
}
