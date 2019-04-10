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
package com.facebook.buck.parser;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.HasBuildTarget;
import com.facebook.buck.parser.TargetSpecResolver.TargetNodeFilterForSpecResolver;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A version of {@link TargetNodeFilterForSpecResolver} delegates filtering to another instance of
 * {@link TargetNodeFilterForSpecResolver} and performs additional filtering of nodes.
 */
class TargetNodeFilterForSpecResolverWithNodeFiltering<T extends HasBuildTarget>
    implements TargetNodeFilterForSpecResolver<T> {

  private final TargetNodeFilterForSpecResolver<T> filter;
  private final Predicate<T> nodeFilter;

  protected TargetNodeFilterForSpecResolverWithNodeFiltering(
      TargetNodeFilterForSpecResolver<T> filter, Predicate<T> nodeFilter) {
    this.filter = filter;
    this.nodeFilter = nodeFilter;
  }

  @Override
  public ImmutableMap<BuildTarget, T> filter(TargetNodeSpec spec, Iterable<T> nodes) {
    return filter.filter(spec, nodes).entrySet().stream()
        .filter(entry -> nodeFilter.test(entry.getValue()))
        .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
  }
}
