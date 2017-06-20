/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.collect.SortedSets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;
import java.util.SortedSet;
import javax.annotation.Nullable;

/** Builder suitable for generating the dependency list of a build rule. */
public class DepsBuilder {
  private final ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.naturalOrder();
  private final SourcePathRuleFinder ruleFinder;
  @Nullable private SortedSet<BuildRule> commonDeps;

  public DepsBuilder(SourcePathRuleFinder ruleFinder) {
    this.ruleFinder = ruleFinder;
  }

  public SortedSet<BuildRule> build() {
    if (commonDeps == null) {
      return builder.build();
    }
    return SortedSets.union(builder.build(), commonDeps);
  }

  public DepsBuilder add(CxxSource source) {
    return add(source.getPath());
  }

  public DepsBuilder add(SourcePath sourcePath) {
    builder.addAll(ruleFinder.filterBuildRuleInputs(sourcePath));
    return this;
  }

  public DepsBuilder add(BuildRule buildRule) {
    builder.add(buildRule);
    return this;
  }

  public DepsBuilder add(PreprocessorDelegate delegate) {
    builder.addAll(delegate.getDeps(ruleFinder));
    return this;
  }

  public DepsBuilder add(CompilerDelegate delegate) {
    builder.addAll(delegate.getDeps(ruleFinder));
    return this;
  }

  // We create a BuildRule per source file. To avoid copying the deps of the target into so many
  // BuildRules, which uses a non-trivial amount of memory, hold on to the common deps in one
  // SortedSet, and return a merging view.
  public DepsBuilder setCommonDeps(SortedSet<BuildRule> commonDeps) {
    Preconditions.checkState(
        this.commonDeps == null,
        "Can only set commonDeps once. Tried to set it to %s but was already %s",
        commonDeps,
        this.commonDeps);
    this.commonDeps = commonDeps;
    return this;
  }
}
