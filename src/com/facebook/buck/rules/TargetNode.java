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

import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.ExceptionWithHumanReadableMessage;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.lang.reflect.Field;
import java.nio.file.Path;

/**
 * A {@link TargetNode} represents a node in the target graph which is created by the
 * {@link com.facebook.buck.parser.Parser} as a result of parsing BUCK files in a project. It is
 * responsible for processing the raw (python) inputs of a build rule, and gathering any build
 * targets and paths referenced from those inputs.
 */
public class TargetNode<T> implements Comparable<TargetNode<?>>, HasBuildTarget {

  private final BuildRuleFactoryParams ruleFactoryParams;
  private final Description<T> description;

  private final T constructorArg;

  private final ImmutableSet<Path> pathsReferenced;
  private final ImmutableSet<BuildTarget> declaredDeps;
  private final ImmutableSortedSet<BuildTarget> extraDeps;
  private final ImmutableSet<BuildTargetPattern> visibilityPatterns;

  @SuppressWarnings("unchecked")
  public TargetNode(
      Description<T> description,
      T constructorArg,
      BuildRuleFactoryParams params,
      ImmutableSet<BuildTarget> declaredDeps,
      ImmutableSet<BuildTargetPattern> visibilityPatterns)
      throws NoSuchBuildTargetException, InvalidSourcePathInputException {
    this.description = description;
    this.constructorArg = constructorArg;
    this.ruleFactoryParams = params;

    final ImmutableSet.Builder<Path> paths = ImmutableSet.builder();
    final ImmutableSortedSet.Builder<BuildTarget> extraDeps = ImmutableSortedSet.naturalOrder();

    // Scan the input to find possible BuildTargets, necessary for loading dependent rules.
    TypeCoercerFactory typeCoercerFactory = new TypeCoercerFactory();
    T arg = description.createUnpopulatedConstructorArg();
    for (Field field : arg.getClass().getFields()) {
      ParamInfo<T> info = new ParamInfo<>(typeCoercerFactory, field);
      if (info.isDep() &&
          info.hasElementTypes(BuildTarget.class, SourcePath.class, Path.class)) {
        detectBuildTargetsAndPathsForConstructorArg(extraDeps, paths, info, constructorArg);
      }
    }

    if (description instanceof ImplicitDepsInferringDescription) {
      Iterable<String> rawTargets =
          ((ImplicitDepsInferringDescription<T>) description).findDepsForTargetFromConstructorArgs(
              params.target,
              constructorArg);
      for (String rawTarget : rawTargets) {
        if (isPossiblyATarget(rawTarget)) {
          extraDeps.add(params.resolveBuildTarget(rawTarget));
        }
      }
    }

    this.extraDeps = ImmutableSortedSet.copyOf(Sets.difference(extraDeps.build(), declaredDeps));
    this.pathsReferenced = ruleFactoryParams.enforceBuckPackageBoundary()
        ? verifyPaths(paths.build())
        : paths.build();

    this.declaredDeps = declaredDeps;
    this.visibilityPatterns = visibilityPatterns;
  }

  public Description<T> getDescription() {
    return description;
  }

  public BuildRuleType getType() {
    return description.getBuildRuleType();
  }

  public T getConstructorArg() {
    return constructorArg;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return ruleFactoryParams.target;
  }

  public ImmutableSet<Path> getInputs() {
    return pathsReferenced;
  }

  public ImmutableSet<BuildTarget> getDeclaredDeps() {
    return declaredDeps;
  }

  public ImmutableSet<BuildTarget> getExtraDeps() {
    return extraDeps;
  }

  public ImmutableSet<BuildTarget> getDeps() {
    ImmutableSet.Builder<BuildTarget> builder = ImmutableSet.builder();
    builder.addAll(getDeclaredDeps());
    builder.addAll(getExtraDeps());
    return builder.build();
  }

  public BuildRuleFactoryParams getRuleFactoryParams() {
    return ruleFactoryParams;
  }

  /**
   * TODO(agallagher): It'd be nice to eventually move this implementation to an
   * `AbstractDescription` base class, so that the various types of descriptions
   * can install their own implementations.  However, we'll probably want to move
   * most of what is now `BuildRuleParams` to `DescriptionParams` and set them up
   * while building the target graph.
   */
  public boolean isVisibleTo(BuildTarget other) {
    return BuildTargets.isVisibleTo(
        getBuildTarget(),
        visibilityPatterns,
        other);
  }

