/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.versions;

import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

/**
 * Takes a regular {@link TargetGraph}, resolves any versioned nodes, and returns a new graph with
 * the versioned nodes removed.
 */
public class VersionedTargetGraphBuilder {

  private static final Logger LOG = Logger.get(VersionedTargetGraphBuilder.class);
  private static final int NON_ROOT_NODE_PACK_SIZE = 100;

  private final ForkJoinPool pool;
  private final VersionSelector versionSelector;
  private final TargetGraphAndBuildTargets unversionedTargetGraphAndBuildTargets;

  /**
   * The resolved version graph being built.
   */
  private final MutableDirectedGraph<TargetNode<?, ?>> graph;

  /**
   * Map of the build targets to nodes in the resolved graph.
   */
  private final ConcurrentHashMap<BuildTarget, TargetNode<?, ?>> index;

  /**
   * Fork-join actions for each root node.
   */
  private final ConcurrentHashMap<BuildTarget, RootAction> rootActions;

  /**
   * Intermediate version info for each node.
   */
  private final ConcurrentHashMap<BuildTarget, VersionInfo> versionInfo;

  /**
   * Count of root nodes.
   */
  private final AtomicInteger roots = new AtomicInteger();

  public VersionedTargetGraphBuilder(
      ForkJoinPool pool,
      VersionSelector versionSelector,
      TargetGraphAndBuildTargets unversionedTargetGraphAndBuildTargets) {

    Preconditions.checkArgument(
        unversionedTargetGraphAndBuildTargets.getTargetGraph().getGroups().isEmpty(),
        "graph versioning does not currently support target groups");

    this.pool = pool;
    this.versionSelector = versionSelector;
    this.unversionedTargetGraphAndBuildTargets = unversionedTargetGraphAndBuildTargets;

    this.graph = MutableDirectedGraph.createConcurrent();
    this.index =
        new ConcurrentHashMap<>(
            unversionedTargetGraphAndBuildTargets.getTargetGraph().getNodes().size() * 4,
            0.75f,
            pool.getParallelism());
    this.rootActions =
        new ConcurrentHashMap<>(
            unversionedTargetGraphAndBuildTargets.getTargetGraph().getNodes().size() / 2,
            0.75f,
            pool.getParallelism());
    this.versionInfo =
        new ConcurrentHashMap<>(
            2 * unversionedTargetGraphAndBuildTargets.getTargetGraph().getNodes().size(),
            0.75f,
            pool.getParallelism());
  }

  private TargetNode<?, ?> getNode(BuildTarget target) {
    return unversionedTargetGraphAndBuildTargets.getTargetGraph().get(target);
  }

  private void addNode(TargetNode<?, ?> node) {
    Preconditions.checkArgument(
        !TargetGraphVersionTransformations.getVersionedNode(node).isPresent(),
        "%s",
        node);
    graph.addNode(node);
  }

  private void addEdge(TargetNode<?, ?> src, TargetNode<?, ?> dst) {
    Preconditions.checkArgument(
        !TargetGraphVersionTransformations.getVersionedNode(src).isPresent());
    Preconditions.checkArgument(
        !TargetGraphVersionTransformations.getVersionedNode(dst).isPresent());
    graph.addEdge(src, dst);
  }

  /**
   * Get/cache the transitive version info for this node.
   */
  private VersionInfo getVersionInfo(TargetNode<?, ?> node) {
    VersionInfo info = this.versionInfo.get(node.getBuildTarget());
    if (info != null) {
      return info;
    }

    Map<BuildTarget, ImmutableSet<Version>> versionDomain = new HashMap<>();

    Optional<TargetNode<VersionedAlias.Arg, ?>> versionedNode =
        TargetGraphVersionTransformations.getVersionedNode(node);
    if (versionedNode.isPresent()) {
      ImmutableMap<Version, BuildTarget> versions =
          versionedNode.get().getConstructorArg().versions;

      // Merge in the versioned deps and the version domain.
      versionDomain.put(node.getBuildTarget(), versions.keySet());

      // If this version has only one possible choice, there's no need to wrap the constraints from
      // it's transitive deps in an implication constraint.
      if (versions.size() == 1) {
        Map.Entry<Version, BuildTarget> ent = versions.entrySet().iterator().next();
        VersionInfo depInfo = getVersionInfo(getNode(ent.getValue()));
        versionDomain.putAll(depInfo.getVersionDomain());
      } else {

        // For each version choice, inherit the transitive constraints by wrapping them in an
        // implication dependent on the specific version that pulls them in.
        for (Map.Entry<Version, BuildTarget> ent : versions.entrySet()) {
          VersionInfo depInfo = getVersionInfo(getNode(ent.getValue()));
          versionDomain.putAll(depInfo.getVersionDomain());
        }
      }
    } else {

      // Merge in the constraints and version domain/deps from transitive deps.
      for (BuildTarget depTarget : TargetGraphVersionTransformations.getDeps(node)) {
        TargetNode<?, ?> dep = getNode(depTarget);
        if (TargetGraphVersionTransformations.isVersionPropagator(dep) ||
            TargetGraphVersionTransformations.getVersionedNode(dep).isPresent()) {
          VersionInfo depInfo = getVersionInfo(dep);
          versionDomain.putAll(depInfo.getVersionDomain());
        }
      }
    }

    info = VersionInfo.of(versionDomain);

    this.versionInfo.put(node.getBuildTarget(), info);
    return info;
  }

