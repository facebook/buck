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

import com.google.common.collect.ImmutableSet;

/**
 * Currently, this is effectively a marker interface, but given the actual implementations of this
 * interface, it would be more accurate to represent it as an algebraic data type:
 *
 * <pre>
 * sealed class QueryTarget {
 *   data class BuildQueryTarget(val buildTarget: BuildTarget) : QueryTarget()
 *   data class QueryFileTarget(val path: SourcePath) : QueryTarget()
 * }
 * </pre>
 *
 * Note that implementations of {@link QueryEnvironment} make heavy use of <code>instanceof</code>
 * because they do not actually work with arbitrary implementations of <code>QueryTarget</code>.
 *
 * <p>Implementors of this class <strong>MUST</strong> provide their own implementation of {@link
 * Object#toString()} so that {@link #compareTo(QueryTarget)} works as expected. We could try to
 * make this more obvious by doing something like:
 *
 * <pre>
 * String getStringRepresentation();
 *
 * @Override
 * default String toString() { return getStringRepresentation() }
 *
 * // compareTo() would be defined in terms of getStringRepresentation().
 * </pre>
 *
 * Unfortunately, it turns out we cannot define a default implementation of {@link
 * Object#toString()}: https://stackoverflow.com/q/24595266/396304.
 */
public interface QueryTarget extends Comparable<QueryTarget> {

  /** @return target as {@link QueryBuildTarget} or throw {@link IllegalArgumentException} */
  static QueryBuildTarget asQueryBuildTarget(QueryTarget target) {
    if (!(target instanceof QueryBuildTarget)) {
      throw new IllegalArgumentException(
          String.format(
              "Expected %s to be a build target but it was an instance of %s",
              target, target.getClass().getName()));
    }

    return (QueryBuildTarget) target;
  }

  /** @return set as {@link QueryBuildTarget}s or throw {@link IllegalArgumentException} */
  @SuppressWarnings("unchecked")
  static ImmutableSet<QueryBuildTarget> asQueryBuildTargets(
      ImmutableSet<? extends QueryTarget> set) {
    // It is probably rare that there is a QueryTarget that is not a QueryBuildTarget.
    boolean hasInvalidItem = set.stream().anyMatch(item -> !(item instanceof QueryBuildTarget));
    if (!hasInvalidItem) {
      return (ImmutableSet<QueryBuildTarget>) set;
    } else {
      throw new IllegalArgumentException(
          String.format("%s has elements that are not QueryBuildTarget", set));
    }
  }

  // HACK: Find a way to do this without creating a copy.
  static ImmutableSet<QueryTarget> toQueryTargets(ImmutableSet<? extends QueryTarget> set) {
    return set.stream().map(QueryTarget.class::cast).collect(ImmutableSet.toImmutableSet());
  }

  /** @return the set filtered by items that are instances of {@link QueryBuildTarget} */
  @SuppressWarnings("unchecked")
  static ImmutableSet<QueryBuildTarget> filterQueryBuildTargets(
      ImmutableSet<? extends QueryTarget> set) {
    // It is probably rare that there is a QueryTarget that is not a QueryBuildTarget.
    boolean needsFilter = set.stream().anyMatch(item -> !(item instanceof QueryBuildTarget));
    if (!needsFilter) {
      return (ImmutableSet<QueryBuildTarget>) set;
    } else {
      return set.stream()
          .filter(QueryBuildTarget.class::isInstance)
          .map(QueryBuildTarget.class::cast)
          .collect(ImmutableSet.toImmutableSet());
    }
  }

  @Override
  default int compareTo(QueryTarget other) {
    if (this == other) {
      return 0;
    }

    return toString().compareTo(other.toString());
  }
}
