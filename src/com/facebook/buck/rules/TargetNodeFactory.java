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
package com.facebook.buck.rules;

import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.ExceptionWithHumanReadableMessage;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;

import java.lang.reflect.Field;
import java.nio.file.Path;

public class TargetNodeFactory {
  private final TypeCoercerFactory typeCoercerFactory;

  public TargetNodeFactory(TypeCoercerFactory typeCoercerFactory) {
    this.typeCoercerFactory = typeCoercerFactory;
  }

  /**
   * This factory method lets the wildcard be bound, so the constructor can be casted to it.
   *
   * This does no checking that the type of {@code constructorArg} is correct, since
   * {@code Description} does not hold the Class of the constructor arg.
   *
   * See <a href="https://docs.oracle.com/javase/tutorial/java/generics/capture.html">Wildcard Capture and Helper Methods</a>.
   */
  @SuppressWarnings("unchecked")
  public <T> TargetNode<T> createFromObject(
      HashCode rawInputsHashCode,
      Description<T> description,
      Object constructorArg,
      BuildRuleFactoryParams params,
      ImmutableSet<BuildTarget> declaredDeps,
      ImmutableSet<VisibilityPattern> visibilityPatterns,
      CellPathResolver cellRoots)
      throws NoSuchBuildTargetException, InvalidSourcePathInputException {
    return create(
        rawInputsHashCode,
        description,
        (T) constructorArg,
        params,
        declaredDeps,
        visibilityPatterns,
        cellRoots);
  }

  @SuppressWarnings("unchecked")
  public <T> TargetNode<T> create(
      HashCode rawInputsHashCode,
      Description<T> description,
      T constructorArg,
      BuildRuleFactoryParams params,
      ImmutableSet<BuildTarget> declaredDeps,
      ImmutableSet<VisibilityPattern> visibilityPatterns,
      CellPathResolver cellRoots)
      throws NoSuchBuildTargetException, InvalidSourcePathInputException {

    ImmutableSortedSet.Builder<BuildTarget> extraDepsBuilder = ImmutableSortedSet.naturalOrder();
    ImmutableSet.Builder<Path> pathsBuilder = ImmutableSet.builder();

    // Scan the input to find possible BuildTargets, necessary for loading dependent rules.
    T arg = description.createUnpopulatedConstructorArg();
    for (Field field : arg.getClass().getFields()) {
      ParamInfo info = new ParamInfo(typeCoercerFactory, field);
      if (info.isDep() && info.isInput() &&
          info.hasElementTypes(BuildTarget.class, SourcePath.class, Path.class)) {
        detectBuildTargetsAndPathsForConstructorArg(
            extraDepsBuilder,
            pathsBuilder,
            info,
            constructorArg);
      }
    }

    if (description instanceof ImplicitDepsInferringDescription) {
      extraDepsBuilder
          .addAll(
              ((ImplicitDepsInferringDescription<T>) description)
                  .findDepsForTargetFromConstructorArgs(params.target, cellRoots, constructorArg));
    }

    if (description instanceof ImplicitInputsInferringDescription) {
      pathsBuilder
          .addAll(
              ((ImplicitInputsInferringDescription<T>) description)
                  .inferInputsFromConstructorArgs(
                      params.target.getUnflavoredBuildTarget(),
                      constructorArg));
    }

    ImmutableSet<Path> paths = pathsBuilder.build();
    if (params.enforceBuckPackageBoundary()) {
      verifyPaths(params.target, params.getBuildFileTree(), paths);
    }

    return new TargetNode<>(
        this,
        rawInputsHashCode,
        description,
        constructorArg,
        params,
        declaredDeps,
        ImmutableSortedSet.copyOf(Sets.difference(extraDepsBuilder.build(), declaredDeps)),
        visibilityPatterns,
        paths,
        cellRoots);
  }

  private static void detectBuildTargetsAndPathsForConstructorArg(
      final ImmutableSet.Builder<BuildTarget> depsBuilder,
      final ImmutableSet.Builder<Path> pathsBuilder,
      ParamInfo info,
      Object constructorArg) throws NoSuchBuildTargetException {
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

  @SuppressWarnings("unchecked")
  public <T> TargetNode<T> copyNodeWithDescription(
      TargetNode<?> originalNode,
      Description<T> description) {
    try {
      return create(
          originalNode.getRawInputsHashCode(),
          description,
          (T) originalNode.getConstructorArg(),
          originalNode.getRuleFactoryParams(),
          originalNode.getDeclaredDeps(),
          originalNode.getVisibilityPatterns(),
          originalNode.getCellNames());
    } catch (InvalidSourcePathInputException | NoSuchBuildTargetException e) {
      throw new IllegalStateException(
          String.format(
              "Caught exception when transforming TargetNode to use a different description: %s",
              originalNode.getBuildTarget()),
          e);
    }
  }

  public <T> TargetNode<T> copyNodeWithFlavors(
      TargetNode<T> originalNode,
      ImmutableSet<Flavor> flavors) {
    try {
      return create(
          originalNode.getRawInputsHashCode(),
          originalNode.getDescription(),
          originalNode.getConstructorArg(),
          originalNode.getRuleFactoryParams().withFlavors(flavors),
          originalNode.getDeclaredDeps(),
          originalNode.getVisibilityPatterns(),
          originalNode.getCellNames());
    } catch (InvalidSourcePathInputException | NoSuchBuildTargetException e) {
      throw new IllegalStateException(
          String.format(
              "Caught exception when transforming TargetNode to use different flavors: %s",
              originalNode.getBuildTarget()),
          e);
    }
  }

  private static ImmutableSet<Path> verifyPaths(
      BuildTarget target,
      BuildFileTree buildFileTree,
      ImmutableSet<Path> paths)
      throws InvalidSourcePathInputException {
    Path basePath = target.getBasePath();

    for (Path path : paths) {
      if (!basePath.toString().isEmpty() && !path.startsWith(basePath)) {
        throw new InvalidSourcePathInputException(
            "'%s' in '%s' refers to a parent directory.",
            basePath.relativize(path),
            target);
      }

      Optional<Path> ancestor = buildFileTree.getBasePathOfAncestorTarget(path);
      // It should not be possible for us to ever get an Optional.absent() for this because that
      // would require one of two conditions:
      // 1) The source path references parent directories, which we check for above.
      // 2) You don't have a build file above this file, which is impossible if it is referenced in
      //    a build file *unless* you happen to be referencing something that is ignored.
      if (!ancestor.isPresent()) {
        throw new InvalidSourcePathInputException(
            "'%s' in '%s' crosses a buck package boundary.  This is probably caused by " +
                "specifying one of the folders in '%s' in your .buckconfig under `project.ignore`.",
            path,
            target,
            path);
      }
      if (!ancestor.get().equals(basePath)) {
        throw new InvalidSourcePathInputException(
            "'%s' in '%s' crosses a buck package boundary.  This file is owned by '%s'.  Find " +
                "the owning rule that references '%s', and use a reference to that rule instead " +
                "of referencing the desired file directly.",
            path,
            target,
            ancestor.get(),
            path);
      }
    }

    return paths;
  }

  @SuppressWarnings("serial")
  public static class InvalidSourcePathInputException extends Exception
      implements ExceptionWithHumanReadableMessage {

    private InvalidSourcePathInputException(String message, Object...objects) {
      super(String.format(message, objects));
    }

    @Override
    public String getHumanReadableErrorMessage() {
      return getMessage();
    }
  }
}
