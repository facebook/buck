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

package com.facebook.buck.core.graph.transformation;

import static org.junit.Assert.assertEquals;

import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;
import org.junit.Test;

public class DefaultAsyncTransformationEngineStackUseTest {

  @Test
  public void largeGraphShouldNotStackOverflow() {
    MutableGraph<Long> graph = GraphBuilder.directed().build();
    // We set -Xss500k for the JVM for this test, so our stack is very small.
    for (long i = 1L; i <= 6000L; i++) {
      graph.addNode(i);
      if (i > 1) {
        graph.putEdge(i - 1, i);
      }
    }

    ChildrenAdder transformer = new ChildrenAdder(graph);
    assertEquals(
        (Long) 18003000L, // arithmetic series from 1 to 6000
        // https://www.wolframalpha.com/input/?i=sum+from+1+to+6000
        new DefaultAsyncTransformationEngine<>(transformer, graph.nodes().size())
            .computeUnchecked((Long) 1L));
  }
}
