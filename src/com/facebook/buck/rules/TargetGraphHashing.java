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
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.facebook.buck.util.hashing.StringHashing;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Utility class to calculate hash codes for build targets in a {@link TargetGraph}.
 *
 * <p>A build target's hash code is guaranteed to change if the build target or any of its
 * dependencies change, including the contents of all input files to the target and its
 * dependencies.
 */
public class TargetGraphHashing {
  private static final Logger LOG = Logger.get(TargetGraphHashing.class);
  private final BuckEventBus eventBus;
  private final TargetGraph targetGraph;
  private final FileHashLoader fileHashLoader;
  private final int numThreads;
  private final Iterable<TargetNode<?, ?>> roots;
  private final Map<BuildTarget, TargetNode<?, ?>> targetNodes;
  private final LoadingCache<BuildTarget, HashCode> allHashes;

  public TargetGraphHashing(
      final BuckEventBus eventBus,
      final TargetGraph targetGraph,
      final FileHashLoader fileHashLoader,
      final int numThreads,
      final Iterable<TargetNode<?, ?>> roots) {
    this.eventBus = eventBus;
    this.targetGraph = targetGraph;
    this.fileHashLoader = fileHashLoader;
    this.numThreads = numThreads;
    this.roots = roots;
    this.targetNodes = new HashMap<>();
    allHashes =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<BuildTarget, HashCode>() {
                  @Override
                  public HashCode load(BuildTarget target) {
                    try (SimplePerfEvent.Scope scope = getHashNodeEventScope(eventBus, target)) {
                      return hashNode(target);
                    }
                  }
                });
  }

  /**
   * Given a {@link TargetGraph} and any number of root nodes to traverse, returns a map of {@code
   * (BuildTarget, HashCode)} pairs for all root build targets and their dependencies.
   */
  public ImmutableMap<BuildTarget, HashCode> hashTargetGraph()
      throws CycleException, InterruptedException {
    try (SimplePerfEvent.Scope scope =
        SimplePerfEvent.scope(eventBus, PerfEventId.of("ShowTargetHashes"))) {

      ConcurrentLinkedQueue<BuildTarget> workQueue = new ConcurrentLinkedQueue<>();
      new AcyclicDepthFirstPostOrderTraversal<TargetNode<?, ?>>(
              node -> targetGraph.getAll(node.getParseDeps()).iterator())
          .traverse(roots)
          .forEach(
              node -> {
                final BuildTarget buildTarget = node.getBuildTarget();
                targetNodes.put(buildTarget, node);
                workQueue.add(buildTarget);
              });

      final ExecutorService executorService = Executors.newFixedThreadPool(numThreads);
      executorService.invokeAll(
          Collections.nCopies(
              numThreads,
              () -> {
                while (true) {
                  BuildTarget target = workQueue.poll();
                  if (target == null) {
                    break;
                  }
                  if (!allHashes.asMap().containsKey(target) && allDepsReady(target)) {
                    allHashes.getUnchecked(target);
                  } else {
                    workQueue.add(target);
                  }
                }
                return null;
              }));
      return ImmutableMap.copyOf(allHashes.asMap());
    }
  }

  private boolean allDepsReady(BuildTarget target) {
    TargetNode<?, ?> node = targetNodes.get(target);
    Preconditions.checkNotNull(node);
    for (BuildTarget dependency : node.getParseDeps()) {
      if (!allHashes.asMap().containsKey(dependency)) {
        return false;
      }
    }
    return true;
  }

  private HashCode hashNode(BuildTarget target) {
    TargetNode<?, ?> node = targetNodes.get(target);
    Preconditions.checkNotNull(node);
    Hasher hasher = Hashing.sha1().newHasher();
    LOG.verbose("Hashing node %s", node);
    // Hash the node's build target and rules.
    StringHashing.hashStringAndLength(hasher, node.getBuildTarget().toString());
    HashCode targetRuleHashCode = node.getRawInputsHashCode();
    LOG.verbose("Got rules hash %s", targetRuleHashCode);
    hasher.putBytes(targetRuleHashCode.asBytes());

    ProjectFilesystem cellFilesystem = node.getFilesystem();

    try (SimplePerfEvent.Scope ignored =
        SimplePerfEvent.scope(eventBus, PerfEventId.of("hashing_inputs")); ) {
      // Hash the contents of all input files and directories.
      for (Path input : ImmutableSortedSet.copyOf(node.getInputs())) {
        try {
          hasher.putBytes(fileHashLoader.get(cellFilesystem.resolve(input)).asBytes());
        } catch (IOException e) {
          throw new HumanReadableException(
              e, "Error reading path %s for rule %s", input, node.getBuildTarget());
        }
      }
    }

    // hash each dependency's build target and that build target's own hash.
    for (BuildTarget dependency : node.getParseDeps()) {
      HashCode dependencyHashCode = allHashes.getUnchecked(dependency);
      LOG.verbose("Node %s: adding dependency %s (%s)", node, dependency, dependencyHashCode);
      StringHashing.hashStringAndLength(hasher, dependency.toString());
      hasher.putBytes(dependencyHashCode.asBytes());
    }

    HashCode result = hasher.hash();
    LOG.debug("Hash for target %s: %s", node.getBuildTarget(), result);
    return result;
  }

  private static SimplePerfEvent.Scope getHashNodeEventScope(
      BuckEventBus eventBus, BuildTarget buildTarget) {
    return SimplePerfEvent.scope(
        eventBus, PerfEventId.of("compute_node_hash"), "target", buildTarget);
  }
}
