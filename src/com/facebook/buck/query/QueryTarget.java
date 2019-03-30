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

  @Override
  default int compareTo(QueryTarget other) {
    if (this == other) {
      return 0;
    }

    return toString().compareTo(other.toString());
  }
}
