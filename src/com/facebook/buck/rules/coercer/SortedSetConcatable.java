/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.rules.coercer.concat.Concatable;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

public class SortedSetConcatable<T> implements Concatable<ImmutableSortedSet<T>> {
  @Override
  public ImmutableSortedSet<T> concat(Iterable<ImmutableSortedSet<T>> elements) {
    return ImmutableSortedSet.copyOf(Iterables.concat(elements));
  }
}
