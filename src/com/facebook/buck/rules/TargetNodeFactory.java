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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.CoercedTypeCache;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public class TargetNodeFactory implements NodeCopier {
  private final TypeCoercerFactory typeCoercerFactory;

  public TargetNodeFactory(TypeCoercerFactory typeCoercerFactory) {
    this.typeCoercerFactory = typeCoercerFactory;
  }

  /**
   * This factory method lets the wildcard be bound, so the constructor can be casted to it.
   *
   * <p>This does no checking that the type of {@code constructorArg} is correct, since {@code
   * Description} does not hold the Class of the constructor arg.
   *
   * <p>See <a href="https://docs.oracle.com/javase/tutorial/java/generics/capture.html">Wildcard
   * Capture and Helper Methods</a>.
   */
  @SuppressWarnings("unchecked")
  public <T, U extends Description<T>> TargetNode<T, U> createFromObject(
      HashCode rawInputsHashCode,
      U description,
      Object constructorArg,
      ProjectFilesystem filesystem,
      BuildTarget buildTarget,
      ImmutableSet<BuildTarget> declaredDeps,
      ImmutableSet<VisibilityPattern> visibilityPatterns,
      ImmutableSet<VisibilityPattern> withinViewPatterns,
      CellPathResolver cellRoots)
      throws NoSuchBuildTargetException {
    return create(
        rawInputsHashCode,
        description,
        (T) constructorArg,
        filesystem,
        buildTarget,
        declaredDeps,
        visibilityPatterns,
        withinViewPatterns,
        cellRoots);
  }

  @SuppressWarnings("unchecked")
  public <T, U extends Description<T>> TargetNode<T, U> create(
      HashCode rawInputsHashCode,
      U description,
      T constructorArg,
      ProjectFilesystem filesystem,
      BuildTarget buildTarget,
      ImmutableSet<BuildTarget> declaredDeps,
      ImmutableSet<VisibilityPattern> visibilityPatterns,
      ImmutableSet<VisibilityPattern> withinViewPatterns,
      CellPathResolver cellRoots)
      throws NoSuchBuildTargetException {

    ImmutableSortedSet.Builder<BuildTarget> extraDepsBuilder = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<BuildTarget> targetGraphOnlyDepsBuilder =
        ImmutableSortedSet.naturalOrder();
    ImmutableSet.Builder<Path> pathsBuilder = ImmutableSet.builder();

    // Scan the input to find possible BuildTargets, necessary for loading dependent rules.
    for (ParamInfo info :
        CoercedTypeCache.INSTANCE
            .getAllParamInfo(typeCoercerFactory, description.getConstructorArgType())
            .values()) {
      if (info.isDep()
          && info.isInput()
          && info.hasElementTypes(BuildTarget.class, SourcePath.class, Path.class)
          && !info.getName().equals("deps")) {
        detectBuildTargetsAndPathsForConstructorArg(
            cellRoots, extraDepsBuilder, pathsBuilder, info, constructorArg);
      }
    }

    if (description instanceof ImplicitDepsInferringDescription) {
      ((ImplicitDepsInferringDescription<T>) description)
          .findDepsForTargetFromConstructorArgs(
              buildTarget, cellRoots, constructorArg, extraDepsBuilder, targetGraphOnlyDepsBuilder);
    }

    if (description instanceof ImplicitInputsInferringDescription) {
      pathsBuilder.addAll(
          ((ImplicitInputsInferringDescription<T>) description)
              .inferInputsFromConstructorArgs(
                  buildTarget.getUnflavoredBuildTarget(), constructorArg));
    }

    // This method uses the TargetNodeFactory, rather than just calling withBuildTarget,
    // because
    // ImplicitDepsInferringDescriptions may give different results for deps based on flavors.
    //
    // Note that this method strips away selected versions, and may be buggy because of it.
    return TargetNode.of(
        buildTarget,
        this,
        rawInputsHashCode,
        description,
        constructorArg,
        filesystem,
        pathsBuilder.build(),
        declaredDeps,
        extraDepsBuilder.build(),
        targetGraphOnlyDepsBuilder.build(),
        cellRoots,
        visibilityPatterns,
        withinViewPatterns,
        Optional.empty());
  }

  private static void detectBuildTargetsAndPathsForConstructorArg(
      CellPathResolver cellRoots,
      final ImmutableSet.Builder<BuildTarget> depsBuilder,
      final ImmutableSet.Builder<Path> pathsBuilder,
      ParamInfo info,
      Object constructorArg)
      throws NoSuchBuildTargetException {
    // We'll make no test for optionality here. Let's assume it's done elsewhere.

    try {
      info.traverse(
          cellRoots,
          object -> {
            if (object instanceof PathSourcePath) {
              pathsBuilder.add(((PathSourcePath) object).getRelativePath());
            } else if (object instanceof BuildTargetSourcePath) {
              depsBuilder.add(((BuildTargetSourcePath) object).getTarget());
            } else if (object instanceof Path) {
              pathsBuilder.add((Path) object);
            } else if (object instanceof BuildTarget) {
              depsBuilder.add((BuildTarget) object);
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
  @Override
  public <T, U extends Description<T>> TargetNode<T, U> copyNodeWithDescription(
      TargetNode<?, ?> originalNode, U description) {
    try {
      return create(
          originalNode.getRawInputsHashCode(),
          description,
          (T) originalNode.getConstructorArg(),
          originalNode.getFilesystem(),
          originalNode.getBuildTarget(),
          originalNode.getDeclaredDeps(),
          originalNode.getVisibilityPatterns(),
          originalNode.getWithinViewPatterns(),
          originalNode.getCellNames());
    } catch (NoSuchBuildTargetException e) {
      throw new IllegalStateException(
          String.format(
              "Caught exception when transforming TargetNode to use a different description: %s",
              originalNode.getBuildTarget()),
          e);
    }
  }

  @Override
  public <T, U extends Description<T>> TargetNode<T, U> copyNodeWithFlavors(
      TargetNode<T, U> originalNode, ImmutableSet<Flavor> flavors) {
    try {
      return create(
          originalNode.getRawInputsHashCode(),
          originalNode.getDescription(),
          originalNode.getConstructorArg(),
          originalNode.getFilesystem(),
          originalNode.getBuildTarget().withFlavors(flavors),
          originalNode.getDeclaredDeps(),
          originalNode.getVisibilityPatterns(),
          originalNode.getWithinViewPatterns(),
          originalNode.getCellNames());
    } catch (NoSuchBuildTargetException e) {
      throw new IllegalStateException(
          String.format(
              "Caught exception when transforming TargetNode to use different flavors: %s",
              originalNode.getBuildTarget()),
          e);
    }
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof TargetNodeFactory)) {
      return false;
    }
    return Objects.equals(typeCoercerFactory, ((TargetNodeFactory) obj).typeCoercerFactory);
  }

  @Override
  public int hashCode() {
    return typeCoercerFactory.hashCode();
  }
}
