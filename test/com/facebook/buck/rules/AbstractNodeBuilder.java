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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Collection;

import javax.annotation.Nullable;

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
  private final Function<Optional<String>, Path> cellRoots;
  @Nullable
  private final HashCode rawHashCode;

  protected AbstractNodeBuilder(
      Description<A> description,
      BuildTarget target) {
    this(description, target, null);
  }

  protected AbstractNodeBuilder(
      Description<A> description,
      BuildTarget target,
      HashCode hashCode) {
    this.description = description;
    this.factoryParams = NonCheckingBuildRuleFactoryParams.createNonCheckingBuildRuleFactoryParams(
        target);
    this.target = target;
    this.rawHashCode = hashCode;

    this.cellRoots = new Function<Optional<String>, Path>() {
      @Override
      public Path apply(Optional<String> input) {
        if (input.isPresent()) {
          throw new HumanReadableException("Can't find the cell");
        }
        return factoryParams.getProjectFilesystem().getRootPath();
      }
    };

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
      Collection<TargetNode<?>> targetNodes) {
    targetNodes.add(build());
    return build(
        resolver,
        filesystem,
        TargetGraphFactory.newInstance(ImmutableSet.copyOf(targetNodes)));
  }

  public final BuildRule build(
      BuildRuleResolver resolver,
      ProjectFilesystem filesystem,
      TargetGraph targetGraph) {

    // The BuildRule determines its deps by extracting them from the rule parameters.
    BuildRuleParams params = createBuildRuleParams(resolver, filesystem);

    BuildRule rule = description.createBuildRule(targetGraph, params, resolver, arg);
    resolver.addToIndex(rule);
    return rule;
  }

  public TargetNode<A> build() {
    try {
      HashCode hash = rawHashCode == null ?
          Hashing.sha1().hashString(factoryParams.target.getFullyQualifiedName(), UTF_8) :
          rawHashCode;

      return new TargetNode<>(
          // This hash will do in a pinch.
          hash,
          description,
          arg,
          factoryParams,
          getDepsFromArg(),
          ImmutableSet.<BuildTargetPattern>of(),
          cellRoots);
    } catch (NoSuchBuildTargetException | TargetNode.InvalidSourcePathInputException e) {
      throw Throwables.propagate(e);
    }
  }

  public BuildRuleParams createBuildRuleParams(
      BuildRuleResolver resolver,
      ProjectFilesystem filesystem) {
    TargetNode<?> node = build();
    return new FakeBuildRuleParamsBuilder(target)
        .setProjectFilesystem(filesystem)
        .setDeclaredDeps(resolver.getAllRules(node.getDeclaredDeps()))
        .setExtraDeps(resolver.getAllRules(node.getExtraDeps()))
        .build();
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
          cellRoots,
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

  @SuppressWarnings("unchecked")
  public Iterable<BuildTarget> findImplicitDeps() {
    ImplicitDepsInferringDescription<A> desc = (ImplicitDepsInferringDescription<A>) description;
    return desc.findDepsForTargetFromConstructorArgs(target, cellRoots, arg);
  }

}
