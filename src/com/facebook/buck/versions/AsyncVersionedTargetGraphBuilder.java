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

package com.facebook.buck.versions;

import com.facebook.buck.core.graph.transformation.ComputationEnvironment;
import com.facebook.buck.core.graph.transformation.DefaultGraphTransformationEngine;
import com.facebook.buck.core.graph.transformation.GraphComputation;
import com.facebook.buck.core.graph.transformation.GraphComputationStage;
import com.facebook.buck.core.graph.transformation.GraphTransformationEngine;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetFactory;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Futures;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.immutables.value.Value;
import org.immutables.value.Value.Style.ImplementationVisibility;

/**
 * Takes a regular {@link TargetGraph}, resolves any versioned nodes, and returns a new graph with
 * the versioned nodes removed, transforming it asynchronously using {@link GraphComputation}.
 */
public class AsyncVersionedTargetGraphBuilder extends AbstractVersionedTargetGraphBuilder {

  private static final Logger LOG = Logger.get(AsyncVersionedTargetGraphBuilder.class);

  private final VersionedTargetGraphComputation versionedTargetGraphTransformer;

  private final GraphTransformationEngine asyncTransformationEngine;

  private final GraphTransformationEngine versionInfoAsyncTransformationEngine;

  AsyncVersionedTargetGraphBuilder(
      DepsAwareExecutor<? super ComputeResult, ?> executor,
      VersionSelector versionSelector,
      TargetGraphAndBuildTargets unversionedTargetGraphAndBuildTargets,
      TypeCoercerFactory typeCoercerFactory,
      UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory,
      long timeoutSeconds) {
    super(
        typeCoercerFactory,
        unconfiguredBuildTargetFactory,
        unversionedTargetGraphAndBuildTargets,
        timeoutSeconds,
        TimeUnit.SECONDS);

    this.versionedTargetGraphTransformer =
        new VersionedTargetGraphComputation(
            unversionedTargetGraphAndBuildTargets.getTargetGraph(), versionSelector);

    this.asyncTransformationEngine =
        new DefaultGraphTransformationEngine(
            ImmutableList.of(new GraphComputationStage<>(versionedTargetGraphTransformer)),
            unversionedTargetGraphAndBuildTargets.getTargetGraph().getSize() * 4,
            executor);
    this.versionInfoAsyncTransformationEngine =
        new DefaultGraphTransformationEngine(
            ImmutableList.of(
                new GraphComputationStage<>(
                    new TargetNodeToVersionInfoComputation(
                        unversionedTargetGraphAndBuildTargets.getTargetGraph()))),
            2 * unversionedTargetGraphAndBuildTargets.getTargetGraph().getSize(),
            DefaultDepsAwareExecutor.of(2));
  }

  @Override
  protected VersionInfo getVersionInfo(TargetNode<?> node) {
    return Futures.getUnchecked(
        versionInfoAsyncTransformationEngine.compute(ImmutableVersionInfoKey.of(node)));
  }

  @Override
  @SuppressWarnings("PMD")
  public TargetGraph build() throws TimeoutException, InterruptedException, VersionException {
    LOG.debug(
        "Starting version target graph transformation (nodes %d)",
        unversionedTargetGraphAndBuildTargets.getTargetGraph().getNodes().size());
    long start = System.currentTimeMillis();

    ImmutableSet<VersionTargetGraphKey> rootKeys =
        RichStream.from(
                unversionedTargetGraphAndBuildTargets
                    .getTargetGraph()
                    .getAll(unversionedTargetGraphAndBuildTargets.getBuildTargets()))
            .map(ImmutableVersionTargetGraphKey::of)
            .collect(ImmutableSet.toImmutableSet());

    ImmutableMap<VersionTargetGraphKey, Future<TargetNode<?>>> results =
        asyncTransformationEngine.computeAll(rootKeys);

    // Wait for actions to complete.
    for (Future<TargetNode<?>> futures : results.values()) {
      try {
        futures.get(timeout, timeUnit);
      } catch (ExecutionException e) {
        Throwable rootCause = Throwables.getRootCause(e);
        Throwables.throwIfInstanceOf(rootCause, VersionException.class);
        Throwables.throwIfInstanceOf(rootCause, TimeoutException.class);
        Throwables.throwIfInstanceOf(rootCause, RuntimeException.class);
        throw new IllegalStateException(
            String.format("Unexpected exception: %s: %s", e.getClass(), e.getMessage()), e);
      }
    }

    asyncTransformationEngine.close();
    versionInfoAsyncTransformationEngine.close();

    long end = System.currentTimeMillis();

    VersionedTargetGraph graph = versionedTargetGraphTransformer.targetGraphBuilder.build();
    LOG.debug(
        "Finished version target graph transformation in %.2f (nodes %d, roots: %d)",
        (end - start) / 1000.0, graph.getSize(), versionedTargetGraphTransformer.roots.get());

    return graph;
  }

