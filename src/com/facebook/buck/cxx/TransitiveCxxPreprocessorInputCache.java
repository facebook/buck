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

package com.facebook.buck.cxx;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.util.concurrent.Parallelizer;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nonnull;

/** Transitive C++ preprocessor input cache */
public class TransitiveCxxPreprocessorInputCache {
  private final Cache<CxxPlatform, ImmutableSortedMap<BuildTarget, CxxPreprocessorInput>> cache =
      CacheBuilder.newBuilder().build();
  private final CxxPreprocessorDep preprocessorDep;

  public TransitiveCxxPreprocessorInputCache(CxxPreprocessorDep preprocessorDep) {
    this.preprocessorDep = preprocessorDep;
  }

  /** Get a value from the cache */
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getUnchecked(
      CxxPlatform key, ActionGraphBuilder graphBuilder) {
    try {
      return cache.get(
          key,
          () ->
              computeTransitiveCxxToPreprocessorInputMap(
                  key, preprocessorDep, true, graphBuilder, graphBuilder.getParallelizer()));
    } catch (ExecutionException e) {
      throw new UncheckedExecutionException(e.getCause());
    }
  }

  public static ImmutableMap<BuildTarget, CxxPreprocessorInput>
      computeTransitiveCxxToPreprocessorInputMap(
          @Nonnull CxxPlatform key,
          CxxPreprocessorDep preprocessorDep,
          boolean includeDep,
          ActionGraphBuilder graphBuilder) {
    return computeTransitiveCxxToPreprocessorInputMap(
        key, preprocessorDep, includeDep, graphBuilder, graphBuilder.getParallelizer());
  }

  private static ImmutableSortedMap<BuildTarget, CxxPreprocessorInput>
      computeTransitiveCxxToPreprocessorInputMap(
          @Nonnull CxxPlatform key,
          CxxPreprocessorDep preprocessorDep,
          boolean includeDep,
          ActionGraphBuilder graphBuilder,
          Parallelizer parallelizer) {
    Map<BuildTarget, CxxPreprocessorInput> builder = new HashMap<>();
    if (includeDep) {
      builder.put(
          preprocessorDep.getBuildTarget(),
          preprocessorDep.getCxxPreprocessorInput(key, graphBuilder));
    }

    Collection<ImmutableMap<BuildTarget, CxxPreprocessorInput>> transitiveDepInputs =
        parallelizer.maybeParallelizeTransform(
            ImmutableList.copyOf(preprocessorDep.getCxxPreprocessorDeps(key, graphBuilder)),
            dep -> dep.getTransitiveCxxPreprocessorInput(key, graphBuilder));
    transitiveDepInputs.forEach(builder::putAll);

    // Using an ImmutableSortedMap here:
    //
    // 1. Memory efficiency. ImmutableSortedMap is implemented with 2 lists (an ImmutableSortedSet
    // of keys, and a ImmutableList of values). This is much more efficient than an ImmutableMap,
    // which creates an Entry instance for each entry.
    //
    // 2. Historically we seem to care that the result has some definite order.
    //
    // 3. We mostly iterate over these maps rather than do lookups, so ImmutableSortedMap
    // binary-search based lookup is not an issue.
    return ImmutableSortedMap.copyOf(builder);
  }
}
