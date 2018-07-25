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

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;

public class VersionRootBuilder
    extends AbstractNodeBuilder<
        VersionRootDescriptionArg.Builder,
        VersionRootDescriptionArg,
        VersionRootBuilder.VersionRootDescription,
        BuildRule> {

  public VersionRootBuilder(BuildTarget target) {
    super(new VersionRootDescription(), target);
  }

  public VersionRootBuilder(String target) {
    this(BuildTargetFactory.newInstance(target));
  }

  public VersionRootBuilder setVersionUniverse(String name) {
    getArgForPopulating().setVersionUniverse(name);
    return this;
  }

  public VersionRootBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
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
    getArgForPopulating().setVersionedDeps(deps);
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
            BuildTargetFactory.newInstance(target), Optional.of(constraint)));
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractVersionRootDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {
    Optional<String> getVersionUniverse();

    @Value.NaturalOrder
    ImmutableSortedMap<BuildTarget, Optional<Constraint>> getVersionedDeps();
  }

  public static class VersionRootDescription implements VersionRoot<VersionRootDescriptionArg> {

    @Override
    public Class<VersionRootDescriptionArg> getConstructorArgType() {
      return VersionRootDescriptionArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContextWithTargetGraph context,
        BuildTarget buildTarget,
        BuildRuleParams params,
        VersionRootDescriptionArg args) {
      throw new IllegalStateException();
    }

    @Override
    public boolean isVersionRoot(ImmutableSet<Flavor> flavors) {
      return true;
    }
  }
}
