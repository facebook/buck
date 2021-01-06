/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.parser;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNodeMaybeIncompatible;
import com.facebook.buck.parser.TargetSpecResolver.TargetNodeFilterForSpecResolver;
import com.facebook.buck.parser.spec.TargetNodeSpec;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A version of {@link TargetNodeFilterForSpecResolver} delegates filtering to another instance of
 * {@link TargetNodeFilterForSpecResolver} and performs additional filtering of nodes.
 */
class TargetNodeFilterForSpecResolverWithNodeFiltering implements TargetNodeFilterForSpecResolver {

  private final TargetNodeFilterForSpecResolver filter;
  private final Predicate<TargetNodeMaybeIncompatible> nodeFilter;

  protected TargetNodeFilterForSpecResolverWithNodeFiltering(
      TargetNodeFilterForSpecResolver filter, Predicate<TargetNodeMaybeIncompatible> nodeFilter) {
    this.filter = filter;
    this.nodeFilter = nodeFilter;
  }

  @Override
  public ImmutableMap<BuildTarget, TargetNodeMaybeIncompatible> filter(
      TargetNodeSpec spec, Iterable<TargetNodeMaybeIncompatible> nodes) {
    return filter.filter(spec, nodes).entrySet().stream()
        .filter(entry -> nodeFilter.test(entry.getValue()))
        .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
  }
}
