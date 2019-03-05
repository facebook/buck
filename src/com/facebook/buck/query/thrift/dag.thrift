# Copyright 2016 Facebook. All Rights Reserved.
#
#!/usr/local/bin/thrift -cpp -py -java
#
# Whenever you change this file please run the following command to refresh the java source code:
# $ thrift --gen java  -out src-gen/ src/com/facebook/buck/query/thrift/dag.thrift

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