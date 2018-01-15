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

package com.facebook.buck.cxx;

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.immutables.value.Value;

/**
 * The group of {@link BuildTarget}s from C/C++ constructor args which comprise a C/C++ descriptions
 * logical C/C++ deps used to find dependency {@link NativeLinkable}s or {@link
 * CxxPreprocessorDep}s.
 */
@Value.Immutable(builder = false, copy = false, singleton = true)
@BuckStyleTuple
abstract class AbstractCxxDeps {

  abstract ImmutableList<BuildTarget> getDeps();

  abstract ImmutableList<PatternMatchedCollection<ImmutableSortedSet<BuildTarget>>>
      getPlatformDeps();

  private Stream<BuildTarget> getSpecificPlatformDeps(CxxPlatform cxxPlatform) {
    return getPlatformDeps()
        .stream()
        .flatMap(
            p ->
                p.getMatchingValues(cxxPlatform.getFlavor().toString())
                    .stream()
                    .flatMap(Collection::stream));
  }

  private Stream<BuildTarget> getAllPlatformDeps() {
    return getPlatformDeps()
        .stream()
        .flatMap(p -> p.getValues().stream().flatMap(Collection::stream));
  }

  public ImmutableSet<BuildRule> getForAllPlatforms(BuildRuleResolver resolver) {
    return RichStream.<BuildTarget>empty()
        .concat(getDeps().stream())
        .concat(getAllPlatformDeps())
        .map(resolver::getRule)
        .toImmutableSet();
  }

  public ImmutableSet<BuildRule> get(BuildRuleResolver resolver, CxxPlatform cxxPlatform) {
    return RichStream.<BuildTarget>empty()
        .concat(getDeps().stream())
        .concat(getSpecificPlatformDeps(cxxPlatform))
        .map(resolver::getRule)
        .toImmutableSet();
  }

  public static CxxDeps concat(Iterable<CxxDeps> cxxDeps) {
    CxxDeps.Builder builder = CxxDeps.builder();
    RichStream.from(cxxDeps).forEach(builder::addDeps);
    return builder.build();
  }

  public static CxxDeps concat(CxxDeps... cxxDeps) {
    return concat(ImmutableList.copyOf(cxxDeps));
  }

  public static Builder builder() {
    return new Builder();
  }

  public static Builder builder(CxxDeps deps) {
    return new Builder().addDeps(deps);
  }

  public static class Builder {

    private final List<BuildTarget> deps = new ArrayList<>();
    private final List<PatternMatchedCollection<ImmutableSortedSet<BuildTarget>>> platformDeps =
        new ArrayList<>();

    private Builder() {}

    public Builder addDep(SourcePath path) {
      if (path instanceof BuildTargetSourcePath) {
        deps.add(((BuildTargetSourcePath) path).getTarget());
      }
      return this;
    }

    public Builder addDep(Optional<SourcePath> path) {
      path.ifPresent(this::addDep);
      return this;
    }

    public Builder addDep(BuildTarget target) {
      deps.add(target);
      return this;
    }

    public Builder addDeps(Iterable<BuildTarget> targets) {
      Iterables.addAll(deps, targets);
      return this;
    }

    public Builder addPlatformDeps(
        PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> targets) {
      platformDeps.add(targets);
      return this;
    }

    public Builder addDeps(CxxDeps cxxDeps) {
      deps.addAll(cxxDeps.getDeps());
      platformDeps.addAll(cxxDeps.getPlatformDeps());
      return this;
    }

    public CxxDeps build() {
      return CxxDeps.of(deps, platformDeps);
    }
  }
}
