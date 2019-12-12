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

import com.facebook.buck.core.exceptions.DependencyStack;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import java.util.HashMap;
import java.util.Objects;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Test;

public class AcyclicDepthFirstPostOrderTraversalWithDependencyStackTest {
  private enum Node implements DependencyStack.Element {
    A,
    B,
    C,
    D,
    E,
  }

  @Test
  public void dependencyStack() throws Exception {
    //      A
    //    /   \
    //  B       C
    //        /   \
    //      D       E
    Multimap<Node, Node> graph = LinkedListMultimap.create();
    graph.put(Node.A, Node.B);
    graph.put(Node.A, Node.C);
    graph.put(Node.C, Node.D);
    graph.put(Node.C, Node.E);

    HashMap<Node, String> expectedStacks = new HashMap<>();
    expectedStacks.put(Node.A, "A");
    expectedStacks.put(Node.B, "B A");
    expectedStacks.put(Node.C, "C A");
    expectedStacks.put(Node.D, "D C A");
    expectedStacks.put(Node.E, "E C A");

    AcyclicDepthFirstPostOrderTraversalWithDependencyStack<Node> traversal =
        new AcyclicDepthFirstPostOrderTraversalWithDependencyStack<>(
            (node, dependencyStack) -> {
              String actualStack =
                  dependencyStack.collect().stream()
                      .map(Objects::toString)
                      .collect(Collectors.joining(" "));
              Assert.assertEquals(actualStack, expectedStacks.get(node));
              return graph.get(node).iterator();
            },
            DependencyStack::child);
    traversal.traverse(ImmutableList.of(Node.A));
  }
}
