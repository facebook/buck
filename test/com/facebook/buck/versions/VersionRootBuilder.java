/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.versions;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Optional;

public class VersionRootBuilder
    extends AbstractNodeBuilder<VersionRootBuilder.Arg, VersionRootBuilder.VersionRootDescription> {

  public VersionRootBuilder(BuildTarget target) {
    super(new VersionRootDescription(), target);
  }

  public VersionRootBuilder(String target) {
    this(BuildTargetFactory.newInstance(target));
  }

  public VersionRootBuilder setVersionUniverse(String name) {
    arg.versionUniverse = Optional.of(name);
    return this;
  }

  public VersionRootBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    arg.deps = deps;
    return this;
  }

  public VersionRootBuilder setDeps(BuildTarget... deps) {
    return setDeps(ImmutableSortedSet.copyOf(deps));
  }

  public VersionRootBuilder setDeps(String... deps) {
    ImmutableSortedSet.Builder<BuildTarget> builder = ImmutableSortedSet.naturalOrder();
    for (String dep : deps) {
      builder.add(BuildTargetFactory.newInstance(dep));
    }
    return setDeps(builder.build());
  }

  public VersionRootBuilder setVersionedDeps(
      ImmutableSortedMap<BuildTarget, Optional<Constraint>> deps) {
    arg.versionedDeps = deps;
    return this;
  }

  @SafeVarargs
  public final VersionRootBuilder setVersionedDeps(
      Map.Entry<BuildTarget, Optional<Constraint>>... deps) {
    return setVersionedDeps(ImmutableSortedMap.copyOf(ImmutableList.copyOf(deps)));
  }

  public VersionRootBuilder setVersionedDeps(String target, Constraint constraint) {
    return setVersionedDeps(
        new AbstractMap.SimpleEntry<>(
            BuildTargetFactory.newInstance(target),
            Optional.of(constraint)));
  }

  public static class Arg {
    public Optional<String> versionUniverse;
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    public ImmutableSortedMap<BuildTarget, Optional<Constraint>> versionedDeps =
        ImmutableSortedMap.of();
  }

  public static class VersionRootDescription implements VersionRoot<Arg> {

    @Override
    public Arg createUnpopulatedConstructorArg() {
      return new Arg();
    }

    @Override
    public <A extends Arg> BuildRule createBuildRule(
        TargetGraph targetGraph,
        BuildRuleParams params,
        BuildRuleResolver resolver,
        A args) throws NoSuchBuildTargetException {
      throw new IllegalStateException();
    }

    @Override
    public boolean isVersionRoot(ImmutableSet<Flavor> flavors) {
      return true;
    }

  }

}
