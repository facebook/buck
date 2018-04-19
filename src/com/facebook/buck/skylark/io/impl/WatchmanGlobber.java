/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.skylark.io.impl;

import com.facebook.buck.io.WatchmanClient;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * An implementation of globbing functionality that allows resolving file paths based on include
 * patterns (file patterns that should be returned) minus exclude patterns (file patterns that
 * should be excluded from the resulting set) using Watchman tool for improved performance.
 *
 * <p>The implementation is mostly compatible with glob_watchman.py and as such differs from the
 * {@link SimpleGlobber} in certain ways:
 *
 * <ul>
 *   <li>does not fail for patterns that cannot possibly match
 *   <li>does not collapse multiple slashes
 * </ul>
 */
public class WatchmanGlobber {

  private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
  private final WatchmanClient watchmanClient;
  /** Path used as a root when resolving patterns. */
  private final String basePath;

  private WatchmanGlobber(WatchmanClient watchmanClient, String basePath) {
    this.watchmanClient = watchmanClient;
    this.basePath = basePath;
  }

  /**
   * @param include File patterns that should be included in the resulting set.
   * @param exclude File patterns that should be excluded from the resulting set.
   * @param excludeDirectories Whether directories should be excluded from the resulting set.
   * @return The set of paths resolved using include patterns minus paths excluded by exclude
   *     patterns.
   */
  public Optional<ImmutableSet<String>> run(
      Collection<String> include, Collection<String> exclude, boolean excludeDirectories)
      throws IOException, InterruptedException {
    ImmutableMap<String, ?> watchmanQuery =
        createWatchmanQuery(include, exclude, excludeDirectories);

    return watchmanClient
        .queryWithTimeout(TIMEOUT_NANOS, "query", basePath, watchmanQuery)
        .map(
            result -> {
              @SuppressWarnings("unchecked")
              List<String> files = (List<String>) result.get("files");
              return ImmutableSet.copyOf(files);
            });
  }

  /**
   * Creates a JSON-like Watchman query to get a list of matching files.
   *
   * <p>The implementation should ideally match the one in glob_watchman.py.
   */
  private ImmutableMap<String, ?> createWatchmanQuery(
      Collection<String> include, Collection<String> exclude, boolean excludeDirectories) {
    ImmutableList.Builder<Object> matchExpressions = ImmutableList.builder();
    matchExpressions.add("allof", toTypeExpression(excludeDirectories));
    if (!exclude.isEmpty()) {
      matchExpressions.add(toExcludeExpression(exclude));
    }
    return ImmutableMap.of(
        "expression",
        matchExpressions.build(),
        "glob",
        include,
        "fields",
        ImmutableList.of("name"));
  }

  private ImmutableList<Object> toTypeExpression(boolean excludeDirectories) {
    ImmutableList.Builder<Object> typeExpressionBuilder =
        ImmutableList.builder()
            .add("anyof")
            .add(ImmutableList.of("type", "f"))
            .add(ImmutableList.of("type", "l"));
    if (!excludeDirectories) {
      typeExpressionBuilder.add(ImmutableList.of("type", "d"));
    }
    return typeExpressionBuilder.build();
  }

  private ImmutableList<Serializable> toExcludeExpression(Collection<String> exclude) {
    return ImmutableList.of(
        "not",
        ImmutableList.builder()
            .add("anyof")
            .addAll(
                exclude
                    .stream()
                    .map(e -> ImmutableList.of("match", e, "wholename"))
                    .collect(Collectors.toList()))
            .build());
  }

  /**
   * Factory method for creating {@link WatchmanGlobber} instances.
   *
   * @param basePath The base path relative to which paths matching glob patterns will be resolved.
   */
  public static WatchmanGlobber create(WatchmanClient watchmanClient, String basePath) {
    return new WatchmanGlobber(watchmanClient, basePath);
  }
}
