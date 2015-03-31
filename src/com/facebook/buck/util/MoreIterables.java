/*
 * Copyright 2014-present Facebook, Inc.
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MoreIterables {

  private MoreIterables() {}

  private static <T> ImmutableList<Iterator<T>> iterators(Iterable<T> inputs[]) {
    ImmutableList.Builder<Iterator<T>> iterators = ImmutableList.builder();
    for (Iterable<T> input : inputs) {
      iterators.add(input.iterator());
    }
    return iterators.build();
  }

  /**
   * Combine the given iterables by peeling off items one at a time from each of the input
   * iterables until any one of the iterables are exhausted.
   */
  @SafeVarargs
  public static <T> Iterable<T> zipAndConcat(Iterable<T>... inputs) {

    // If no inputs were seen, just return an empty list.
    if (inputs.length == 0) {
      return ImmutableList.of();
    }

    ImmutableList.Builder<T> result = ImmutableList.builder();
    ImmutableList<Iterator<T>> iterators = iterators(inputs);

    // Keep grabbing rounds from the input iterators until we've exhausted one
    // of them, then return.
    List<T> round = Lists.newArrayListWithCapacity(inputs.length);
    while (true) {
      for (Iterator<T> iterator : iterators) {
        if (!iterator.hasNext()) {
          return result.build();
        }
        round.add(iterator.next());
      }
      result.addAll(round);
      round.clear();
    }
  }

  /**
   * Returns a deduped version of toDedup and keeps the order of elements
   * If a key is contained more than once
   * (that is, there are multiple elements e1, e2... en, such that ei.equals(ej))
   * then the last one will be kept in the ordering
   */
  public static <T> Set<T> dedupKeepLast(Iterable<T> toDedup) {
    Set<T> dedupedSet = new LinkedHashSet<>();
    for (T t : toDedup) {
      if (dedupedSet.contains(t)) {
        dedupedSet.remove(t);
      }
      dedupedSet.add(t);
    }

    return dedupedSet;
  }

}
