/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.features.rust;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;

/**
 * In Rust, direct dependencies are passed to rustc with an `--extern` line like `--extern
 * crate=libcrate.rlib`. In some rare cases, you have to pass extra options to `--extern` like
 * `--extern noprelude:crate=libcrate.rlib`.
 *
 * <p>Rust rules have a `flagged_deps` attribute allowing you to specify a direct dependency and its
 * accompanying extern options and this class provides helper functions for processing that
 * attribute.
 */
public class FlaggedDeps {
  /**
   * Flags don't affect how a target is built; just how it appears on the command line for targets
   * that depend on it. As far as the action graph is concerned, `flagged_deps` are just more
   * `deps`. This helper allows you to handle them as such.
   */
  public static ImmutableSortedSet<BuildTarget> getDeps(
      ImmutableList<Pair<BuildTarget, ImmutableList<String>>> flaggedDeps) {
    return flaggedDeps.stream()
        .map(pair -> pair.getFirst())
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  /**
   * Dependencies are added to the command line one target at a time. This helper returns the flags
   * that should be added to each dependency's `--extern` line or an empty list if no flags are
   * needed.
   */
  public static ImmutableList<String> getFlagsForTarget(
      ImmutableList<Pair<BuildTarget, ImmutableList<String>>> flaggedDeps, BuildTarget target) {
    return flaggedDeps.stream()
        .filter(dep -> dep.getFirst().equals(target))
        .findAny()
        .map(dep -> dep.getSecond())
        .orElse(ImmutableList.of());
  }
}
