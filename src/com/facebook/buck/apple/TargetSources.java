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

import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import org.immutables.value.Value;

import java.util.Collection;
import java.util.List;
import java.util.SortedSet;

/**
 * Immutable value type which holds information on source file(s)
 * used to build an Apple binary target.
 */
@Value.Immutable
@BuckStyleImmutable
public abstract class TargetSources {
  /**
   * The tree of source files and source groups comprising the target.
   */
  @Value.Parameter
  public abstract List<GroupedSource> getSrcs();

  /**
   * Paths to each source code file in the target to be compiled and their per-file flags.
   */
  @Value.Parameter
  public abstract SortedSet<SourceWithFlags> getSourcesWithFlags();

  /**
   * Paths to each public header file in the target.
   */
  @Value.Parameter
  public abstract SortedSet<SourcePath> getPublicHeaderPaths();

  /**
   * Paths to each private header file in the target.
   */
  @Value.Parameter
  public abstract SortedSet<SourcePath> getPrivateHeaderPaths();

  /**
   * Creates a {@link TargetSources} object given a list of {@link SourceWithFlags} objects.
   */
  public static TargetSources fromSourcesWithFlags(
      SourcePathResolver resolver,
      Collection<SourceWithFlags> sourcesWithFlags,
      Collection<SourcePath> headers,
      Collection<SourcePath> exportedHeaders) {
    ImmutableSortedSet.Builder<SourceWithFlags> srcPathsBuilder = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<SourcePath> publicHeaderPathsBuilder =
        ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<SourcePath> privateHeaderPathsBuilder =
        ImmutableSortedSet.naturalOrder();
    RuleUtils.extractSourcePaths(
        srcPathsBuilder,
        publicHeaderPathsBuilder,
        privateHeaderPathsBuilder,
        sourcesWithFlags,
        headers,
        exportedHeaders);

    ImmutableSortedSet<SourceWithFlags> srcPaths = srcPathsBuilder.build();
    ImmutableSortedSet<SourcePath> publicHeaderPaths = publicHeaderPathsBuilder.build();
    ImmutableSortedSet<SourcePath> privateHeaderPaths = privateHeaderPathsBuilder.build();

    ImmutableList<GroupedSource> groupedSource = RuleUtils.createGroupsFromSourcePaths(
        resolver,
        srcPaths,
        publicHeaderPaths,
        privateHeaderPaths);

    return ImmutableTargetSources.of(
        groupedSource,
        srcPaths,
        publicHeaderPaths,
        privateHeaderPaths);
  }

}
