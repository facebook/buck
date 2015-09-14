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

package com.facebook.buck.util;

import com.google.common.collect.Sets;

import java.util.LinkedHashSet;
import java.util.Set;

public abstract class MoreSets {

  /**
   * Returns a new and mutable set containing the intersection of the
   * two specified sets. Using the smaller of the two sets as the base for
   * finding the intersection for performance reasons.
   */
  public static <T> Set<T> intersection(Set<T> x, Set<T> y) {
    Set<T> result = new LinkedHashSet<>();
    if (x.size() > y.size()) {
      Sets.intersection(y, x).copyInto(result);
    } else {
      Sets.intersection(x, y).copyInto(result);
    }
    return result;
  }
}