  /**
   * @return a flavor to which summarizes the given version selections.
   */
  static Flavor getVersionedFlavor(SortedMap<BuildTarget, Version> versions) {
    Preconditions.checkArgument(!versions.isEmpty());
    Hasher hasher = Hashing.md5().newHasher();
    for (Map.Entry<BuildTarget, Version> ent : versions.entrySet()) {
      hasher.putString(ent.getKey().toString(), Charsets.UTF_8);
      hasher.putString(ent.getValue().getName(), Charsets.UTF_8);
    }
    return ImmutableFlavor.of("v" + hasher.hash().toString().substring(0, 7));
  }

  private TargetNode<?, ?> resolveVersions(
      TargetNode<?, ?> node,
      ImmutableMap<BuildTarget, Version> selectedVersions) {
    Optional<TargetNode<VersionedAlias.Arg, ?>> versionedNode =
        node.castArg(VersionedAlias.Arg.class);
    if (versionedNode.isPresent()) {
      node =
          getNode(
              Preconditions.checkNotNull(
                  versionedNode.get().getConstructorArg().versions.get(
                      selectedVersions.get(node.getBuildTarget()))));
    }
    return node;
  }

  /**
   * @return the {@link BuildTarget} to use in the resolved target graph, formed by adding a
   *         flavor generated from the given version selections.
   */
  private Optional<BuildTarget> getTranslateBuildTarget(
      TargetNode<?, ?> node,
      ImmutableMap<BuildTarget, Version> selectedVersions) {

    BuildTarget originalTarget = node.getBuildTarget();
    node = resolveVersions(node, selectedVersions);
    BuildTarget newTarget = node.getBuildTarget();

    if (TargetGraphVersionTransformations.isVersionPropagator(node)) {
      VersionInfo info = getVersionInfo(node);
      Collection<BuildTarget> versionedDeps = info.getVersionDomain().keySet();
      TreeMap<BuildTarget, Version> versions = new TreeMap<>();
      for (BuildTarget depTarget : versionedDeps) {
        versions.put(depTarget, selectedVersions.get(depTarget));
      }
      if (!versions.isEmpty()) {
        Flavor versionedFlavor = getVersionedFlavor(versions);
        newTarget = node.getBuildTarget().withAppendedFlavors(versionedFlavor);
      }
    }

    return newTarget.equals(originalTarget) ?
        Optional.empty() :
        Optional.of(newTarget);
  }

  public TargetGraph build() throws VersionException, InterruptedException {
    LOG.debug(
        "Starting version target graph transformation (nodes %d)",
        unversionedTargetGraphAndBuildTargets.getTargetGraph().getNodes().size());
    long start = System.currentTimeMillis();

    // Walk through explicit built targets, separating them into root and non-root nodes.
    ImmutableList.Builder<TargetNode<?, ?>> rootNodesBuilder = ImmutableList.builder();
    ImmutableList.Builder<TargetNode<?, ?>> nonRootNodesBuilder = ImmutableList.builder();
    for (BuildTarget root : unversionedTargetGraphAndBuildTargets.getBuildTargets()) {
      TargetNode<?, ?> node = getNode(root);
      if (TargetGraphVersionTransformations.isVersionRoot(node)) {
        rootNodesBuilder.add(node);
      } else {
        nonRootNodesBuilder.add(node);
      }
    }
    ImmutableList<TargetNode<?, ?>> rootNodes = rootNodesBuilder.build();
    ImmutableList<TargetNode<?, ?>> nonRootNodes = nonRootNodesBuilder.build();

    List<Action> actions =
        new ArrayList<>(unversionedTargetGraphAndBuildTargets.getBuildTargets().size());

    // Kick off the jobs to process the root nodes.
    for (TargetNode<?, ?> root : rootNodes) {
      RootAction action = new RootAction(root);
      actions.add(action);
      rootActions.put(root.getBuildTarget(), action);
    }

    // Kick off jobs to process batches of non-root nodes.
    for (int i = 0; i < nonRootNodes.size(); i += NON_ROOT_NODE_PACK_SIZE) {
      actions.add(
          new NodePackAction(
              nonRootNodes.subList(i, Math.min(i + NON_ROOT_NODE_PACK_SIZE, nonRootNodes.size()))));
    }

    // Wait for actions to finish.
    for (Action action : actions) {
      pool.submit(action);
    }
    for (Action action : actions) {
      action.getChecked();
    }

    long end = System.currentTimeMillis();
    LOG.debug(
        "Finished version target graph transformation in %.2f (nodes %d, roots: %d)",
        (end - start) / 1000.0,
        index.size(),
        roots.get());

    return new TargetGraph(
        graph,
        ImmutableMap.copyOf(index),
        ImmutableSet.of());
  }

