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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Collection;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

public abstract class AbstractVersionedTargetGraphBuilder implements VersionedTargetGraphBuilder {

  protected final TypeCoercerFactory typeCoercerFactory;
  private final UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory;
  protected final TargetGraphAndBuildTargets unversionedTargetGraphAndBuildTargets;
  protected final long timeout;
  protected final TimeUnit timeUnit;

  protected AbstractVersionedTargetGraphBuilder(
      TypeCoercerFactory typeCoercerFactory,
      UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory,
      TargetGraphAndBuildTargets unversionedTargetGraphAndBuildTargets,
      long timeout,
      TimeUnit timeUnit) {
    this.typeCoercerFactory = typeCoercerFactory;
    this.unconfiguredBuildTargetFactory = unconfiguredBuildTargetFactory;
    this.unversionedTargetGraphAndBuildTargets = unversionedTargetGraphAndBuildTargets;
    this.timeout = timeout;
    this.timeUnit = timeUnit;
  }

  protected TargetNode<?> getNode(BuildTarget target) {
    return unversionedTargetGraphAndBuildTargets.getTargetGraph().get(target);
  }

  protected Optional<TargetNode<?>> getNodeOptional(BuildTarget target) {
    return unversionedTargetGraphAndBuildTargets.getTargetGraph().getOptional(target);
  }

  protected final TargetNode<?> resolveVersions(
      TargetNode<?> node, ImmutableMap<BuildTarget, Version> selectedVersions) {
    Optional<TargetNode<VersionedAliasDescriptionArg>> versionedNode =
        TargetNodes.castArg(node, VersionedAliasDescriptionArg.class);
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

  /** Get/cache the transitive version info for this node. */
  protected abstract VersionInfo getVersionInfo(TargetNode<?> node);

  /**
   * @return the {@link BuildTarget} to use in the resolved target graph, formed by adding a flavor
   *     generated from the given version selections.
   */
  protected final Optional<BuildTarget> getTranslateBuildTarget(
      TargetNode<?> node, ImmutableMap<BuildTarget, Version> selectedVersions) {

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
        Flavor versionedFlavor = ParallelVersionedTargetGraphBuilder.getVersionedFlavor(versions);
        newTarget = node.getBuildTarget().withAppendedFlavors(versionedFlavor);
      }
    }

    return newTarget.equals(originalTarget) ? Optional.empty() : Optional.of(newTarget);
  }

  protected TargetNodeTranslator getTargetNodeTranslator(
      TargetNode<?> root, ImmutableMap<BuildTarget, Version> selectedVersions) {
    // Build a target translator object to translate build targets.
    ImmutableList<TargetTranslator<?>> translators =
        ImmutableList.of(new QueryTargetTranslator(unconfiguredBuildTargetFactory));
    return new TargetNodeTranslator(typeCoercerFactory, translators) {

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
                        Optional<TargetNode<?>> node = getNodeOptional(target);
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
      public Optional<ImmutableMap<BuildTarget, Version>> getSelectedVersions(BuildTarget target) {
        ImmutableMap.Builder<BuildTarget, Version> builder = ImmutableMap.builder();
        for (BuildTarget dep : getVersionInfo(getNode(target)).getVersionDomain().keySet()) {
          builder.put(dep, selectedVersions.get(dep));
        }
        return Optional.of(builder.build());
      }
    };
  }
}
