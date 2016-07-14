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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
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
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveTask;


/**
 * Utility class to calculate hash codes for build targets in a {@link TargetGraph}.
 *
 * A build target's hash code is guaranteed to change if the build
 * target or any of its dependencies change, including the contents of
 * all input files to the target and its dependencies.
 */
public class TargetGraphHashing {
  private static final Logger LOG = Logger.get(TargetGraphHashing.class);
  private final BuckEventBus eventBus;
  private final TargetGraph targetGraph;
  private final FileHashLoader fileHashLoader;
  private final Iterable<TargetNode<?>> roots;
  private int numThreads = 1;

  public TargetGraphHashing(
      final BuckEventBus eventBus,
      final TargetGraph targetGraph,
      final FileHashLoader fileHashLoader,
      final Iterable<TargetNode<?>> roots
  ) {
    this.eventBus = eventBus;
    this.targetGraph = targetGraph;
    this.fileHashLoader = fileHashLoader;
    this.roots = roots;
  }

  /**
   * Given a {@link TargetGraph} and any number of root nodes to traverse,
   * returns a map of {@code (BuildTarget, HashCode)} pairs for all root
   * build targets and their dependencies.
   */
  public ImmutableMap<BuildTarget, HashCode> hashTargetGraph() throws CycleException {
    try (SimplePerfEvent.Scope scope = SimplePerfEvent.scope(
            eventBus,
            PerfEventId.of("ShowTargetHashes"))) {

      AcyclicDepthFirstPostOrderTraversal<TargetNode<?>> traversal =
          new AcyclicDepthFirstPostOrderTraversal<>(
              new GraphTraversable<TargetNode<?>>() {
                @Override
                public Iterator<TargetNode<?>> findChildren(TargetNode<?> node) {
                  return targetGraph.getAll(node.getDeps()).iterator();
                }
              });

      final Map<BuildTarget, ForkJoinTask<HashCode>> buildTargetHashes = new HashMap<>();
      Queue<ForkJoinTask<HashCode>> tasksToSchedule = new ArrayDeque<>();
      // Create our mapping of build-rules to tasks and arrange in bottom-up order
      // Start all the node tasks, bottom up
      for (final TargetNode<?> node : traversal.traverse(roots)) {
        HashNodeTask task = new HashNodeTask(node, buildTargetHashes);
        buildTargetHashes.put(node.getBuildTarget(), task);
        tasksToSchedule.add(task);
      }

      // Execute tasks in parallel
      ForkJoinPool pool = new ForkJoinPool(numThreads);
      for (ForkJoinTask<HashCode> task : tasksToSchedule) {
        pool.execute(task);
      }

      // Wait for all scheduled tasks to complete
      return ImmutableMap.copyOf(Maps.transformEntries(
          buildTargetHashes,
          new Maps.EntryTransformer<BuildTarget, ForkJoinTask<HashCode>, HashCode>() {
            @Override
            public HashCode transformEntry(BuildTarget key, ForkJoinTask<HashCode> value) {
              return value.join();
            }
          }));
    }
  }

  // Set the parallelism level for calculating the number of target hashes
  public TargetGraphHashing setNumThreads(int numThreads) {
    this.numThreads = numThreads;
    return this;
  }

  private class HashNodeTask extends RecursiveTask<HashCode> {
    private final TargetNode<?> node;
    private Map<BuildTarget, ForkJoinTask<HashCode>> buildTargetHashes;

    HashNodeTask(
        final TargetNode<?> node,
        Map<BuildTarget, ForkJoinTask<HashCode>> buildTargetHashes) {
      this.node = node;
      this.buildTargetHashes = buildTargetHashes;
    }

    @Override
    protected HashCode compute() {
      try (SimplePerfEvent.Scope scope = getHashNodeEventScope(eventBus, node.getBuildTarget())) {
        return hashNode();
      }
    }

    private HashCode hashNode() {
      Hasher hasher = Hashing.sha1().newHasher();
      LOG.verbose("Hashing node %s", node);
      // Hash the node's build target and rules.
      StringHashing.hashStringAndLength(hasher, node.getBuildTarget().toString());
      HashCode targetRuleHashCode = node.getRawInputsHashCode();
      LOG.verbose("Got rules hash %s", targetRuleHashCode);
      hasher.putBytes(targetRuleHashCode.asBytes());

      ProjectFilesystem cellFilesystem = node.getRuleFactoryParams().getProjectFilesystem();

      try {
        // Hash the contents of all input files and directories.
        PathHashing.hashPaths(
            hasher,
            fileHashLoader,
            cellFilesystem,
            ImmutableSortedSet.copyOf(node.getInputs()));
      } catch (IOException e) {
        throw new HumanReadableException(
            e, "Error reading files for rule %s",
            node.getBuildTarget()
        );
      }

      // hash each dependency's build target and that build target's own hash.
      for (BuildTarget dependency : node.getDeps()) {
        ForkJoinTask<HashCode> dependencyHashCodeTask = buildTargetHashes.get(dependency);
        Preconditions.checkState(dependencyHashCodeTask != null);
        HashCode dependencyHashCode = dependencyHashCodeTask.join();
        Preconditions.checkState(dependencyHashCode != null);
        LOG.verbose("Node %s: adding dependency %s (%s)", node, dependency, dependencyHashCode);
        StringHashing.hashStringAndLength(hasher, dependency.toString());
        hasher.putBytes(dependencyHashCode.asBytes());
      }
      HashCode result = hasher.hash();
      LOG.debug("Hash for target %s: %s", node.getBuildTarget(), result);
      return result;
    }
  }

  private static SimplePerfEvent.Scope getHashNodeEventScope(
      BuckEventBus eventBus,
      BuildTarget buildTarget) {
    return SimplePerfEvent.scope(
        eventBus,
        PerfEventId.of("ComputeNodeHash"),
        "target",
        buildTarget);
  }
}
