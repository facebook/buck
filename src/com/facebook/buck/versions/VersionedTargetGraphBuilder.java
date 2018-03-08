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

import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
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
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

/**
 * Takes a regular {@link TargetGraph}, resolves any versioned nodes, and returns a new graph with
 * the versioned nodes removed.
 */
public class VersionedTargetGraphBuilder {

  private static final long TIMEOUT = 20;
  private static final TimeUnit UNIT = TimeUnit.SECONDS;

  private static final Logger LOG = Logger.get(VersionedTargetGraphBuilder.class);

  private final ForkJoinPool pool;
  private final VersionSelector versionSelector;
  private final TargetGraphAndBuildTargets unversionedTargetGraphAndBuildTargets;
  private final TypeCoercerFactory typeCoercerFactory;

  /** The resolved version graph being built. */
  private final VersionedTargetGraph.Builder targetGraphBuilder = VersionedTargetGraph.builder();

  /** Map of the build targets to nodes in the resolved graph. */
  private final ConcurrentHashMap<BuildTarget, TargetNode<?, ?>> index;

  /** Fork-join actions for each root node. */
  private final ConcurrentHashMap<BuildTarget, ForkJoinTask<?>> rootActions;

  /** Intermediate version info for each node. */
  private final ConcurrentHashMap<BuildTarget, VersionInfo> versionInfo;

  /** Count of root nodes. */
  private final AtomicInteger roots = new AtomicInteger();

