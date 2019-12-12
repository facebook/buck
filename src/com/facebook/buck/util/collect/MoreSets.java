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

package com.facebook.buck.util.collect;

import com.google.common.collect.ImmutableSet;

/** Additional methods to deal with sets. */
public class MoreSets {
  private MoreSets() {}

  /** Creates a union of two immutable sets. */
  public static <T> ImmutableSet<T> union(ImmutableSet<T> set1, ImmutableSet<T> set2) {
    return ImmutableSet.<T>builderWithExpectedSize(set1.size() + set2.size())
        .addAll(set1)
        .addAll(set2)
        .build();
  }
}
