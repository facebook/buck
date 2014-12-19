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

import com.facebook.buck.graph.AbstractAcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.AbstractAcyclicDepthFirstPostOrderTraversal.CycleException;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.io.ProjectFilesystem;

import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.Funnels;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.Path;
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
  public static ImmutableMap<BuildTarget, HashCode> hashTargetGraph(
      ProjectFilesystem projectFilesystem,
      TargetGraph targetGraph,
      Function<BuildTarget, HashCode> buildTargetToRuleHashCode,
      BuildTarget... roots
    ) throws IOException {
    return hashTargetGraph(
        projectFilesystem,
        targetGraph,
        buildTargetToRuleHashCode,
        ImmutableList.copyOf(roots)
    );
  }

  /**
   * Given a {@link TargetGraph} and any number of root nodes to traverse,
   * returns a map of {@code (BuildTarget, HashCode)} pairs for all root
   * build targets and their dependencies.
   */
  public static ImmutableMap<BuildTarget, HashCode> hashTargetGraph(
      ProjectFilesystem projectFilesystem,
      TargetGraph targetGraph,
      Function<BuildTarget, HashCode> buildTargetToRuleHashCode,
      Iterable<BuildTarget> roots
    ) throws IOException {
    try {
      Map<BuildTarget, HashCode> buildTargetHashes = new HashMap<>();
      TargetGraphHashingTraversal traversal = new TargetGraphHashingTraversal(
          projectFilesystem,
          targetGraph,
          buildTargetToRuleHashCode,
          buildTargetHashes);
      traversal.traverse(targetGraph.getAll(roots));
      return ImmutableMap.copyOf(buildTargetHashes);
    } catch (CycleException | InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  private static class TargetGraphHashingTraversal
      extends AbstractAcyclicDepthFirstPostOrderTraversal<TargetNode<?>> {
    private final ProjectFilesystem projectFilesystem;
    private final TargetGraph targetGraph;
    private final Function<BuildTarget, HashCode> buildTargetToRuleHashCode;
    private final Map<BuildTarget, HashCode> buildTargetHashes;

    public TargetGraphHashingTraversal(
        ProjectFilesystem projectFilesystem,
        TargetGraph targetGraph,
        Function<BuildTarget, HashCode> buildTargetToRuleHashCode,
        Map<BuildTarget, HashCode> buildTargetHashes) {
      this.projectFilesystem = projectFilesystem;
      this.targetGraph = targetGraph;
      this.buildTargetToRuleHashCode = buildTargetToRuleHashCode;
      this.buildTargetHashes = buildTargetHashes;
    }

    @Override
    protected Iterator<TargetNode<?>> findChildren(TargetNode<?> node) {
      return targetGraph.getAll(node.getDeps()).iterator();
    }

    @Override
    protected void onNodeExplored(TargetNode<?> node) throws IOException {
      if (buildTargetHashes.containsKey(node.getBuildTarget())) {
        LOG.verbose("Already hashed node %s, not hashing again.", node);
        return;
      }
      Hasher hasher = Hashing.sha1().newHasher();
      hashNode(hasher, node);
      HashCode result = hasher.hash();
      LOG.debug("Hash for target %s: %s", node.getBuildTarget(), result);
      buildTargetHashes.put(node.getBuildTarget(), result);
    }

    private void hashNode(final Hasher hasher, final TargetNode<?> node) throws IOException {
      LOG.verbose("Hashing node %s", node);
      // Hash the node's build target, rules, and input file contents.
      hashStringAndLength(hasher, node.getBuildTarget().toString());
      HashCode targetRuleHashCode = buildTargetToRuleHashCode.apply(node.getBuildTarget());
      LOG.verbose("Got rules hash %s", targetRuleHashCode);
      hasher.putBytes(targetRuleHashCode.asBytes());

      try (final OutputStream hasherOutputStream = Funnels.asOutputStream(hasher)) {
        for (Path path : walkedPathsInSortedOrder(node.getInputs())) {
          LOG.verbose("Node %s: adding input file contents %s", node, path);
          hashStringAndLength(hasher, path.toString());
          hasher.putLong(projectFilesystem.getFileSize(path));
          try (InputStream inputStream = projectFilesystem.newFileInputStream(path)) {
            ByteStreams.copy(inputStream, hasherOutputStream);
          }
        }
      }

      // We've already visited the dependencies (this is a depth-first traversal), so
      // hash each dependency's build target and that build target's own hash.
      for (BuildTarget dependency : node.getDeps()) {
        HashCode dependencyHashCode = buildTargetHashes.get(dependency);
        Preconditions.checkState(dependencyHashCode != null);
        LOG.verbose("Node %s: adding dependency %s (%s)", node, dependency, dependencyHashCode);
        hashStringAndLength(hasher, dependency.toString());
        hasher.putBytes(dependencyHashCode.asBytes());
      }
    }

    private static void hashStringAndLength(Hasher hasher, String string) {
      byte[] utf8Bytes = string.getBytes(Charsets.UTF_8);
      hasher.putInt(utf8Bytes.length);
      hasher.putBytes(utf8Bytes);
    }

    private ImmutableSortedSet<Path> walkedPathsInSortedOrder(
        Iterable<Path> pathsToWalk)
        throws IOException {
      final ImmutableSortedSet.Builder<Path> walkedPaths = ImmutableSortedSet.naturalOrder();
      for (Path pathToWalk : pathsToWalk) {
        projectFilesystem.walkRelativeFileTree(
          pathToWalk,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
                throws IOException {
              walkedPaths.add(path);
              return FileVisitResult.CONTINUE;
            }
          });
      }
      return walkedPaths.build();
    }

    @Override
    protected void onTraversalComplete(Iterable<TargetNode<?>> nodesInExplorationOrder) {
      // Nothing to do; we did our work in onNodeExplored().
    }
  }
}
