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

namespace java com.facebook.buck.query.thrift

//Node of the graph
struct DirectedAcyclicGraphNode {
  //Node name
  1: string name;
  //Node attributes : key-value pairs
  2: optional map<string, string> nodeAttributes;
}

// Graph's edge
struct DirectedAcyclicGraphEdge {
  //From node
  1: DirectedAcyclicGraphNode fromNode;
  //To node
  2: DirectedAcyclicGraphNode toNode;
}

// Graph
struct DirectedAcyclicGraph{
  // list of nodes
  1: list<DirectedAcyclicGraphNode> nodes;
  // list of edges
  2: list<DirectedAcyclicGraphEdge> edges;
}