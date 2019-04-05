/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.query;

import com.facebook.buck.core.model.BuildTarget;
import javax.annotation.Nullable;

/** Implementation of {@link QueryTarget} that wraps a {@link BuildTarget}. */
public final class QueryBuildTarget implements QueryTarget {

  private final BuildTarget buildTarget;

  private QueryBuildTarget(BuildTarget buildTarget) {
    this.buildTarget = buildTarget;
  }

  /**
   * Construct a new immutable {@code QueryBuildTarget} instance.
   *
   * @param buildTarget The value for the {@code buildTarget} attribute
   * @return An immutable QueryBuildTarget instance
   */
  public static QueryBuildTarget of(BuildTarget buildTarget) {
    return new QueryBuildTarget(buildTarget);
  }

  /** @return The value of the {@code buildTarget} attribute */
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public String toString() {
    return buildTarget.toString();
  }

  @Override
  public boolean equals(@Nullable Object other) {
    if (!(other instanceof QueryBuildTarget)) {
      return false;
    }

    QueryBuildTarget that = (QueryBuildTarget) other;
    return buildTarget.equals((that.buildTarget));
  }

  @Override
  public int hashCode() {
    return buildTarget.hashCode();
  }
}
