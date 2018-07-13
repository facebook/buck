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

package com.facebook.buck.features.project.intellij.model;

import com.google.common.base.Preconditions;
import java.util.Map;

public enum DependencyType {
  /**
   * The current {@link IjModule} depends on the other element from test code only. This only
   * happens if a particular module contains both test and production code and only code in the test
   * folders needs to reference the other element.
   */
  TEST,
  /** The current {@link IjModule} depends on the other element from production (non-test) code. */
  PROD,
  /** The current {@link IjModule} depends on the other element from runtime only. */
  RUNTIME,
  /**
   * This dependency means that the other element contains a compiled counterpart to this element.
   * This is used when the current element uses BUCK features which cannot be expressed in IntelliJ.
   */
  COMPILED_SHADOW,
  ;

  public static DependencyType merge(DependencyType left, DependencyType right) {
    if (left.equals(right)) {
      return left;
    }
    Preconditions.checkArgument(
        !left.equals(COMPILED_SHADOW) && !right.equals(COMPILED_SHADOW),
        "The COMPILED_SHADOW type cannot be merged with other types.");
    if (left == DependencyType.RUNTIME) {
      return right;
    } else if (right == DependencyType.RUNTIME) {
      return left;
    } else {
      return DependencyType.PROD;
    }
  }

  public static <T> void putWithMerge(Map<T, DependencyType> map, T key, DependencyType value) {
    DependencyType oldValue = map.get(key);
    if (oldValue != null) {
      value = merge(oldValue, value);
    }
    map.put(key, value);
  }
}