  /** Transforms the given {@link TargetGraphAndBuildTargets} such that all versions are resolved */
  public static TargetGraphAndBuildTargets transform(
      VersionSelector versionSelector,
      TargetGraphAndBuildTargets unversionedTargetGraphAndBuildTargets,
      DepsAwareExecutor<? super ComputeResult, ?> executor,
      TypeCoercerFactory typeCoercerFactory,
      UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory,
      long timeoutSeconds)
      throws VersionException, TimeoutException, InterruptedException {
    return unversionedTargetGraphAndBuildTargets.withTargetGraph(
        new AsyncVersionedTargetGraphBuilder(
                executor,
                versionSelector,
                unversionedTargetGraphAndBuildTargets,
                typeCoercerFactory,
                unconfiguredBuildTargetFactory,
                timeoutSeconds)
            .build());
  }

  /**
   * Computes all the {@link VersionInfo} in the graph. TODO(bobyf) rewrite this as stages once
   * {@link GraphComputation} supports staging
   */
  @Value.Immutable(builder = false, copy = false, prehash = true)
  @Value.Style(visibility = ImplementationVisibility.PACKAGE)
  abstract static class VersionInfoKey implements ComputeKey<VersionInfo> {
    @Value.Parameter
    public abstract TargetNode<?> getTargetNode();

    @Override
    public Class<? extends ComputeKey<?>> getKeyClass() {
      return VersionInfoKey.class;
    }
  }

  private final class TargetNodeToVersionInfoComputation
      implements GraphComputation<VersionInfoKey, VersionInfo> {

    private final TargetGraph targetGraph;

    public TargetNodeToVersionInfoComputation(TargetGraph targetGraph) {
      this.targetGraph = targetGraph;
    }

    @Override
    public Class<VersionInfoKey> getKeyClass() {
      return VersionInfoKey.class;
    }

    @Override
    public VersionInfo transform(VersionInfoKey node, ComputationEnvironment env) {
      return getVersionInfo(node.getTargetNode(), env);
    }

    @Override
    public ImmutableSet<VersionInfoKey> discoverDeps(
        VersionInfoKey key, ComputationEnvironment env) {
      return ImmutableSet.of();
    }

    @Override
    public ImmutableSet<VersionInfoKey> discoverPreliminaryDeps(VersionInfoKey node) {
      TargetNode<?> targetNode = node.getTargetNode();
      Optional<TargetNode<VersionedAliasDescriptionArg>> versionedNode =
          TargetGraphVersionTransformations.getVersionedNode(targetNode);

      Iterable<BuildTarget> versionedDeps;
      if (versionedNode.isPresent()) {
        ImmutableMap<Version, BuildTarget> versions =
            versionedNode.get().getConstructorArg().getVersions();

        // Merge in the versioned deps and the version domain.

        // For each version choice, inherit the transitive constraints by wrapping them in an
        // implication dependent on the specific version that pulls them in.
        versionedDeps = versions.values();

      } else {
        Iterable<BuildTarget> deps =
            TargetGraphVersionTransformations.getDeps(typeCoercerFactory, targetNode);
        versionedDeps =
            Iterables.filter(
                deps,
                dep -> {
                  TargetNode<?> depNode = targetGraph.get(dep);
                  return TargetGraphVersionTransformations.isVersionPropagator(depNode)
                      || TargetGraphVersionTransformations.getVersionedNode(depNode).isPresent();
                });
      }
      return ImmutableSet.copyOf(
          Iterables.transform(
              versionedDeps,
              buildTarget -> ImmutableVersionInfoKey.of(targetGraph.get(buildTarget))));
    }

    /** Get/cache the transitive version info for this node. */
    private VersionInfo getVersionInfo(TargetNode<?> node, ComputationEnvironment env) {
      HashMap<BuildTarget, ImmutableSet<Version>> versionDomain = new HashMap<>();

      Optional<TargetNode<VersionedAliasDescriptionArg>> versionedNode =
          TargetGraphVersionTransformations.getVersionedNode(node);

      if (versionedNode.isPresent()) {
        ImmutableMap<Version, BuildTarget> versions =
            versionedNode.get().getConstructorArg().getVersions();
        versionDomain.put(node.getBuildTarget(), versions.keySet());
      }

      for (VersionInfo depInfo : env.getDeps(VersionInfoKey.class).values()) {
        versionDomain.putAll(depInfo.getVersionDomain());
      }
      return VersionInfo.of(versionDomain);
    }
  }