  public static TargetGraphAndBuildTargets transform(
      VersionSelector versionSelector,
      TargetGraphAndBuildTargets unversionedTargetGraphAndBuildTargets,
      ForkJoinPool pool)
      throws VersionException, InterruptedException {
    return unversionedTargetGraphAndBuildTargets.withTargetGraph(
        new VersionedTargetGraphBuilder(
            pool,
            versionSelector,
            unversionedTargetGraphAndBuildTargets)
            .build());
  }

  /**
   * An action for transforming nodes.
   */
  private abstract class Action extends RecursiveAction {

    /**
     * Process a non-root node in the graph.
     */
    protected TargetNode<?, ?> processNode(TargetNode<?, ?> node) throws VersionException {

      // If we've already processed this node, exit now.
      TargetNode<?, ?> processed = index.get(node.getBuildTarget());
      if (processed != null) {
        return processed;
      }

      // Add the node to the graph and recurse on its deps.
      TargetNode<?, ?> oldNode = index.putIfAbsent(node.getBuildTarget(), node);
      if (oldNode != null) {
        node = oldNode;
      } else {
        addNode(node);
        for (TargetNode<?, ?> dep : process(node.getDeps())) {
          addEdge(node, dep);
        }
      }

      return node;
    }

    /**
     * Dispatch new jobs to transform the given nodes in parallel and wait for their results.
     */
    protected Iterable<TargetNode<?, ?>> process(Iterable<BuildTarget> targets)
        throws VersionException {
      int size = Iterables.size(targets);
      List<RootAction> newActions = new ArrayList<>(size);
      List<RootAction> oldActions = new ArrayList<>(size);
      List<TargetNode<?, ?>> nonRootNodes = new ArrayList<>(size);
      for (BuildTarget target : targets) {
        TargetNode<?, ?> node = getNode(target);

        // If we see a root node, create an action to process it using the pool, since it's
        // potentially heavy-weight.
        if (TargetGraphVersionTransformations.isVersionRoot(node)) {
          RootAction oldAction = rootActions.get(target);
          if (oldAction != null) {
            oldActions.add(oldAction);
          } else {
            RootAction newAction = new RootAction(getNode(target));
            oldAction = rootActions.putIfAbsent(target, newAction);
            if (oldAction == null) {
              newActions.add(newAction);
            } else {
              oldActions.add(oldAction);
            }
          }

        } else {
          nonRootNodes.add(node);
        }
      }

      // Kick off all new rootActions in parallel.
      invokeAll(newActions);

      // For non-root nodes, just process them in-place, as they are inexpensive.
      for (TargetNode<?, ?> node : nonRootNodes) {
        processNode(node);
      }

      // Wait for any existing rootActions to finish.
      for (RootAction action : oldActions) {
        action.join();
      }

      // Now that everything is ready, return all the results.
      return StreamSupport.stream(targets.spliterator(), false)
          .map(index::get)
          .collect(MoreCollectors.toImmutableList());
    }

    protected Void getChecked() throws VersionException, InterruptedException {
      try {
        return get();
      } catch (ExecutionException e) {
        Throwable rootCause = Throwables.getRootCause(e);
        Throwables.propagateIfInstanceOf(rootCause, VersionException.class);
        Throwables.propagateIfInstanceOf(rootCause, RuntimeException.class);
        throw new IllegalStateException(
            String.format("Unexpected exception: %s: %s", e.getClass(), e.getMessage()),
            e);
      }
    }

  }

  /**
   * Transform a version sub-graph at the given root node.
   */
  private class RootAction extends Action {

    private final TargetNode<?, ?> node;

