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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.lang.reflect.Field;

/**
 * Support class for writing builders for nodes of a {@link TargetGraph}
 * and {@link ActionGraph} ({@link TargetNode} and {@link BuildRule} respectively) mirroring the
 * behavior seen when running the actual parser as closely as possible.
 */
public abstract class AbstractNodeBuilder<A> {
  protected final Description<A> description;
  protected final BuildRuleFactoryParams factoryParams;
  protected final BuildTarget target;
  protected final A arg;

  protected AbstractNodeBuilder(
      Description<A> description,
      BuildTarget target) {
    this.description = description;
    this.factoryParams = NonCheckingBuildRuleFactoryParams.createNonCheckingBuildRuleFactoryParams(
        new BuildTargetParser(),
        target);
    this.target = target;
    this.arg = description.createUnpopulatedConstructorArg();
    populateWithDefaultValues(this.arg);
  }

  public final BuildRule build(BuildRuleResolver resolver) {
    return build(resolver, new FakeProjectFilesystem(), TargetGraph.EMPTY);
  }

  public final BuildRule build(BuildRuleResolver resolver, ProjectFilesystem filesystem) {
    return build(resolver, filesystem, TargetGraph.EMPTY);
  }

  public final BuildRule build(
      BuildRuleResolver resolver,
      ProjectFilesystem filesystem,
      TargetGraph targetGraph) {

    // The BuildRule determines its deps by extracting them from the rule parameters.
    BuildRuleParams params = createBuildRuleParams(resolver, filesystem, targetGraph);

    BuildRule rule = description.createBuildRule(params, resolver, arg);
    resolver.addToIndex(rule);
    return rule;
  }

  public TargetNode<A> build() {
    try {
      return new TargetNode<>(
          description,
          arg,
          factoryParams,
          getDepsFromArg(),
          ImmutableSet.<BuildTargetPattern>of());
    } catch (NoSuchBuildTargetException | TargetNode.InvalidSourcePathInputException e) {
      throw Throwables.propagate(e);
    }
  }

  @SuppressWarnings("unchecked")
  public BuildRuleParams createBuildRuleParams(
      BuildRuleResolver resolver,
      ProjectFilesystem filesystem,
      TargetGraph targetGraph) {
    // Not all rules have deps, but all rules call them deps. When they do, they're always optional.
    // Grab them in the unsafest way I know.
    FakeBuildRuleParamsBuilder builder = new FakeBuildRuleParamsBuilder(target)
        .setType(description.getBuildRuleType())
        .setProjectFilesystem(filesystem)
        .setTargetGraph(targetGraph);
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

  @SuppressWarnings("unchecked")
  private ImmutableSortedSet<BuildTarget> getDepsFromArg() {
    // Not all rules have deps, but all rules call them deps. When they do, they're always optional.
    // Grab them in the unsafest way I know.
    try {
      Field depsField = arg.getClass().getField("deps");
      Object optional = depsField.get(arg);

      if (optional == null) {
        return ImmutableSortedSet.of();
      }
      // Here's a whole series of assumptions in one lump of a Bad Idea.
      return (ImmutableSortedSet<BuildTarget>) ((Optional<?>) optional).get();
    } catch (ReflectiveOperationException ignored) {
      // Field doesn't exist: no deps.
      return ImmutableSortedSet.of();
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

  private void populateWithDefaultValues(A arg) {
    try {
      new ConstructorArgMarshaller().populate(
          new FakeProjectFilesystem(),
          factoryParams,
          arg,
          ImmutableSet.<BuildTarget>builder(),
          ImmutableMap.<String, Object>of(),
          true);
    } catch (ConstructorArgMarshalException error) {
      throw Throwables.propagate(error);
    }
  }

}
