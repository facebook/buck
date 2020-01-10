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

package com.facebook.buck.core.util.graph;

import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ConsumingTraverserTest {

  @Test
  public void forGraphBreadthFirst() {
    Map<Integer, Iterable<Integer>> graph =
        ImmutableMap.<Integer, Iterable<Integer>>builder()
            .put(1, ImmutableList.of(4, 5))
            .put(4, ImmutableList.of(6, 5))
            .put(5, ImmutableList.of(9, 10))
            .build();

    List<Integer> visited = new ArrayList<>();
    ConsumingTraverser.breadthFirst(
            ImmutableList.of(1),
            (node, consumer) -> {
              Iterable<Integer> deps = graph.get(node);
              if (deps != null) {
                deps.forEach(consumer);
              }
            })
        .forEach(visited::add);

    assertThat(visited.indexOf(1), Matchers.lessThan(visited.indexOf(4)));
    assertThat(visited.indexOf(1), Matchers.lessThan(visited.indexOf(5)));
    assertThat(visited.indexOf(4), Matchers.lessThan(visited.indexOf(6)));
    assertThat(visited.indexOf(4), Matchers.lessThan(visited.indexOf(5)));
    assertThat(visited.indexOf(5), Matchers.lessThan(visited.indexOf(9)));
    assertThat(visited.indexOf(5), Matchers.lessThan(visited.indexOf(9)));
    assertThat(visited.indexOf(5), Matchers.lessThan(visited.indexOf(6)));
  }

  @Test
  public void topologicallySorted() {

    // Test graphs.
    List<Map<Integer, Iterable<Integer>>> graphs =
        ImmutableList.of(
            ImmutableMap.<Integer, Iterable<Integer>>builder()
                .put(1, ImmutableList.of(2, 3))
                .put(3, ImmutableList.of(4))
                .put(4, ImmutableList.of(5))
                .put(5, ImmutableList.of(2))
                .build(),
            ImmutableMap.<Integer, Iterable<Integer>>builder()
                .put(1, ImmutableList.of(2, 5))
                .put(2, ImmutableList.of(3))
                .put(3, ImmutableList.of(5))
                .build());

    for (Map<Integer, Iterable<Integer>> graph : graphs) {

      // Run topo-sort on the graph.
      List<Integer> visited = new ArrayList<>();
      ConsumingTraverser.topologicallySorted(
              ImmutableList.of(1),
              (node, consumer) -> {
                Iterable<Integer> deps = graph.get(node);
                if (deps != null) {
                  deps.forEach(consumer);
                }
              })
          .forEach(visited::add);

      // Verify that every node comes before it's dependencies.
      for (Map.Entry<Integer, Iterable<Integer>> ent : graph.entrySet()) {
        for (Integer dep : ent.getValue()) {
          assertThat(visited.indexOf(ent.getKey()), Matchers.lessThan(visited.indexOf(dep)));
        }
      }
    }
  }

  @Test(expected = IllegalStateException.class)
  public void topologicallySortedCycles() {

    // Test graphs.
    Map<Integer, Iterable<Integer>> graph =
        ImmutableMap.<Integer, Iterable<Integer>>builder()
            .put(1, ImmutableList.of(2, 3))
            .put(3, ImmutableList.of(1))
            .build();

    // Run topo-sort on the graph.
    ConsumingTraverser.topologicallySorted(
            ImmutableList.of(1),
            (node, consumer) -> {
              Iterable<Integer> deps = graph.get(node);
              if (deps != null) {
                deps.forEach(consumer);
              }
            })
        .forEach(n -> {});
  }

  @Test(expected = IllegalStateException.class)
  public void topologicallySortedThrowing() {

    // Test graphs.
    Map<Integer, Iterable<Integer>> graph =
        ImmutableMap.<Integer, Iterable<Integer>>builder().put(1, ImmutableList.of(2, 3)).build();

    // Run topo-sort on the graph.
    ConsumingTraverser.topologicallySorted(
            ImmutableList.of(1),
            (node, consumer) -> {
              Iterable<Integer> deps = graph.get(node);
              if (deps != null) {
                deps.forEach(consumer);
              }
            })
        .forEachThrowing(
            n -> {
              throw new IllegalStateException();
            });
  }
}
