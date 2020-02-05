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

package com.facebook.buck.cxx;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The group of {@link BuildTarget}s from C/C++ constructor args which comprise a C/C++ descriptions
 * logical C/C++ deps used to find dependency {@link NativeLinkableGroup}s or {@link
 * CxxPreprocessorDep}s.
 */
@BuckStyleValue
public abstract class CxxDeps {

  public static final CxxDeps EMPTY_INSTANCE =
      ImmutableCxxDeps.of(ImmutableList.of(), ImmutableList.of());

  public abstract ImmutableList<BuildTarget> getDeps();

  public abstract ImmutableList<PatternMatchedCollection<ImmutableSortedSet<BuildTarget>>>
      getPlatformDeps();

  public void forEachForAllPlatforms(Consumer<BuildTarget> consumer) {
    // Process all platform-agnostic deps.
    getDeps().forEach(consumer);

    // Process all platform-specific deps.
    Consumer<ImmutableSortedSet<BuildTarget>> setConsumer = s -> s.forEach(consumer);
    int size = getPlatformDeps().size();
    for (int idx = 0; idx < size; idx++) {
      getPlatformDeps().get(idx).forEachValue(setConsumer);
    }
  }

  public ImmutableSet<BuildRule> getForAllPlatforms(BuildRuleResolver resolver) {
    ImmutableSet.Builder<BuildRule> builder = ImmutableSet.builder();
    forEachForAllPlatforms(t -> builder.add(resolver.getRule(t)));
    return builder.build();
  }

  public void forEach(CxxPlatform platform, Consumer<BuildTarget> consumer) {
    // Process all platform-agnostic deps.
    getDeps().forEach(consumer);

    // Process matching platform-specific deps.
    Consumer<ImmutableSortedSet<BuildTarget>> setConsumer = s -> s.forEach(consumer);
    int size = getPlatformDeps().size();
    String platformName = platform.getFlavor().toString();
    for (int idx = 0; idx < size; idx++) {
      getPlatformDeps().get(idx).forEachMatchingValue(platformName, setConsumer);
    }
  }

  public ImmutableSet<BuildRule> get(BuildRuleResolver resolver, CxxPlatform cxxPlatform) {
    ImmutableSet.Builder<BuildRule> builder = ImmutableSet.builder();
    forEach(cxxPlatform, t -> builder.add(resolver.getRule(t)));
    return builder.build();
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
      if (deps.isEmpty() && platformDeps.isEmpty()) {
        return CxxDeps.EMPTY_INSTANCE;
      }
      return ImmutableCxxDeps.of(deps, platformDeps);
    }
  }
}
