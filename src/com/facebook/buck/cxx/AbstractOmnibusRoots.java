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

package com.facebook.buck.cxx;

import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import org.immutables.value.Value;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A helper class for building the included and excluded omnibus roots to pass to the omnibus
 * builder.
 */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractOmnibusRoots {

  /**
   * @return the {@link NativeLinkTarget} roots that are included in omnibus linking.
   */
  abstract ImmutableMap<BuildTarget, NativeLinkTarget> getIncludedRoots();

  /**
   * @return the {@link NativeLinkable} roots that are excluded from omnibus linking.
   */
  abstract ImmutableMap<BuildTarget, NativeLinkable> getExcludedRoots();

  public static Builder builder(CxxPlatform cxxPlatform, ImmutableSet<BuildTarget> excludes) {
    return new Builder(cxxPlatform, excludes);
  }

  public static class Builder {

    private final CxxPlatform cxxPlatform;
    private final ImmutableSet<BuildTarget> excludes;

    private final Map<BuildTarget, NativeLinkTarget> includedRoots = new LinkedHashMap<>();
    private final Map<BuildTarget, NativeLinkable> excludedRoots = new LinkedHashMap<>();

    private Builder(CxxPlatform cxxPlatform, ImmutableSet<BuildTarget> excludes) {
      this.cxxPlatform = cxxPlatform;
      this.excludes = excludes;
    }

    /**
     * Add a root which is included in omnibus linking.
     */
    public void addIncludedRoot(NativeLinkTarget root) {
      includedRoots.put(root.getBuildTarget(), root);
    }

    /**
     * Add a root which is excluded from omnibus linking.
     */
    public void addExcludedRoot(NativeLinkable root) {
      excludedRoots.put(root.getBuildTarget(), root);
    }

    /**
     * Add a node which may qualify as either an included root, and excluded root, or neither.
     *
     * @return whether the node was added as a root.
     */
    public boolean addPotentialRoot(HasBuildTarget node) {

      // First check if this root can be used as an included root.
      Optional<NativeLinkTarget> target = NativeLinkables.getNativeLinkTarget(node, cxxPlatform);
      if (target.isPresent() && !excludes.contains(node.getBuildTarget())) {
        addIncludedRoot(target.get());
        return true;
      }

      // If not, check if it can be used as a excluded root.
      if (node instanceof NativeLinkable) {
        addExcludedRoot((NativeLinkable) node);
        return true;
      }

      // Otherwise, return `false` to indicate this wasn't a root.
      return false;
    }

    private ImmutableMap<BuildTarget, NativeLinkable> buildExcluded() {
      final Map<BuildTarget, NativeLinkable> excluded = new LinkedHashMap<>();

      excluded.putAll(excludedRoots);

      // Recursively expand the excluded nodes including any preloaded deps, as we'll need this full
      // list to know which roots to exclude from omnibus linking.
      new AbstractBreadthFirstTraversal<NativeLinkable>(excludedRoots.values()) {
        @Override
        public Iterable<NativeLinkable> visit(NativeLinkable linkable) {
          if (includedRoots.containsKey(linkable.getBuildTarget())) {
            excluded.put(linkable.getBuildTarget(), linkable);
          }
          return Iterables.concat(
              linkable.getNativeLinkableDeps(cxxPlatform),
              linkable.getNativeLinkableExportedDeps(cxxPlatform));
        }
      }.start();

      return ImmutableMap.copyOf(excluded);
    }

    private ImmutableMap<BuildTarget, NativeLinkTarget> buildIncluded(
        ImmutableSet<BuildTarget> excluded) {
      return ImmutableMap.copyOf(
          Maps.filterKeys(includedRoots, Predicates.not(Predicates.in(excluded))));
    }

    public OmnibusRoots build() {
      ImmutableMap<BuildTarget, NativeLinkable> excluded = buildExcluded();
      ImmutableMap<BuildTarget, NativeLinkTarget> included = buildIncluded(excluded.keySet());
      return OmnibusRoots.of(included, excluded);
    }

  }

}
