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

package com.facebook.buck.cli;

import com.facebook.buck.query.thrift.DirectedAcyclicGraphEdge;
import com.facebook.buck.query.thrift.DirectedAcyclicGraphNode;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility class with methods that can be used in Tests to verify correctness of thrift object
 * serialization/deserialization.
 */
public final class ThriftOutputUtils {

  private ThriftOutputUtils() {}

  /**
   * Converts list of thrift's DirectedAcyclicGraphNode objects into list of their string
   * representations.
   */
  public static List<String> nodesToStringList(List<DirectedAcyclicGraphNode> nodes) {
    return nodes.stream().map(ThriftOutputUtils::nodeToString).collect(Collectors.toList());
  }

  /** Converts thrift's DirectedAcyclicGraphNode object into string representations. */
  public static String nodeToString(DirectedAcyclicGraphNode node) {
    return node.getName();
  }

  /**
   * Converts list of thrift's DirectedAcyclicGraphEdge objects into list of their string
   * representations.
   */
  public static List<String> edgesToStringList(List<DirectedAcyclicGraphEdge> edges) {
    return edges.stream().map(ThriftOutputUtils::edgeToString).collect(Collectors.toList());
  }

  /** Converts thrift's DirectedAcyclicGraphEdge object into string representations. */
  public static String edgeToString(DirectedAcyclicGraphEdge edge) {
    return edge.getFromNode().getName() + "->" + edge.getToNode().getName();
  }
}
