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

package com.facebook.buck.core.model.targetgraph.impl;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.json.JsonObjectHashing;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.facebook.buck.util.hashing.StringHashing;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;

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
  private final Iterable<TargetNode<?>> roots;
  private final ListeningExecutorService executor;
  private final RuleKeyConfiguration ruleKeyConfiguration;
  private final Function<TargetNode<?>, ListenableFuture<?>> targetNodeRawAttributesProvider;

  public TargetGraphHashing(
      BuckEventBus eventBus,
      TargetGraph targetGraph,
      FileHashLoader fileHashLoader,
      Iterable<TargetNode<?>> roots,
      ListeningExecutorService executor,
      RuleKeyConfiguration ruleKeyConfiguration,
      Function<TargetNode<?>, ListenableFuture<?>> targetNodeRawAttributesProvider) {
    this.eventBus = eventBus;
    this.targetGraph = targetGraph;
    this.fileHashLoader = fileHashLoader;
    this.roots = roots;
    this.executor = executor;
    this.ruleKeyConfiguration = ruleKeyConfiguration;
    this.targetNodeRawAttributesProvider = targetNodeRawAttributesProvider;
  }

  /**
   * Given a {@link TargetGraph} and any number of root nodes to traverse, returns a map of {@code
   * (BuildTarget, HashCode)} pairs for all root build targets and their dependencies.
   */
  public ImmutableMap<BuildTarget, HashCode> hashTargetGraph() throws InterruptedException {
    try (SimplePerfEvent.Scope scope =
        SimplePerfEvent.scope(eventBus, PerfEventId.of("ShowTargetHashes"))) {
      return new Runner().run();
    } catch (ExecutionException e) {
      Throwables.throwIfUnchecked(e.getCause());
      throw new RuntimeException(e);
    }
  }

  private class Runner {

    private final Map<BuildTarget, ListenableFuture<HashCode>> futures =
        new ConcurrentHashMap<>(targetGraph.getSize());

    /**
     * The initial hashing phase of a node, which hashes everything except its dependencies.
     *
     * @return the partial {@link Hasher}.
     */
    private Hasher startNode(TargetNode<?> node, Object nodeAttributes) {
      Hasher hasher = Hashing.sha1().newHasher();

      // Hash the node's build target and rules.
      LOG.verbose("Hashing node %s", node);
      StringHashing.hashStringAndLength(hasher, node.getBuildTarget().toString());
      JsonObjectHashing.hashJsonObject(hasher, nodeAttributes);
      hasher.putString(ruleKeyConfiguration.getCoreKey(), StandardCharsets.UTF_8);

      // Hash the contents of all input files and directories.
      ProjectFilesystem cellFilesystem = node.getFilesystem();
      for (Path input : ImmutableSortedSet.copyOf(node.getInputs())) {
        try {
          hasher.putBytes(fileHashLoader.get(cellFilesystem.resolve(input)).asBytes());
        } catch (IOException e) {
          throw new HumanReadableException(
              e, "Error reading path %s for rule %s", input, node.getBuildTarget());
        }
      }

      return hasher;
    }

    /**
     * Finish up hashing a node by including its dependencies.
     *
     * @return the nodes {@link HashCode}.
     */
    private HashCode finishNode(
        BuildTarget node, Hasher hasher, List<Pair<BuildTarget, HashCode>> depPairs) {
      for (Pair<BuildTarget, HashCode> depPair : depPairs) {
        LOG.verbose(
            "Node %s: adding dependency %s (%s)", node, depPair.getFirst(), depPair.getSecond());
        StringHashing.hashStringAndLength(hasher, depPair.getFirst().toString());
        hasher.putBytes(depPair.getSecond().asBytes());
      }
      return hasher.hash();
    }

    /**
     * @return the {@link HashCode} of all the node's dependencies as as a {@link ListenableFuture}
     *     of a list of {@link BuildTarget} and {@link HashCode} pairs.
     */
    private ListenableFuture<List<Pair<BuildTarget, HashCode>>> getDepPairsFuture(
        TargetNode<?> node) {
      return Futures.allAsList(
          node.getParseDeps()
              .stream()
              .map(
                  dep ->
                      Futures.transform(
                          getHash(targetGraph.get(dep)),
                          depHash -> new Pair<>(dep, depHash),
                          MoreExecutors.directExecutor()))
              .collect(Collectors.toList()));
    }

    private ListenableFuture<HashCode> getHash(TargetNode<?> node) {
      // NOTE: Our current implementation starts *all* the target node hashes in parallel, and
      // and only starts synchronizing near the end, when nodes need to incorporate the hashes of
      // their dependencies.  As such, we're basically trading off the extra memory required to
      // keep around the hashers for all in flight node hashing operations for the ability to mine
      // extra parallelism.  The hashers should be relatively small, but it's possible that in
      // some situations, this tradeoff isn't ideal, in which case we could switch to only
      // *starting* to hash a node after it's dependencies have completed.
      ListenableFuture<HashCode> future = futures.get(node.getBuildTarget());
      if (future == null) {
        future =
            Futures.transformAsync(
                // Start hashing a node.
                Futures.transform(
                    targetNodeRawAttributesProvider.apply(node),
                    attributes -> startNode(node, attributes),
                    executor),
                // Wait for all dependencies to finish hashing.
                hasher ->
                    Futures.transform(
                        getDepPairsFuture(node),
                        depPairs -> finishNode(node.getBuildTarget(), hasher, depPairs),
                        MoreExecutors.directExecutor()),
                executor);
        futures.put(node.getBuildTarget(), future);
      }
      return future;
    }

    /**
     * @return a map of all {@link BuildTarget}s to {@link HashCode}s for the graph defined by the
     *     given roots.
     */
    private ImmutableMap<BuildTarget, HashCode> run()
        throws InterruptedException, ExecutionException {

      // Kick off future chain and wait for roots to complete.  Due to the recursive nature of
      // target hashes, once these have completed, all transitive deps should be finished as well.
      Futures.allAsList(RichStream.from(roots).map(this::getHash).toImmutableList()).get();

      // Wait for all scheduled tasks to complete
      ImmutableMap.Builder<BuildTarget, HashCode> results = ImmutableMap.builder();
      for (Map.Entry<BuildTarget, ListenableFuture<HashCode>> ent : futures.entrySet()) {
        results.put(ent.getKey(), ent.getValue().get());
      }
      return results.build();
    }
  }
}
