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

package com.facebook.buck.apple;

import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public class AppleDependenciesCache {
  private class CacheItem {
    private final ImmutableSortedSet<TargetNode<?, ?>> defaultDeps;
    private final ImmutableSortedSet<TargetNode<?, ?>> exportedDeps;

    CacheItem(
        ImmutableSortedSet<TargetNode<?, ?>> defaultDeps,
        ImmutableSortedSet<TargetNode<?, ?>> exportedDeps) {
      this.defaultDeps = defaultDeps;
      this.exportedDeps = exportedDeps;
    }

    public ImmutableSortedSet<TargetNode<?, ?>> getDefaultDeps() {
      return defaultDeps;
    }

    public ImmutableSortedSet<TargetNode<?, ?>> getExportedDeps() {
      return exportedDeps;
    }
  }

  private final LoadingCache<TargetNode<?, ?>, CacheItem> depsCache;

  public AppleDependenciesCache(TargetGraph projectGraph) {
    this.depsCache =
        CacheBuilder.newBuilder()
            .build(
                CacheLoader.from(
                    node -> {
                      ImmutableSortedSet.Builder<TargetNode<?, ?>> defaultDepsBuilder =
                          ImmutableSortedSet.naturalOrder();
                      ImmutableSortedSet.Builder<TargetNode<?, ?>> exportedDepsBuilder =
                          ImmutableSortedSet.naturalOrder();
                      AppleBuildRules.addDirectAndExportedDeps(
                          projectGraph,
                          node,
                          defaultDepsBuilder,
                          exportedDepsBuilder,
                          Optional.empty());
                      return new CacheItem(defaultDepsBuilder.build(), exportedDepsBuilder.build());
                    }));
  }

  ImmutableSortedSet<TargetNode<?, ?>> getDefaultDeps(TargetNode<?, ?> node) {
    return depsCache.getUnchecked(node).getDefaultDeps();
  }

  ImmutableSortedSet<TargetNode<?, ?>> getExportedDeps(TargetNode<?, ?> node) {
    return depsCache.getUnchecked(node).getExportedDeps();
  }
}
