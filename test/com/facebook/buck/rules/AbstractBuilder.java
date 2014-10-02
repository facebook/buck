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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import java.lang.reflect.Field;
import java.nio.file.Paths;

/**
 * Support class for writing builders, which can create {@link BuildRule} and {@link BuildRule} \
 * instances at test time. It does this by as closely as possible mirroring the behavior seen when
 * running the actual parser.
 *
 * @param <A> The type of the constructor arg returned by the Buildable's {@link Description}.
 */
public abstract class AbstractBuilder<A> {

  private final Description<A> description;
  private final BuildTarget target;
  protected final A arg;

  protected AbstractBuilder(Description<A> description, BuildTarget target) {
    this.description = Preconditions.checkNotNull(description);
    this.target = Preconditions.checkNotNull(target);
    this.arg = description.createUnpopulatedConstructorArg();
    populateWithDefaultValues(this.arg, this.target);
  }

  public final BuildRule build(BuildRuleResolver resolver) {
    return build(resolver, new FakeProjectFilesystem());
  }

  public final BuildRule build(BuildRuleResolver resolver, ProjectFilesystem filesystem) {
    // The BuildRule determines its deps by extracting them from the rule parameters.
    BuildRuleParams params = createBuildRuleParams(resolver, filesystem);

    BuildRule rule = description.createBuildRule(params, resolver, arg);
    resolver.addToIndex(rule);
    return rule;
  }

  @SuppressWarnings("unchecked")
  private BuildRuleParams createBuildRuleParams(
      BuildRuleResolver resolver,
      ProjectFilesystem filesystem) {
    // Not all rules have deps, but all rules call them deps. When they do, they're always optional.
    // Grab them in the unsafest way I know.
    FakeBuildRuleParamsBuilder builder = new FakeBuildRuleParamsBuilder(target)
        .setType(description.getBuildRuleType())
        .setProjectFilesystem(filesystem);
    try {
      Field depsField = arg.getClass().getField("deps");
      Object optional = depsField.get(arg);

      if (optional == null) {
        return builder.build();
      }
      // Here's a whole series of assumptions in one lump of a Bad Idea.
      ImmutableSortedSet<BuildTarget> deps =
          (ImmutableSortedSet<BuildTarget>) ((Optional<?>) optional).get();
      return builder.setDeps(resolver.getAllRules(deps)).build();
    } catch (ReflectiveOperationException ignored) {
      // Field doesn't exist: no deps.
      return builder.build();
    }
  }

  protected <C extends Comparable<?>> Optional<ImmutableSortedSet<C>> amend(
      Optional<ImmutableSortedSet<C>> existing,
      C instance) {
    ImmutableSortedSet.Builder<C> toReturn = ImmutableSortedSet.naturalOrder();
    if (existing != null && existing.isPresent()) {
      toReturn.addAll(existing.get());
    }
    toReturn.add(instance);
    return Optional.of(toReturn.build());
  }

  // Thanks to type erasure, this needs a unique name.
  protected <C extends Comparable<?>> Optional<ImmutableSet<C>> amendSet(
      Optional<ImmutableSet<C>> existing,
      C instance) {
    ImmutableSet.Builder<C> toReturn = ImmutableSet.builder();
    if (existing.isPresent()) {
      toReturn.addAll(existing.get());
    }
    toReturn.add(instance);
    return Optional.of(toReturn.build());
  }

  private void populateWithDefaultValues(A arg, BuildTarget target) {
    BuildRuleResolver resolver = new BuildRuleResolver(Maps.<BuildTarget, BuildRule>newHashMap());
    ProjectFilesystem filesystem = new ProjectFilesystem(Paths.get("."));
    BuildRuleFactoryParams factoryParams = NonCheckingBuildRuleFactoryParams
        .createNonCheckingBuildRuleFactoryParams(
            Maps.<String, Object>newHashMap(),
            new BuildTargetParser(),
            target);
    try {
      new ConstructorArgMarshaller()
          .populate(resolver, filesystem, factoryParams, arg, true);
    } catch (ConstructorArgMarshalException error) {
      throw Throwables.propagate(error);
    }
  }
}
