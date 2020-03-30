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

package com.facebook.buck.core.model.targetgraph.impl;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolverProvider;
import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.description.attr.ImplicitInputsInferringDescription;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.ConfigurationForConfigurationTargets;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.targetgraph.NodeCopier;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.rules.config.ConfigurationRuleDescription;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.starlark.rule.SkylarkDescriptionArg;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.rules.coercer.PathTypeCoercer.PathExistenceVerificationMode;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public class TargetNodeFactory implements NodeCopier {

  private final TypeCoercerFactory typeCoercerFactory;
  private final PathsChecker pathsChecker;
  private final CellNameResolverProvider cellNames;

  public TargetNodeFactory(
      TypeCoercerFactory typeCoercerFactory, CellNameResolverProvider cellNames) {
    this(typeCoercerFactory, PathExistenceVerificationMode.VERIFY, cellNames);
  }

  public TargetNodeFactory(
      TypeCoercerFactory typeCoercerFactory,
      PathExistenceVerificationMode pathExistenceVerificationMode,
      CellNameResolverProvider cellNames) {
    this(typeCoercerFactory, getPathsChecker(pathExistenceVerificationMode), cellNames);
  }

  public TargetNodeFactory(
      TypeCoercerFactory typeCoercerFactory,
      PathsChecker pathsChecker,
      CellNameResolverProvider cellNames) {

    this.typeCoercerFactory = typeCoercerFactory;
    this.pathsChecker = pathsChecker;
    this.cellNames = cellNames;
  }

  private static PathsChecker getPathsChecker(
      PathExistenceVerificationMode pathExistenceVerificationMode) {
    if (pathExistenceVerificationMode == PathExistenceVerificationMode.VERIFY) {
      return new MissingPathsChecker();
    }
    return new NoopPathsChecker();
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
  public <T extends ConstructorArg, U extends BaseDescription<T>> TargetNode<T> createFromObject(
      U description,
      Object constructorArg,
      ProjectFilesystem filesystem,
      BuildTarget buildTarget,
      DependencyStack dependencyStack,
      ImmutableSet<BuildTarget> declaredDeps,
      ImmutableSortedSet<BuildTarget> configurationDeps,
      ImmutableSet<VisibilityPattern> visibilityPatterns,
      ImmutableSet<VisibilityPattern> withinViewPatterns)
      throws NoSuchBuildTargetException {
    return create(
        description,
        (T) constructorArg,
        filesystem,
        buildTarget,
        dependencyStack,
        declaredDeps,
        configurationDeps,
        visibilityPatterns,
        withinViewPatterns);
  }

  @SuppressWarnings("unchecked")
  private <T extends ConstructorArg, U extends BaseDescription<T>> TargetNode<T> create(
      U description,
      T constructorArg,
      ProjectFilesystem filesystem,
      BuildTarget buildTarget,
      DependencyStack dependencyStack,
      ImmutableSet<BuildTarget> declaredDeps,
      ImmutableSortedSet<BuildTarget> configurationDeps,
      ImmutableSet<VisibilityPattern> visibilityPatterns,
      ImmutableSet<VisibilityPattern> withinViewPatterns)
      throws NoSuchBuildTargetException {

    boolean isConfigurationRule = description instanceof ConfigurationRuleDescription<?, ?>;

    if (buildTarget
        .getTargetConfiguration()
        .equals(ConfigurationForConfigurationTargets.INSTANCE)) {
      if (!isConfigurationRule) {
        throw new HumanReadableException(
            dependencyStack,
            "%s was used to resolve a configuration rule but it is a build rule",
            buildTarget);
      }
    } else {
      if (isConfigurationRule) {
        throw new HumanReadableException(
            dependencyStack,
            "%s was used to resolve a build rule but it is a configuration rule",
            buildTarget);
      }
    }

    ImmutableSortedSet.Builder<BuildTarget> extraDepsBuilder = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<BuildTarget> targetGraphOnlyDepsBuilder =
        ImmutableSortedSet.naturalOrder();
    ImmutableSet.Builder<ForwardRelativePath> pathsBuilder = ImmutableSet.builder();

    ImmutableMap<String, ParamInfo<?>> paramInfos;
    if (constructorArg instanceof SkylarkDescriptionArg) {
      paramInfos = ((SkylarkDescriptionArg) constructorArg).getAllParamInfo();
    } else {
      paramInfos =
          typeCoercerFactory
              .getConstructorArgDescriptor(
                  (Class<? extends ConstructorArg>) description.getConstructorArgType())
              .getParamInfos();
    }

    CellNameResolver cellNameResolver = cellNames.cellNameResolverForCell(buildTarget.getCell());

    // Scan the input to find possible BuildTargetPaths, necessary for loading dependent rules.
    for (ParamInfo<?> info : paramInfos.values()) {
      if (info.isDep()
          && info.isInput()
          && info.hasElementTypes(BuildTarget.class, SourcePath.class, Path.class)
          && !info.getName().equals("deps")) {
        detectBuildTargetsAndPathsForConstructorArg(
            buildTarget,
            cellNameResolver,
            info.isTargetGraphOnlyDep() ? targetGraphOnlyDepsBuilder : extraDepsBuilder,
            pathsBuilder,
            info,
            constructorArg);
      }
    }

    if (description instanceof ImplicitDepsInferringDescription) {
      ((ImplicitDepsInferringDescription<T>) description)
          .findDepsForTargetFromConstructorArgs(
              buildTarget,
              cellNameResolver,
              constructorArg,
              extraDepsBuilder,
              targetGraphOnlyDepsBuilder);
    }

    if (description instanceof ImplicitInputsInferringDescription) {
      pathsBuilder.addAll(
          ((ImplicitInputsInferringDescription<T>) description)
              .inferInputsFromConstructorArgs(
                  buildTarget.getUnflavoredBuildTarget(), constructorArg));
    }

    ImmutableSet<BuildTarget> configurationDepsFromArg =
        description.getConfigurationDeps(constructorArg);
    if (!configurationDepsFromArg.isEmpty()) {
      configurationDeps =
          ImmutableSortedSet.<BuildTarget>naturalOrder()
              .addAll(configurationDeps)
              .addAll(configurationDepsFromArg)
              .build();
    }

    ImmutableSet<ForwardRelativePath> paths = pathsBuilder.build();
    pathsChecker.checkPaths(filesystem, buildTarget, paths);

    // This method uses the TargetNodeFactory, rather than just calling withBuildTarget,
    // because
    // ImplicitDepsInferringDescriptions may give different results for deps based on flavors.
    //
    // Note that this method strips away selected versions, and may be buggy because of it.
    return TargetNodeImpl.of(
        buildTarget,
        this,
        description,
        constructorArg,
        filesystem,
        paths,
        declaredDeps,
        extraDepsBuilder.build(),
        targetGraphOnlyDepsBuilder.build(),
        configurationDeps,
        visibilityPatterns,
        withinViewPatterns,
        Optional.empty());
  }

  private static void detectBuildTargetsAndPathsForConstructorArg(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      ImmutableSet.Builder<BuildTarget> depsBuilder,
      ImmutableSet.Builder<ForwardRelativePath> pathsBuilder,
      ParamInfo<?> info,
      ConstructorArg constructorArg)
      throws NoSuchBuildTargetException {
    // We'll make no test for optionality here. Let's assume it's done elsewhere.

    try {
      info.traverse(
          cellRoots,
          object -> {
            if (object instanceof PathSourcePath) {
              // We know that coercer returns normalized object, so
              // converting to ForwardRelativePath is OK
              pathsBuilder.add(
                  ForwardRelativePath.ofPath(((PathSourcePath) object).getRelativePath()));
            } else if (object instanceof Path) {
              pathsBuilder.add(ForwardRelativePath.ofPath((Path) object));
            } else if (object instanceof BuildTargetSourcePath) {
              depsBuilder.add(((BuildTargetSourcePath) object).getTarget());
            } else if (object instanceof BuildTarget) {
              depsBuilder.add((BuildTarget) object);
            } else if (object instanceof BuildTargetWithOutputs) {
              depsBuilder.add(((BuildTargetWithOutputs) object).getBuildTarget());
            }
          },
          constructorArg);
    } catch (RuntimeException e) {
      if (e.getCause() instanceof NoSuchBuildTargetException) {
        throw (NoSuchBuildTargetException) e.getCause();
      }
      throw new HumanReadableException(
          e, "Cannot traverse attribute %s of %s: %s", info.getName(), buildTarget, e.getMessage());
    }
  }

  @Override
  public <T extends ConstructorArg> TargetNode<T> copyNodeWithFlavors(
      TargetNode<T> originalNode, ImmutableSet<Flavor> flavors) {
    try {
      return create(
          originalNode.getDescription(),
          originalNode.getConstructorArg(),
          originalNode.getFilesystem(),
          originalNode.getBuildTarget().withFlavors(flavors),
          DependencyStack.top(originalNode.getBuildTarget()),
          originalNode.getDeclaredDeps(),
          originalNode.getConfigurationDeps(),
          originalNode.getVisibilityPatterns(),
          originalNode.getWithinViewPatterns());
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