  VersionedTargetGraphBuilder(
      ForkJoinPool pool,
      VersionSelector versionSelector,
      TargetGraphAndBuildTargets unversionedTargetGraphAndBuildTargets,
      TypeCoercerFactory typeCoercerFactory) {

    this.pool = pool;
    this.versionSelector = versionSelector;
    this.unversionedTargetGraphAndBuildTargets = unversionedTargetGraphAndBuildTargets;
    this.typeCoercerFactory = typeCoercerFactory;

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

  private Optional<TargetNode<?, ?>> getNodeOptional(BuildTarget target) {
    return unversionedTargetGraphAndBuildTargets.getTargetGraph().getOptional(target);
  }

  private TargetNode<?, ?> indexPutIfAbsent(TargetNode<?, ?> node) {
    return index.putIfAbsent(node.getBuildTarget(), node);
  }

  /** Get/cache the transitive version info for this node. */
  private VersionInfo getVersionInfo(TargetNode<?, ?> node) {
    VersionInfo info = this.versionInfo.get(node.getBuildTarget());
    if (info != null) {
      return info;
    }

    Map<BuildTarget, ImmutableSet<Version>> versionDomain = new HashMap<>();

    Optional<TargetNode<VersionedAliasDescriptionArg, ?>> versionedNode =
        TargetGraphVersionTransformations.getVersionedNode(node);
    if (versionedNode.isPresent()) {
      ImmutableMap<Version, BuildTarget> versions =
          versionedNode.get().getConstructorArg().getVersions();

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
      for (BuildTarget depTarget :
          TargetGraphVersionTransformations.getDeps(typeCoercerFactory, node)) {
        TargetNode<?, ?> dep = getNode(depTarget);
        if (TargetGraphVersionTransformations.isVersionPropagator(dep)
            || TargetGraphVersionTransformations.getVersionedNode(dep).isPresent()) {
          VersionInfo depInfo = getVersionInfo(dep);
          versionDomain.putAll(depInfo.getVersionDomain());
        }
      }
    }

    info = VersionInfo.of(versionDomain);

    this.versionInfo.put(node.getBuildTarget(), info);
    return info;
  }

  /** @return a flavor to which summarizes the given version selections. */
  static Flavor getVersionedFlavor(SortedMap<BuildTarget, Version> versions) {
    Preconditions.checkArgument(!versions.isEmpty());
    Hasher hasher = Hashing.md5().newHasher();
    for (Map.Entry<BuildTarget, Version> ent : versions.entrySet()) {
      hasher.putString(ent.getKey().toString(), Charsets.UTF_8);
      hasher.putString(ent.getValue().getName(), Charsets.UTF_8);
    }
    return InternalFlavor.of("v" + hasher.hash().toString().substring(0, 7));
  }

  private TargetNode<?, ?> resolveVersions(
      TargetNode<?, ?> node, ImmutableMap<BuildTarget, Version> selectedVersions) {
    Optional<TargetNode<VersionedAliasDescriptionArg, ?>> versionedNode =
        node.castArg(VersionedAliasDescriptionArg.class);
    if (versionedNode.isPresent()) {
      node =
          getNode(
              Preconditions.checkNotNull(
                  versionedNode
                      .get()
                      .getConstructorArg()
                      .getVersions()
                      .get(selectedVersions.get(node.getBuildTarget()))));
    }
    return node;
  }

  /**
   * @return the {@link BuildTarget} to use in the resolved target graph, formed by adding a flavor
   *     generated from the given version selections.
   */
  private Optional<BuildTarget> getTranslateBuildTarget(
      TargetNode<?, ?> node, ImmutableMap<BuildTarget, Version> selectedVersions) {

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

    return newTarget.equals(originalTarget) ? Optional.empty() : Optional.of(newTarget);
  }

  public TargetGraph build() throws VersionException, TimeoutException, InterruptedException {
    LOG.debug(
        "Starting version target graph transformation (nodes %d)",
        unversionedTargetGraphAndBuildTargets.getTargetGraph().getNodes().size());
    long start = System.currentTimeMillis();

    // Walk through explicit built targets, separating them into root and non-root nodes.
    ImmutableList<RootAction> actions =
        unversionedTargetGraphAndBuildTargets
            .getBuildTargets()
            .stream()
            .map(this::getNode)
            .map(RootAction::new)
            .collect(ImmutableList.toImmutableList());

    // Add actions to the `rootActions` member for bookkeeping.
    actions.forEach(a -> rootActions.put(a.getRoot().getBuildTarget(), a));

    // Kick off the jobs to process the root nodes.
    actions.forEach(pool::submit);

    // Wait for actions to complete.
    for (RootAction action : actions) {
      action.getChecked();
    }

    long end = System.currentTimeMillis();
    LOG.debug(
        "Finished version target graph transformation in %.2f (nodes %d, roots: %d)",
        (end - start) / 1000.0, index.size(), roots.get());

    return targetGraphBuilder.build();
  }

  public static TargetGraphAndBuildTargets transform(
      VersionSelector versionSelector,
      TargetGraphAndBuildTargets unversionedTargetGraphAndBuildTargets,
      ForkJoinPool pool,
      TypeCoercerFactory typeCoercerFactory)
      throws VersionException, TimeoutException, InterruptedException {
    return unversionedTargetGraphAndBuildTargets.withTargetGraph(
        new VersionedTargetGraphBuilder(
                pool, versionSelector, unversionedTargetGraphAndBuildTargets, typeCoercerFactory)
            .build());
  }

  /** Transform a version sub-graph at the given root node. */
  private class RootAction extends RecursiveAction {

    private final TargetNode<?, ?> node;

    RootAction(TargetNode<?, ?> node) {
      this.node = node;
    }

    private final Predicate<BuildTarget> isVersionPropagator =
        target -> TargetGraphVersionTransformations.isVersionPropagator(getNode(target));

    private final Predicate<BuildTarget> isVersioned =
        target -> TargetGraphVersionTransformations.getVersionedNode(getNode(target)).isPresent();

    /** Process a non-root node in the graph. */
    private TargetNode<?, ?> processNode(TargetNode<?, ?> node)
        throws VersionException, TimeoutException {

      // If we've already processed this node, exit now.
      TargetNode<?, ?> processed = index.get(node.getBuildTarget());
      if (processed != null) {
        return processed;
      }

      // Add the node to the graph and recurse on its deps.
      TargetNode<?, ?> oldNode = indexPutIfAbsent(node);
      if (oldNode != null) {
        node = oldNode;
      } else {
        targetGraphBuilder.addNode(node.getBuildTarget().withFlavors(), node);
        for (TargetNode<?, ?> dep : process(node.getParseDeps())) {
          targetGraphBuilder.addEdge(node, dep);
        }
      }

      return node;
    }

    /** Dispatch new jobs to transform the given nodes in parallel and wait for their results. */
    private Iterable<TargetNode<?, ?>> process(Iterable<BuildTarget> targets)
        throws VersionException, TimeoutException {
      int size = Iterables.size(targets);
      List<ForkJoinTask<?>> rootNodes = new ArrayList<>(size);
      List<TargetNode<?, ?>> nonRootNodes = new ArrayList<>(size);
      for (BuildTarget target : targets) {
        TargetNode<?, ?> node = getNode(target);

        // If we see a root node, create an action to process it using the pool, since it's
        // potentially heavy-weight.
        if (TargetGraphVersionTransformations.isVersionRoot(node)) {
          rootNodes.add(rootActions.computeIfAbsent(target, t -> new RootAction(node).fork()));

        } else {
          nonRootNodes.add(node);
        }
      }

      // For non-root nodes, just process them in-place, as they are inexpensive.
      for (TargetNode<?, ?> node : nonRootNodes) {
        processNode(node);
      }

      // Wait for any existing rootActions to finish.
      for (ForkJoinTask<?> action : rootNodes) {
        try {
          action.get(TIMEOUT, UNIT);
        } catch (ExecutionException e) {
          throw new RuntimeException(e);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      }

      // Now that everything is ready, return all the results.
      return StreamSupport.stream(targets.spliterator(), false)
          .map(index::get)
          .collect(ImmutableList.toImmutableList());
    }

    public Void getChecked() throws VersionException, TimeoutException, InterruptedException {
      try {
        return get();
      } catch (ExecutionException e) {
        Throwable rootCause = Throwables.getRootCause(e);
        Throwables.throwIfInstanceOf(rootCause, VersionException.class);
        Throwables.throwIfInstanceOf(rootCause, TimeoutException.class);
        Throwables.throwIfInstanceOf(rootCause, RuntimeException.class);
        throw new IllegalStateException(
            String.format("Unexpected exception: %s: %s", e.getClass(), e.getMessage()), e);
      }
    }

    @SuppressWarnings("unchecked")
    private TargetNode<?, ?> processVersionSubGraphNode(
        TargetNode<?, ?> node,
        ImmutableMap<BuildTarget, Version> selectedVersions,
        TargetNodeTranslator targetTranslator)
        throws VersionException, TimeoutException {

      Optional<BuildTarget> newTarget =
          targetTranslator.translateBuildTarget(node.getBuildTarget());
      TargetNode<?, ?> processed = index.get(newTarget.orElse(node.getBuildTarget()));
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
      TargetNode<?, ?> oldNode = indexPutIfAbsent(newNode);
      if (oldNode != null) {
        newNode = oldNode;
      } else {
        // Insert the node into the graph, indexing it by a base target containing only the version
        // flavor, if one exists.
        targetGraphBuilder.addNode(
            node.getBuildTarget()
                .withFlavors(
                    Sets.difference(
                        newNode.getBuildTarget().getFlavors(), node.getBuildTarget().getFlavors())),
            newNode);
        for (BuildTarget depTarget :
            FluentIterable.from(node.getParseDeps())
                .filter(isVersionPropagator.or(isVersioned)::test)) {
          targetGraphBuilder.addEdge(
              newNode,
              processVersionSubGraphNode(
                  resolveVersions(getNode(depTarget), selectedVersions),
                  selectedVersions,
                  targetTranslator));
        }
        for (TargetNode<?, ?> dep :
            process(
                FluentIterable.from(node.getParseDeps())
                    .filter(isVersionPropagator.or(isVersioned).negate()::test))) {
          targetGraphBuilder.addEdge(newNode, dep);
        }
      }

      return newNode;
    }

    // Transform a root node and its version sub-graph.
    private TargetNode<?, ?> processRoot(TargetNode<?, ?> root)
        throws VersionException, TimeoutException {

      // If we've already processed this root, exit now.
      TargetNode<?, ?> processedRoot = index.get(root.getBuildTarget());
      if (processedRoot != null) {
        return processedRoot;
      }

      // For stats collection.
      roots.incrementAndGet();

      VersionInfo versionInfo = getVersionInfo(root);

      // Select the versions to use for this sub-graph.
      ImmutableMap<BuildTarget, Version> selectedVersions =
          versionSelector.resolve(root.getBuildTarget(), versionInfo.getVersionDomain());

      // Build a target translator object to translate build targets.
      ImmutableList<TargetTranslator<?>> translators =
          ImmutableList.of(new QueryTargetTranslator());
      TargetNodeTranslator targetTranslator =
          new TargetNodeTranslator(typeCoercerFactory, translators) {

            private final LoadingCache<BuildTarget, Optional<BuildTarget>> cache =
                CacheBuilder.newBuilder()
                    .build(
                        CacheLoader.from(
                            target -> {

                              // If we're handling the root node, there's nothing to translate.
                              if (root.getBuildTarget().equals(target)) {
                                return Optional.empty();
                              }

                              // If this target isn't in the target graph, which can be the case
                              // of build targets in the `tests` parameter, don't do any
                              // translation.
                              Optional<TargetNode<?, ?>> node = getNodeOptional(target);
                              if (!node.isPresent()) {
                                return Optional.empty();
                              }

                              return getTranslateBuildTarget(getNode(target), selectedVersions);
                            }));

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
      } catch (VersionException | TimeoutException e) {
        completeExceptionally(e);
      }
    }

    public TargetNode<?, ?> getRoot() {
      return node;
    }
  }
}
