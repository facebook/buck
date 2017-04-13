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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nonnull;

public class TargetNodeFactory {
  private final TypeCoercerFactory typeCoercerFactory;
  private final LoadingCache<Class<?>, List<ParamInfo>> paramInfoCache = CacheBuilder.newBuilder()
      .build(
          new CacheLoader<Class<?>, List<ParamInfo>>() {
            @Override
            public List<ParamInfo> load(@Nonnull Class<?> key) {
              return computeParamInfosFromArg(key);
            }
          }
      );

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
    T arg = description.createUnpopulatedConstructorArg();
    for (ParamInfo info : paramInfoCache.getUnchecked(arg.getClass())) {
      detectBuildTargetsAndPathsForConstructorArg(
          extraDepsBuilder,
          pathsBuilder,
          info,
          constructorArg);
    }

    if (description instanceof ImplicitDepsInferringDescription) {
        ((ImplicitDepsInferringDescription<T>) description).findDepsForTargetFromConstructorArgs(
            buildTarget,
            cellRoots,
            constructorArg,
            extraDepsBuilder,
            targetGraphOnlyDepsBuilder);
    }

    if (description instanceof ImplicitInputsInferringDescription) {
      pathsBuilder
          .addAll(
              ((ImplicitInputsInferringDescription<T>) description)
                  .inferInputsFromConstructorArgs(
                      buildTarget.getUnflavoredBuildTarget(),
                      constructorArg));
    }

    return new TargetNode<>(
        this,
        rawInputsHashCode,
        description,
        constructorArg,
        filesystem,
        buildTarget,
        declaredDeps,
        ImmutableSortedSet.copyOf(Sets.difference(extraDepsBuilder.build(), declaredDeps)),
        targetGraphOnlyDepsBuilder.build(),
        visibilityPatterns,
        withinViewPatterns,
        pathsBuilder.build(),
        cellRoots,
        Optional.empty());
  }

  private List<ParamInfo> computeParamInfosFromArg(Class<?> key) {
    List<ParamInfo> ret = new ArrayList<>();
    // Scan the input to find possible BuildTargets, necessary for loading dependent rules.
    for (Field field : key.getFields()) {
      ParamInfo info = new ParamInfo(typeCoercerFactory, key, field);
      if (info.isDep() && info.isInput() &&
          info.hasElementTypes(BuildTarget.class, SourcePath.class, Path.class)) {
        ret.add(info);
      }
    }
    return ret;
  }

  private static void detectBuildTargetsAndPathsForConstructorArg(
      final ImmutableSet.Builder<BuildTarget> depsBuilder,
      final ImmutableSet.Builder<Path> pathsBuilder,
      ParamInfo info,
      Object constructorArg) throws NoSuchBuildTargetException {
    // We'll make no test for optionality here. Let's assume it's done elsewhere.

    try {
      info.traverse(
          object -> {
            if (object instanceof PathSourcePath) {
              pathsBuilder.add(((PathSourcePath) object).getRelativePath());
            } else if (object instanceof BuildTargetSourcePath) {
              depsBuilder.add(((BuildTargetSourcePath<?>) object).getTarget());
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
  public <T, U extends Description<T>> TargetNode<T, U> copyNodeWithDescription(
      TargetNode<?, ?> originalNode,
      U description) {
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

  public <T, U extends Description<T>> TargetNode<T, U> copyNodeWithFlavors(
      TargetNode<T, U> originalNode,
      ImmutableSet<Flavor> flavors) {
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
}
