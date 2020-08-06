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

package com.facebook.buck.core.model;

import com.facebook.buck.core.util.immutables.BuckStylePrehashedValue;

/**
 * Wrapper for an unconfigured build target and its output label.
 *
 * <p>For example, for the target {@code //:foo}, the unconfigured target would be {@code //:foo},
 * and the output label would be empty.
 *
 * <p>For the target {@code //:foo[baz]}, the unconfigured target would be {@code //:foo}, and the
 * output label would be {@code baz}.
 *
 * <p>For the target {@code //:foo#flavor[baz]}, the unconfigured target would be {@code
 * //:foo#flavor}, and the output label would be {@code baz}.
 *
 * <p>See also {@link BuildTargetWithOutputs} for outputs with configured build targets.
 */
@BuckStylePrehashedValue
public abstract class UnconfiguredBuildTargetWithOutputs
    implements Comparable<UnconfiguredBuildTargetWithOutputs> {

  /** Returns the associated {@link UnconfiguredBuildTarget}. */
  public abstract UnconfiguredBuildTarget getBuildTarget();

  /** Returns the output label associated with the build target, if any. */
  public abstract OutputLabel getOutputLabel();

  @Override
  public int compareTo(UnconfiguredBuildTargetWithOutputs other) {
    if (this == other) {
      return 0;
    }

    int targetComparison = getBuildTarget().compareTo(other.getBuildTarget());
    if (targetComparison != 0) {
      return targetComparison;
    }

    return getOutputLabel().compareTo(other.getOutputLabel());
  }

  /**
   * Returns the string representation of a {@code UnconfiguredBuildTargetWithOutputs} in the form
   * of target_name[output_label]. E.g. //foo:bar[baz]. If no output label is available, the square
   * brackets are omitted. E.g. //foo:bar
   */
  @Override
  public String toString() {
    return getOutputLabel().isDefault()
        ? getBuildTarget().getFullyQualifiedName()
        : String.format("%s[%s]", getBuildTarget(), getOutputLabel());
  }

  public static UnconfiguredBuildTargetWithOutputs of(
      UnconfiguredBuildTarget buildTarget, OutputLabel outputLabel) {
    return ImmutableUnconfiguredBuildTargetWithOutputs.of(buildTarget, outputLabel);
  }

  /** Apply the configuration. */
  public BuildTargetWithOutputs configure(TargetConfiguration targetConfiguration) {
    return BuildTargetWithOutputs.of(
        getBuildTarget().configure(targetConfiguration), getOutputLabel());
  }
}
