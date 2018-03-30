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
}