  public void checkVisibility(BuildTarget other) {
    if (!isVisibleTo(other)) {
      throw new HumanReadableException(
          "%s depends on %s, which is not visible",
          other,
          getBuildTarget());
    }
  }

  /**
   * Type safe checked cast of the constructor arg.
   */
  @SuppressWarnings("unchecked")
  public <U> Optional<TargetNode<U>> castArg(Class<U> cls) {
    if (cls.isInstance(constructorArg)) {
      return Optional.of((TargetNode<U>) this);
    } else {
      return Optional.absent();
    }
  }

  private void detectBuildTargetsAndPathsForConstructorArg(
      final ImmutableSet.Builder<BuildTarget> depsBuilder,
      final ImmutableSet.Builder<Path> pathsBuilder,
      ParamInfo<T> info,
      T constructorArg) throws NoSuchBuildTargetException {
    // We'll make no test for optionality here. Let's assume it's done elsewhere.

    try {
      info.traverse(
          new ParamInfo.Traversal() {
            @Override
            public void traverse(Object object) {
              if (object instanceof PathSourcePath) {
                pathsBuilder.add(((PathSourcePath) object).getRelativePath());
              } else if (object instanceof BuildTargetSourcePath) {
                depsBuilder.add(((BuildTargetSourcePath) object).getTarget());
              } else if (object instanceof Path) {
                pathsBuilder.add((Path) object);
              } else if (object instanceof BuildTarget) {
                depsBuilder.add((BuildTarget) object);
              }
            }
          },
          constructorArg);
    } catch (RuntimeException e) {
      if (e.getCause() instanceof NoSuchBuildTargetException) {
        throw (NoSuchBuildTargetException) e.getCause();
      }
    }
  }

  private ImmutableSet<Path> verifyPaths(ImmutableSet<Path> paths)
      throws InvalidSourcePathInputException {
    Path basePath = getBuildTarget().getBasePath();
    BuildFileTree buildFileTree = ruleFactoryParams.getBuildFileTree();

    for (Path path : paths) {
      if (!basePath.toString().isEmpty() && !path.startsWith(basePath)) {
        throw new InvalidSourcePathInputException(
            "'%s' in '%s' refers to a parent directory.",
            basePath.relativize(path),
            getBuildTarget());
      }

      Optional<Path> ancestor = buildFileTree.getBasePathOfAncestorTarget(path);
      if (!ancestor.isPresent() || !ancestor.get().equals(basePath)) {
        throw new InvalidSourcePathInputException(
            "'%s' in '%s' crosses a buck package boundary. Find the nearest BUCK file in the " +
                "directory that contains this file and refer to the rule referencing the desired" +
                "file.",
            path,
            getBuildTarget());
      }
    }

    return paths;
  }

  private boolean isPossiblyATarget(String param) {
    return param.startsWith(":") || param.startsWith(BuildTarget.BUILD_TARGET_PREFIX);
  }

  @Override
  public int compareTo(TargetNode<?> o) {
    return getBuildTarget().compareTo(o.getBuildTarget());
  }

  @Override
  public final boolean equals(Object obj) {
    if (!(obj instanceof TargetNode<?>)) {
      return false;
    }
    TargetNode<?> that = (TargetNode<?>) obj;
    return this.getBuildTarget().equals(that.getBuildTarget());
  }

  @Override
  public final int hashCode() {
    return getBuildTarget().hashCode();
  }

  @Override
  public final String toString() {
    return getBuildTarget().getFullyQualifiedName();
  }

  /**
   * Return a copy of the current TargetNode, with the {@link Description} used for creating
   * {@link BuildRule} instances switched out.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public TargetNode<?> amend(Description<?> description) {
    try {
      return new TargetNode(
          description,
          constructorArg,
          ruleFactoryParams,
          declaredDeps,
          visibilityPatterns);
    } catch (InvalidSourcePathInputException | NoSuchBuildTargetException e) {
      // This is extremely unlikely to happen --- we've already created a TargetNode with these
      // values before.
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("serial")
  public static class InvalidSourcePathInputException extends Exception
      implements ExceptionWithHumanReadableMessage{

    private InvalidSourcePathInputException(String message, Object...objects) {
      super(String.format(message, objects));
    }

    @Override
    public String getHumanReadableErrorMessage() {
      return getMessage();
    }
  }
}
