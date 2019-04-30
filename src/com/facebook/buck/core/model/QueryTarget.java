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

package com.facebook.buck.core.model;

/**
 * Currently, this is a marker interface, but given the actual implementations of this interface, it
 * would be more accurate to represent it as an algebraic data type:
 *
 * <pre>
 * sealed class QueryTarget {
 *   data class BuildQueryTarget(val buildTarget: BuildTarget) : QueryTarget()
 *   data class QueryFileTarget(val path: SourcePath) : QueryTarget()
 * }
 * </pre>
 *
 * <p>Implementors of this class <strong>MUST</strong> provide their own implementation of {@link
 * Object#toString()} so that {@link #compare(QueryTarget, QueryTarget)} works as expected.
 */
public interface QueryTarget {

  /** Compare {@link QueryTarget}s by their {@link Object#toString()} methods. */
  static int compare(QueryTarget a, QueryTarget b) {
    if (a == b) {
      return 0;
    }

    return a.toString().compareTo(b.toString());
  }
}