    public RootAction(TargetNode<?, ?> node) {
      this.node = node;
    }

    private final Predicate<BuildTarget> isVersionPropagator =
        target -> TargetGraphVersionTransformations.isVersionPropagator(getNode(target));

    private final Predicate<BuildTarget> isVersioned =
        target -> TargetGraphVersionTransformations.getVersionedNode(getNode(target)).isPresent();

    @SuppressWarnings("unchecked")
    private TargetNode<?, ?> processVersionSubGraphNode(
        TargetNode<?, ?> node,
        ImmutableMap<BuildTarget, Version> selectedVersions,
        TargetNodeTranslator targetTranslator)
        throws VersionException {

      BuildTarget newTarget =
          targetTranslator.translateBuildTarget(node.getBuildTarget())
              .orElse(node.getBuildTarget());
      TargetNode<?, ?> processed = index.get(newTarget);
      if (processed != null) {
        return processed;
      }

      // Create the new target node, with the new target and deps.
      TargetNode<?, ?> newNode =
          ((Optional<TargetNode<?, ?>>) (Optional<?>) targetTranslator.translateNode(node))
              .orElse(node);

      LOG.verbose(
          "%s: new node declared deps %s, extra deps %s, arg %s",
          newNode.getBuildTarget(),
          newNode.getDeclaredDeps(),
          newNode.getExtraDeps(),
          newNode.getConstructorArg());

      // Add the new node, and it's dep edges, to the new graph.
      TargetNode<?, ?> oldNode = index.putIfAbsent(newTarget, newNode);
      if (oldNode != null) {
        newNode = oldNode;
      } else {
        addNode(newNode);
        for (BuildTarget depTarget :
             FluentIterable.from(node.getDeps())
                 .filter(Predicates.or(isVersionPropagator, isVersioned))) {
          addEdge(
              newNode,
              processVersionSubGraphNode(
                  resolveVersions(getNode(depTarget), selectedVersions),
                  selectedVersions,
                  targetTranslator));
        }
        for (TargetNode<?, ?> dep :
             process(
                 FluentIterable.from(node.getDeps())
                     .filter(Predicates.not(Predicates.or(isVersionPropagator, isVersioned))))) {
          addEdge(newNode, dep);
        }
      }

      return newNode;
    }

    // Transform a root node and its version sub-graph.
    private TargetNode<?, ?> processRoot(TargetNode<?, ?> root) throws VersionException {

      // If we've already processed this root, exit now.
      final TargetNode<?, ?> processedRoot = index.get(root.getBuildTarget());
      if (processedRoot != null) {
        return processedRoot;
      }

      // For stats collection.
      roots.incrementAndGet();

      VersionInfo versionInfo = getVersionInfo(root);

      // Select the versions to use for this sub-graph.
      final ImmutableMap<BuildTarget, Version> selectedVersions =
          versionSelector.resolve(
              root.getBuildTarget(),
              versionInfo.getVersionDomain());

      // Build a target translator object to translate build targets.
      TargetNodeTranslator targetTranslator =
          new TargetNodeTranslator() {

            private final LoadingCache<BuildTarget, Optional<BuildTarget>> cache =
                CacheBuilder.newBuilder()
                    .build(
                        CacheLoader.from(
                            target -> getTranslateBuildTarget(getNode(target), selectedVersions)));

            @Override
            public Optional<BuildTarget> translateBuildTarget(BuildTarget target) {
              return cache.getUnchecked(target);
            }

            @Override
            public Optional<ImmutableMap<BuildTarget, Version>> getSelectedVersions(
                BuildTarget target) {
              ImmutableMap.Builder<BuildTarget, Version> builder = ImmutableMap.builder();
              for (BuildTarget dep : getVersionInfo(getNode(target)).getVersionDomain().keySet()) {
                builder.put(dep, selectedVersions.get(dep));
              }
              return Optional.of(builder.build());
            }

          };

      return processVersionSubGraphNode(root, selectedVersions, targetTranslator);
    }

    @Override
    protected void compute() {
      try {
        processRoot(node);
      } catch (VersionException e) {
        completeExceptionally(e);
      }
    }

  }

  /**
   * Transform a group of nodes.
   */
  private class NodePackAction extends Action {

    private final Iterable<TargetNode<?, ?>> nodes;

    public NodePackAction(Iterable<TargetNode<?, ?>> nodes) {
      this.nodes = nodes;
    }

    @Override
    protected void compute() {
      try {
        for (TargetNode<?, ?> node : nodes) {
          processNode(node);
        }
      } catch (VersionException e) {
        completeExceptionally(e);
      }
    }

  }

}
