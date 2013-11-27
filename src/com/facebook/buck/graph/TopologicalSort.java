/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.graph;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;

public class TopologicalSort {

  private TopologicalSort() {}

  public static <T> ImmutableList<T> sort(
      TraversableGraph<T> graph,
      final Predicate<T> inclusionPredicate) {
    AbstractBottomUpTraversal<T, ImmutableList<T>> traversal =
        new AbstractBottomUpTraversal<T, ImmutableList<T>>(graph) {

      final ImmutableList.Builder<T> builder = ImmutableList.builder();

      @Override
      public void visit(T node) {
        if (inclusionPredicate.apply(node)) {
          builder.add(node);
        }
      }

      @Override
      public ImmutableList<T> getResult() {
        return builder.build();
      }

    };
    traversal.traverse();
    return traversal.getResult();
  }
}
