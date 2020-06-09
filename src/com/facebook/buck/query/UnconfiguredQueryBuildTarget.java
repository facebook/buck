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

package com.facebook.buck.query;

import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import javax.annotation.Nullable;

/**
 * Implementation of {@link UnconfiguredQueryTarget} that wraps a {@link UnconfiguredBuildTarget}.
 */
public class UnconfiguredQueryBuildTarget implements UnconfiguredQueryTarget {

  private final UnconfiguredBuildTarget buildTarget;

  private UnconfiguredQueryBuildTarget(UnconfiguredBuildTarget buildTarget) {
    this.buildTarget = buildTarget;
  }

  /**
   * Construct a new immutable {@code UnconfiguredQueryBuildTarget} instance.
   *
   * @param buildTarget The value for the {@code buildTarget} attribute
   * @return An immutable UnconfiguredQueryBuildTarget instance
   */
  public static UnconfiguredQueryBuildTarget of(UnconfiguredBuildTarget buildTarget) {
    return new UnconfiguredQueryBuildTarget(buildTarget);
  }

  /** See {@code of(UnconfiguredBuildTarget} */
  public static UnconfiguredQueryBuildTarget of(UnflavoredBuildTarget buildTarget) {
    return of(UnconfiguredBuildTarget.of(buildTarget));
  }

  /** @return The value of the {@code buildTarget} attribute */
  public UnconfiguredBuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public String toString() {
    return buildTarget.toString();
  }

  @Override
  public boolean equals(@Nullable Object other) {
    if (!(other instanceof UnconfiguredQueryBuildTarget)) {
      return false;
    }

    UnconfiguredQueryBuildTarget that = (UnconfiguredQueryBuildTarget) other;
    return buildTarget.equals((that.buildTarget));
  }

  @Override
  public int hashCode() {
    return buildTarget.hashCode();
  }
}