  /** Key used to request for resolved {@link TargetNode}s. */
  @Value.Immutable(builder = false, copy = false, prehash = true)
  abstract static class VersionTargetGraphKey implements ComputeKey<TargetNode<?>> {
    @Value.Parameter
    public abstract TargetNode<?> getTargetNode();

    @Value.Parameter
    public abstract Optional<ImmutableMap<BuildTarget, Version>> getSelectedVersions();

    @Value.Parameter
    @Value.Auxiliary
    public abstract Optional<TargetNodeTranslator> targetNodeTranslator();

    @Override
    public Class<? extends ComputeKey<?>> getKeyClass() {
      return VersionTargetGraphKey.class;
    }

    public static VersionTargetGraphKey of(TargetNode<?> node) {
      return ImmutableVersionTargetGraphKey.of(node, Optional.empty(), Optional.empty());
    }
  }

  /** Key used to request for {@link VersionInfo} */
  @Value.Immutable(builder = false, copy = false)
  @Value.Style(visibility = ImplementationVisibility.PACKAGE)
  abstract static class VersionRootInfo {
    @Value.Parameter
    abstract ImmutableMap<BuildTarget, Version> getSelectedVersions();

    @Value.Parameter
    abstract TargetNodeTranslator getTargetTranslator();
  }

  /** Transforms versioned {@link TargetGraph} */
  private final class VersionedTargetGraphComputation
      implements GraphComputation<VersionTargetGraphKey, TargetNode<?>> {

    private final LoadingCache<VersionTargetGraphKey, VersionRootInfo> keyToRootInfoCache =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<VersionTargetGraphKey, VersionRootInfo>() {
                  @Override
                  public VersionRootInfo load(VersionTargetGraphKey key) throws Exception {
                    return computeRootInfoForKeyUncached(key);
                  }
                });

    /** Count of root nodes. */
    private final AtomicInteger roots = new AtomicInteger();

    private final TargetGraph targetGraph;

    private final VersionSelector versionSelector;

    /** The resolved version graph being built. */
    private final VersionedTargetGraph.Builder targetGraphBuilder = VersionedTargetGraph.builder();

    public VersionedTargetGraphComputation(
        TargetGraph targetGraph, VersionSelector versionSelector) {
      this.targetGraph = targetGraph;
      this.versionSelector = versionSelector;
    }

    @Override
    public Class<VersionTargetGraphKey> getKeyClass() {
      return VersionTargetGraphKey.class;
    }

    @Override
    public TargetNode<?> transform(VersionTargetGraphKey key, ComputationEnvironment env)
        throws VersionException {

      TargetNodeTranslator targetTranslator;
      if (key.getSelectedVersions().isPresent() && key.targetNodeTranslator().isPresent()) {
        targetTranslator = key.targetNodeTranslator().get();
      } else {
        VersionRootInfo info = computeRootInfo(key);
        targetTranslator = info.getTargetTranslator();
      }

      return processVersionSubGraphNode(key.getTargetNode(), targetTranslator, env);
    }

    @Override
    public ImmutableSet<VersionTargetGraphKey> discoverDeps(
        VersionTargetGraphKey key, ComputationEnvironment env) {
      return ImmutableSet.of();
    }

