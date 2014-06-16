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
import com.facebook.buck.rules.coercer.AppleSource;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Collection;
import java.util.Objects;

/**
 * Immutable value type which holds information on source file(s)
 * used to build an Apple binary target.
 */
public class TargetSources {
  /**
   * The tree of source files and source groups comprising the target.
   */
  public final ImmutableList<GroupedSource> srcs;

  /**
   * A map of (source path : flags) pairs containing flags to
   * apply to each source or header path.
   */
  public final ImmutableMap<SourcePath, String> perFileFlags;

  /**
   * Paths to each source code file in the target to be compiled.
   */
  public final ImmutableSortedSet<SourcePath> srcPaths;

  /**
   * Paths to each header file in the target.
   */
  public final ImmutableSortedSet<SourcePath> headerPaths;

  private TargetSources(
      ImmutableList<GroupedSource> srcs,
      ImmutableMap<SourcePath, String> perFileFlags,
      ImmutableSortedSet<SourcePath> srcPaths,
      ImmutableSortedSet<SourcePath> headerPaths) {
    this.srcs = Preconditions.checkNotNull(srcs);
    this.perFileFlags = Preconditions.checkNotNull(perFileFlags);
    this.srcPaths = Preconditions.checkNotNull(srcPaths);
    this.headerPaths = Preconditions.checkNotNull(headerPaths);
  }

  /**
   * Creates a {@link TargetSources} given a list of {@link AppleSource}s.
   */
  public static TargetSources ofAppleSources(Collection<AppleSource> appleSources) {
    ImmutableList.Builder<GroupedSource> srcsBuilder = ImmutableList.builder();
    ImmutableMap.Builder<SourcePath, String> perFileFlagsBuilder = ImmutableMap.builder();
    ImmutableSortedSet.Builder<SourcePath> srcPathsBuilder = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<SourcePath> headerPathsBuilder = ImmutableSortedSet.naturalOrder();
    RuleUtils.extractSourcePaths(
        srcsBuilder,
        perFileFlagsBuilder,
        srcPathsBuilder,
        headerPathsBuilder,
        appleSources);
    return new TargetSources(
        srcsBuilder.build(),
        perFileFlagsBuilder.build(),
        srcPathsBuilder.build(),
        headerPathsBuilder.build());
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof TargetSources) {
      TargetSources that = (TargetSources) other;
      return Objects.equals(this.srcs, that.srcs) &&
          Objects.equals(this.perFileFlags, that.perFileFlags) &&
          Objects.equals(this.srcPaths, that.srcPaths) &&
          Objects.equals(this.headerPaths, that.headerPaths);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return Objects.hash(srcs, perFileFlags, srcPaths, headerPaths);
  }
}
