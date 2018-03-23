/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.graph.transformation;

import static org.junit.Assert.assertEquals;

import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;
import java.util.concurrent.CompletionStage;
import org.junit.Before;
import org.junit.Test;

/** Test and demonstration of {@link DefaultAsyncTransformationEngine} */
public class DefaultAsyncTransformationEngineTest {

  private MutableGraph<Long> graph;

  @Before
  public void setUp() {
    graph = GraphBuilder.directed().build();

    /**
     * Make a graph
     *
     * <p>Edges directed down
     *
     * <pre>
     *            1
     *         /  |  \
     *        2  4 <- 5
     *       /
     *      3
     * </pre>
     */
    graph.addNode(1L);
    graph.addNode(2L);
    graph.addNode(3L);
    graph.addNode(4L);
    graph.addNode(5L);

    graph.putEdge(1L, 2L);
    graph.putEdge(1L, 4L);
    graph.putEdge(1L, 5L);
    graph.putEdge(5L, 4L);
    graph.putEdge(2L, 3L);
  }

  /**
   * Demonstration of usage of {@link AsyncTransformer}.
   *
   * <p>This returns the value of the sum of its input graph's chidren and itself. For the above
   * graph in {@code graph}, operating on the root would result in 19.
   */
  private class ChildrenAdder implements AsyncTransformer<Long, Long> {

    private final MutableGraph<Long> input;

    public ChildrenAdder(MutableGraph<Long> input) {
      this.input = input;
    }

    @Override
    public CompletionStage<Long> transform(Long key, TransformationEnvironment<Long, Long> env) {
      Iterable<Long> children = input.successors(key);

      return env.evaluateAll(
          children,
          childValues -> {
            return key + childValues.values().parallelStream().mapToLong(Long::new).sum();
          });
    }
  }

  @Test
  public void requestOnLeafResultsSameValue() {
    ChildrenAdder transformer = new ChildrenAdder(graph);
    assertEquals(
        (Long) 3L,
        new DefaultAsyncTransformationEngine<>(transformer, graph.nodes().size())
            .computeUnchecked((Long) 3L));
  }

  @Test
  public void requestOnRootCorrectValue() {
    ChildrenAdder transformer = new ChildrenAdder(graph);
    assertEquals(
        (Long) 19L,
        new DefaultAsyncTransformationEngine<>(transformer, graph.nodes().size())
            .computeUnchecked((Long) 1L));
  }

  @Test
  public void largeGraphShouldNotStackOverflow() {
    graph = GraphBuilder.directed().build();
    // graph of 100000 depth is plenty deep for testing
    for (long i = 1L; i <= 100000L; i++) {
      graph.addNode(i);
      if (i > 1) {
        graph.putEdge(i - 1, i);
      }
    }

    ChildrenAdder transformer = new ChildrenAdder(graph);
    assertEquals(
        (Long) 5000050000L, // arithmetic series from 1 to 100000
        // https://www.wolframalpha.com/input/?i=sum+from+1+to+100000
        new DefaultAsyncTransformationEngine<>(transformer, graph.nodes().size())
            .computeUnchecked((Long) 1L));
  }
}