    @Override
    public ImmutableSet<VersionTargetGraphKey> discoverPreliminaryDeps(
        VersionTargetGraphKey versionTargetGraphKey) throws VersionException {

      TargetNode<?> root = versionTargetGraphKey.getTargetNode();

      ImmutableMap<BuildTarget, Version> selectedVersions;
      TargetNodeTranslator targetTranslator;

      if (versionTargetGraphKey.getSelectedVersions().isPresent()
          && versionTargetGraphKey.targetNodeTranslator().isPresent()) {
        selectedVersions = versionTargetGraphKey.getSelectedVersions().get();
        targetTranslator = versionTargetGraphKey.targetNodeTranslator().get();
      } else {
        VersionRootInfo info = computeRootInfo(versionTargetGraphKey);
        selectedVersions = info.getSelectedVersions();
        targetTranslator = info.getTargetTranslator();
      }

      Set<BuildTarget> parseDeps = root.getParseDeps();
      ImmutableSet.Builder<VersionTargetGraphKey> subGraphKeys =
          ImmutableSet.builderWithExpectedSize(parseDeps.size());
      targetGraph
          .getAll(parseDeps)
          .forEach(
              targetNode -> {
                if (TargetGraphVersionTransformations.isVersionPropagator(targetNode)
                    || TargetGraphVersionTransformations.getVersionedNode(targetNode).isPresent()) {
                  subGraphKeys.add(
                      ImmutableVersionTargetGraphKey.of(
                          resolveVersions(targetNode, selectedVersions),
                          Optional.of(selectedVersions),
                          Optional.of(targetTranslator)));
                } else {
                  subGraphKeys.add(ImmutableVersionTargetGraphKey.of(targetNode));
                }
              });
      return subGraphKeys.build();
    }

    private VersionRootInfo computeRootInfo(VersionTargetGraphKey key) throws VersionException {
      try {
        return keyToRootInfoCache.get(key);
      } catch (ExecutionException e) {
        Throwable cause = Throwables.getRootCause(e);
        Throwables.throwIfInstanceOf(cause, VersionException.class);
        throw new IllegalStateException("Unexpected Exception type: ", cause);
      }
    }

    private VersionRootInfo computeRootInfoForKeyUncached(VersionTargetGraphKey key)
        throws VersionException {
      TargetNode<?> root = key.getTargetNode();
      VersionInfo versionInfo = getVersionInfo(root);

      // Select the versions to use for this sub-graph.
      ImmutableMap<BuildTarget, Version> selectedVersions =
          versionSelector.resolve(root.getBuildTarget(), versionInfo.getVersionDomain());

      TargetNodeTranslator targetTranslator = getTargetNodeTranslator(root, selectedVersions);

      return ImmutableVersionRootInfo.of(selectedVersions, targetTranslator);
    }

    @SuppressWarnings("unchecked")
    private TargetNode<?> processVersionSubGraphNode(
        TargetNode<?> node, TargetNodeTranslator targetTranslator, ComputationEnvironment env) {

      // Create the new target node, with the new target and deps.
      TargetNode<?> newNode =
          ((Optional<TargetNode<?>>) (Optional<?>) targetTranslator.translateNode(node))
              .orElse(node);

      LOG.verbose(
          "%s: new node declared deps %s, extra deps %s, arg %s",
          newNode.getBuildTarget(),
          newNode.getDeclaredDeps(),
          newNode.getExtraDeps(),
          newNode.getConstructorArg());

      // Add the new node, and it's dep edges, to the new graph.
      // Insert the node into the graph, indexing it by a base target containing only the version
      // flavor, if one exists.
      targetGraphBuilder.addNode(
          node.getBuildTarget()
              .withFlavors(
                  Sets.difference(
                      newNode.getBuildTarget().getFlavors(), node.getBuildTarget().getFlavors())),
          newNode);

      for (TargetNode<?> childNode : env.getDeps(VersionTargetGraphKey.class).values()) {
        targetGraphBuilder.addEdge(newNode, childNode);
      }

      return newNode;
    }

    private TargetNode<?> resolveVersions(
        TargetNode<?> node, ImmutableMap<BuildTarget, Version> selectedVersions) {
      Optional<TargetNode<VersionedAliasDescriptionArg>> versionedNode =
          TargetNodes.castArg(node, VersionedAliasDescriptionArg.class);
      if (versionedNode.isPresent()) {
        node =
            targetGraph.get(
                Preconditions.checkNotNull(
                    versionedNode
                        .get()
                        .getConstructorArg()
                        .getVersions()
                        .get(selectedVersions.get(node.getBuildTarget()))));
      }
      return node;
    }
  }
}
