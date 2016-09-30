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
import com.facebook.buck.rules.TargetGroup;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;

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
  private final MutableDirectedGraph<TargetNode<?>> graph;

  /**
   * Map of the build targets to nodes in the resolved graph.
   */
  private final ConcurrentHashMap<BuildTarget, TargetNode<?>> index;

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

  private TargetNode<?> getNode(BuildTarget target) {
    return unversionedTargetGraphAndBuildTargets.getTargetGraph().get(target);
  }

  /**
   * Get/cache the transitive version info for this node.
   */
  private VersionInfo getVersionInfo(TargetNode<?> node) {
    VersionInfo info = this.versionInfo.get(node.getBuildTarget());
    if (info != null) {
      return info;
    }

    Map<BuildTarget, ImmutableSet<Version>> versionDomain = new HashMap<>();

    Optional<TargetNode<VersionedAlias.Arg>> versionedNode =
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
        TargetNode<?> dep = getNode(depTarget);
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

  /**
   * @return a copy of the given {@link TargetNode} suitable for the resolved target graph, using
   *         the update dependencies and a version flavor, if necessary.
   */
  private <A> TargetNode<A> cloneNode(
      TargetNode<A> node,
      ImmutableSet<Flavor> flavors,
      ImmutableSortedSet<BuildTarget> declaredDeps) {

    // Fast path for avoiding node cloning.
    ImmutableMap<BuildTarget, Optional<Constraint>> versionedDeps =
        TargetGraphVersionTransformations.getVersionedDeps(node);
    if (node.getBuildTarget().getFlavors().equals(flavors) &&
        node.getDeclaredDeps().equals(declaredDeps) &&
        versionedDeps.keySet().isEmpty()) {
      return node;
    }

    // Generate the new constructor arg from the original
    A constructorArg = node.getConstructorArg();
    A newConstructorArg = node.getDescription().createUnpopulatedConstructorArg();
    for (Field field : constructorArg.getClass().getFields()) {
      try {
        // Update the `dep` parameter with the new declared deps.
        if (field.getName().equals("deps")) {
          field.set(newConstructorArg, Optional.of(declaredDeps));
        // Clear-out the versioned deps field, as we only use this for generating the resolved
        // graph and we want to avoid the version alias nodes showing up in the new nodes extra
        // deps.
        } else if (
            field.getName().equals(TargetGraphVersionTransformations.VERSIONED_DEPS_FIELD_NAME)) {
          field.set(
              newConstructorArg,
              Optional.of(ImmutableSortedMap.<BuildTarget, Optional<Constraint>>of()));
        // Use all other fields as-is.
        } else {
          field.set(newConstructorArg, field.get(constructorArg));
        }
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }

    // Generate the new extra deps.
    ImmutableSet<BuildTarget> newExtraDeps =
        ImmutableSet.copyOf(Sets.difference(node.getExtraDeps(), versionedDeps.keySet()));

    // Build the new node and return it.
    return node.withConstructorArgFlavorsAndDeps(
        newConstructorArg,
        flavors,
        declaredDeps,
        newExtraDeps);
  }

  public TargetGraph build() throws VersionException, InterruptedException {
    LOG.debug(
        "Starting version target graph transformation (nodes %d)",
        unversionedTargetGraphAndBuildTargets.getTargetGraph().getNodes().size());
    long start = System.currentTimeMillis();

    // Walk through explicit built targets, separating them into root and non-root nodes.
    ImmutableList.Builder<TargetNode<?>> rootNodesBuilder = ImmutableList.builder();
    ImmutableList.Builder<TargetNode<?>> nonRootNodesBuilder = ImmutableList.builder();
    for (BuildTarget root : unversionedTargetGraphAndBuildTargets.getBuildTargets()) {
      TargetNode<?> node = getNode(root);
      if (TargetGraphVersionTransformations.isVersionRoot(node)) {
        rootNodesBuilder.add(node);
      } else {
        nonRootNodesBuilder.add(node);
      }
    }
    ImmutableList<TargetNode<?>> rootNodes = rootNodesBuilder.build();
    ImmutableList<TargetNode<?>> nonRootNodes = nonRootNodesBuilder.build();

    List<Action> actions =
        new ArrayList<>(unversionedTargetGraphAndBuildTargets.getBuildTargets().size());

    // Kick off the jobs to process the root nodes.
    for (TargetNode<?> root : rootNodes) {
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
        ImmutableSet.<TargetGroup>of());
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
    protected TargetNode<?> processNode(TargetNode<?> node) throws VersionException {

      // If we've already processed this node, exit now.
      TargetNode<?> processed = index.get(node.getBuildTarget());
      if (processed != null) {
        return processed;
      }

      // Add the node to the graph and recurse on its deps.
      TargetNode<?> oldNode = index.putIfAbsent(node.getBuildTarget(), node);
      if (oldNode != null) {
        node = oldNode;
      } else {
        graph.addNode(node);
        for (TargetNode<?> dep : process(node.getDeps())) {
          graph.addEdge(node, dep);
        }
      }

      return node;
    }

    /**
     * Dispatch new jobs to transform the given nodes in parallel and wait for their results.
     */
    protected Iterable<TargetNode<?>> process(Iterable<BuildTarget> targets)
        throws VersionException {
      int size = Iterables.size(targets);
      List<RootAction> newActions = new ArrayList<>(size);
      List<RootAction> oldActions = new ArrayList<>(size);
      List<TargetNode<?>> nonRootNodes = new ArrayList<>(size);
      for (BuildTarget target : targets) {
        TargetNode<?> node = getNode(target);

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
      for (TargetNode<?> node : nonRootNodes) {
        processNode(node);
      }

      // Wait for any existing rootActions to finish.
      for (RootAction action : oldActions) {
        action.join();
      }

      // Now that everything is ready, return all the results.
      return Iterables.transform(targets, Functions.forMap(index));
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

    private final TargetNode<?> node;

    public RootAction(TargetNode<?> node) {
      this.node = node;
    }

    /**
     * @return the {@link BuildTarget} to use in the resolved target graph, formed by adding a
     *         flavor generated from the given version selections.
     */
    private BuildTarget getNewTarget(
        TargetNode<?> node,
        ImmutableMap<BuildTarget, Version> selectedVersions) {

      BuildTarget target = node.getBuildTarget();
      if (!TargetGraphVersionTransformations.isVersionRoot(node)) {
        VersionInfo info = getVersionInfo(node);
        Collection<BuildTarget> versionedDeps = info.getVersionDomain().keySet();
        TreeMap<BuildTarget, Version> versions = new TreeMap<>();
        for (BuildTarget depTarget : versionedDeps) {
          versions.put(depTarget, selectedVersions.get(depTarget));
        }
        if (!versions.isEmpty()) {
          // transform build target.
          Flavor versionedFlavor = getVersionedFlavor(versions);
          target = target.withAppendedFlavors(versionedFlavor);
        }
      }

      return target;
    }

    private TargetNode<?> processVersionSubGraphNode(
        TargetNode<?> node,
        final ImmutableMap<BuildTarget, Version> selectedVersions)
        throws VersionException {

      BuildTarget newTarget = getNewTarget(node, selectedVersions);
      TargetNode<?> processed = index.get(newTarget);
      if (processed != null) {
        return processed;
      }

      // Translate the current node's declared deps to the new ones.
      ImmutableList.Builder<BuildTarget> newInternalDeclaredDepsBuilder = ImmutableList.builder();
      ImmutableList.Builder<BuildTarget> newExternalDeclaredDepsBuilder = ImmutableList.builder();
      for (BuildTarget depTarget : node.getDeclaredDeps()) {
        TargetNode<?> dep = getNode(depTarget);
        Optional<TargetNode<VersionedAlias.Arg>> versionedDep =
            TargetGraphVersionTransformations.getVersionedNode(dep);
        if (versionedDep.isPresent()) {
          depTarget =
              Preconditions.checkNotNull(
                  versionedDep.get().getConstructorArg().versions.get(
                      selectedVersions.get(depTarget)));
        }
        if (TargetGraphVersionTransformations.isVersionPropagator(dep)) {
          newInternalDeclaredDepsBuilder.add(depTarget);
        } else {
          newExternalDeclaredDepsBuilder.add(depTarget);
        }
      }
      for (BuildTarget depTarget :
           TargetGraphVersionTransformations.getVersionedDeps(node).keySet()) {
        TargetNode<?> dep = getNode(depTarget);
        Optional<TargetNode<VersionedAlias.Arg>> versionedDep =
            TargetGraphVersionTransformations.getVersionedNode(dep);
        Preconditions.checkState(versionedDep.isPresent());
        depTarget =
            versionedDep.get().getConstructorArg().versions.get(
                selectedVersions.get(depTarget));
        newInternalDeclaredDepsBuilder.add(depTarget);
      }
      ImmutableList<BuildTarget> newInternalDeclaredDeps = newInternalDeclaredDepsBuilder.build();
      ImmutableList<BuildTarget> newExternalDeclaredDeps = newExternalDeclaredDepsBuilder.build();

      // Create the new target node, with the new target and deps.
      TargetNode<?> newNode =
          cloneNode(
              node,
              newTarget.getFlavors(),
              ImmutableSortedSet.<BuildTarget>naturalOrder()
                  .addAll(
                      FluentIterable.from(newInternalDeclaredDeps)
                          .transform(
                              new Function<BuildTarget, BuildTarget>() {
                                @Override
                                public BuildTarget apply(BuildTarget depTarget) {
                                  return getNewTarget(getNode(depTarget), selectedVersions);
                                }
                              }))
                  .addAll(newExternalDeclaredDeps)
                  .build());

      // Add the new node, and it's dep edges, to the new graph.
      TargetNode<?> oldNode = index.putIfAbsent(newTarget, newNode);
      if (oldNode != null) {
        newNode = oldNode;
      } else {
        graph.addNode(newNode);
        for (BuildTarget depTarget : newInternalDeclaredDeps) {
          graph.addEdge(
              newNode,
              processVersionSubGraphNode(getNode(depTarget), selectedVersions));
        }
        for (TargetNode<?> dep :
            process(
                Iterables.concat(newExternalDeclaredDeps, newNode.getExtraDeps()))) {
          graph.addEdge(newNode, dep);
        }
      }

      return newNode;
    }

    // Transform a root node and its version sub-graph.
    private TargetNode<?> processRoot(TargetNode<?> root) throws VersionException {

      // If we've already processed this root, exit now.
      final TargetNode<?> processedRoot = index.get(root.getBuildTarget());
      if (processedRoot != null) {
        return processedRoot;
      }

      // For stats collection.
      roots.incrementAndGet();

      VersionInfo versionInfo = getVersionInfo(root);

      // Select the versions to use for this sub-graph.
      ImmutableMap<BuildTarget, Version> selectedVersions =
          versionSelector.resolve(
              root.getBuildTarget(),
              versionInfo.getVersionDomain());

      return processVersionSubGraphNode(root, selectedVersions);
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

    private final Iterable<TargetNode<?>> nodes;

    public NodePackAction(Iterable<TargetNode<?>> nodes) {
      this.nodes = nodes;
    }

    @Override
    protected void compute() {
      try {
        for (TargetNode<?> node : nodes) {
          processNode(node);
        }
      } catch (VersionException e) {
        completeExceptionally(e);
      }
    }

  }

}
