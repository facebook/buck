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

package com.facebook.buck.rules;

import com.facebook.buck.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.AcyclicDepthFirstPostOrderTraversal.CycleException;
import com.facebook.buck.graph.GraphTraversable;
import com.facebook.buck.hashing.FileHashLoader;
import com.facebook.buck.hashing.PathHashing;
import com.facebook.buck.hashing.StringHashing;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Utility class to calculate hash codes for build targets in a {@link TargetGraph}.
 *
 * A build target's hash code is guaranteed to change if the build
 * target or any of its dependencies change, including the contents of
 * all input files to the target and its dependencies.
 */
public class TargetGraphHashing {
  private static final Logger LOG = Logger.get(TargetGraphHashing.class);

  // Utility class; do not instantiate.
  private TargetGraphHashing() { }

  /**
   * Given a {@link TargetGraph} and any number of root nodes to traverse,
   * returns a map of {@code (BuildTarget, HashCode)} pairs for all root
   * build targets and their dependencies.
   */
  public static ImmutableMap<TargetNode<?>, HashCode> hashTargetGraph(
      Cell rootCell,
      final TargetGraph targetGraph,
      FileHashLoader fileHashLoader,
      Iterable<TargetNode<?>> roots) throws IOException {
    try {
      Map<TargetNode<?>, HashCode> buildTargetHashes = new HashMap<>();
      AcyclicDepthFirstPostOrderTraversal<TargetNode<?>> traversal =
          new AcyclicDepthFirstPostOrderTraversal<>(
              new GraphTraversable<TargetNode<?>>() {
                @Override
                public Iterator<TargetNode<?>> findChildren(TargetNode<?> node) {
                  return targetGraph.getAll(node.getDeps()).iterator();
                }
              });
      for (TargetNode<?> node : traversal.traverse(roots)) {
        if (buildTargetHashes.containsKey(node)) {
          LOG.verbose("Already hashed node %s, not hashing again.", node);
          continue;
        }
        Hasher hasher = Hashing.sha1().newHasher();
        try {
          hashNode(rootCell, fileHashLoader, targetGraph, hasher, buildTargetHashes, node);
        } catch (IOException e) {
          throw new HumanReadableException(
              e,
              "Exception while attempting to hash %s: %s",
              node.getBuildTarget().getFullyQualifiedName(),
              e.getMessage());
        }
        HashCode result = hasher.hash();
        LOG.debug("Hash for target %s: %s", node.getBuildTarget(), result);
        buildTargetHashes.put(node, result);
      }
      return ImmutableMap.copyOf(buildTargetHashes);
    } catch (CycleException e) {
      throw new RuntimeException(e);
    }
  }

  private static void hashNode(
      Cell rootCell,
      FileHashLoader fileHashLoader,
      TargetGraph targetGraph,
      Hasher hasher,
      Map<TargetNode<?>, HashCode> buildTargetHashes,
      TargetNode<?> node) throws IOException {
    LOG.verbose("Hashing node %s", node);
    // Hash the node's build target and rules.
    StringHashing.hashStringAndLength(hasher, node.getBuildTarget().toString());
    HashCode targetRuleHashCode = node.getRawInputsHashCode();
    LOG.verbose("Got rules hash %s", targetRuleHashCode);
    hasher.putBytes(targetRuleHashCode.asBytes());

    ProjectFilesystem cellFilesystem = rootCell.getCell(node.getBuildTarget()).getFilesystem();

    // Hash the contents of all input files and directories.
    PathHashing.hashPaths(
        hasher,
        fileHashLoader,
        cellFilesystem,
        ImmutableSortedSet.copyOf(node.getInputs()));

    // We've already visited the dependencies (this is a depth-first traversal), so
    // hash each dependency's build target and that build target's own hash.
    for (BuildTarget dependency : node.getDeps()) {
      HashCode dependencyHashCode = buildTargetHashes.get(targetGraph.get(dependency));
      Preconditions.checkState(dependencyHashCode != null);
      LOG.verbose("Node %s: adding dependency %s (%s)", node, dependency, dependencyHashCode);
      StringHashing.hashStringAndLength(hasher, dependency.toString());
      hasher.putBytes(dependencyHashCode.asBytes());
    }
  }
}
