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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import org.immutables.value.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The group of {@link BuildTarget}s from C/C++ constructor args which comprise a C/C++
 * descriptions logical C/C++ deps used to find dependency {@link NativeLinkable}s or
 * {@link CxxPreprocessorDep}s.
 */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractCxxDeps {

  public static final CxxDeps EMPTY = CxxDeps.of(ImmutableList.of());

  abstract ImmutableList<BuildTarget> getDeps();

  public ImmutableSet<BuildRule> get(BuildRuleResolver resolver) {
    ImmutableSet.Builder<BuildRule> deps = ImmutableSet.builder();
    for (BuildTarget target : getDeps()) {
      deps.add(resolver.getRule(target));
    }
    return deps.build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static Builder builder(CxxDeps deps) {
    return new Builder()
        .addDeps(deps);
  }

  public static CxxDeps concat(Iterable<CxxDeps> cxxDeps) {
    CxxDeps.Builder builder = CxxDeps.builder();
    RichStream.from(cxxDeps)
        .forEach(builder::addDeps);
    return builder.build();
  }

  public static CxxDeps concat(CxxDeps... cxxDeps) {
    return concat(ImmutableList.copyOf(cxxDeps));
  }

  public static class Builder {

    private final List<BuildTarget> deps = new ArrayList<>();

    private Builder() {}

    public Builder addDep(SourcePath path) {
      if (path instanceof BuildTargetSourcePath) {
        deps.add(((BuildTargetSourcePath<?>) path).getTarget());
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

    public Builder addDeps(CxxDeps cxxDeps) {
      deps.addAll(cxxDeps.getDeps());
      return this;
    }

    public CxxDeps build() {
      return CxxDeps.of(deps);
    }

  }

}
