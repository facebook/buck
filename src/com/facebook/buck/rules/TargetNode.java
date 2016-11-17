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
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.versions.Version;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;

import java.nio.file.Path;
import java.util.Optional;

/**
 * A {@link TargetNode} represents a node in the target graph which is created by the
 * {@link com.facebook.buck.parser.Parser} as a result of parsing BUCK files in a project. It is
 * responsible for processing the raw (python) inputs of a build rule, and gathering any build
 * targets and paths referenced from those inputs.
 */
public class TargetNode<T, U extends Description<T>>
    implements Comparable<TargetNode<?, ?>>, HasBuildTarget {

  private final TargetNodeFactory factory;

  private final HashCode rawInputsHashCode;
  private final ProjectFilesystem filesystem;
  private final BuildTarget buildTarget;
  private final CellPathResolver cellNames;

  private final U description;

  private final T constructorArg;
  private final ImmutableSet<Path> inputs;
  private final ImmutableSet<BuildTarget> declaredDeps;
  private final ImmutableSet<BuildTarget> extraDeps;
  private final ImmutableSet<VisibilityPattern> visibilityPatterns;

  private final Optional<ImmutableMap<BuildTarget, Version>> selectedVersions;

  TargetNode(
      TargetNodeFactory factory,
      HashCode rawInputsHashCode,
      U description,
      T constructorArg,
      ProjectFilesystem filesystem,
      BuildTarget buildTarget,
      ImmutableSet<BuildTarget> declaredDeps,
      ImmutableSet<BuildTarget> extraDeps,
      ImmutableSet<VisibilityPattern> visibilityPatterns,
      ImmutableSet<Path> paths,
      CellPathResolver cellNames,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions) {
    this.factory = factory;
    this.rawInputsHashCode = rawInputsHashCode;
    this.description = description;
    this.constructorArg = constructorArg;
    this.filesystem = filesystem;
    this.buildTarget = buildTarget;
    this.cellNames = cellNames;
    this.declaredDeps = declaredDeps;
    this.extraDeps = extraDeps;
    this.inputs = paths;
    this.visibilityPatterns = visibilityPatterns;
    this.selectedVersions = selectedVersions;
  }

  /**
   * @return A hash of the raw input from the build file used to construct the node.
   */
  public HashCode getRawInputsHashCode() {
    return rawInputsHashCode;
  }

  public U getDescription() {
    return description;
  }

  public BuildRuleType getType() {
    return Description.getBuildRuleType(description);
  }

  public T getConstructorArg() {
    return constructorArg;
  }

  public ProjectFilesystem getFilesystem() {
    return filesystem;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  public ImmutableSet<Path> getInputs() {
    return inputs;
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

  /**
   * TODO(andrewjcg): It'd be nice to eventually move this implementation to an
   * `AbstractDescription` base class, so that the various types of descriptions
   * can install their own implementations.  However, we'll probably want to move
   * most of what is now `BuildRuleParams` to `DescriptionParams` and set them up
   * while building the target graph.
   */
  public boolean isVisibleTo(TargetGraph graph, TargetNode<?, ?> viewer) {

    // if i am in a restricted visibility group that the viewer isn't, the viewer can't see me.
    // this check *must* take priority even over the sibling check, because if it didn't then that
    // would introduce siblings as a way to "leak" visibility out of restricted groups.
    for (TargetGroup targetGroup : graph.getGroupsContainingTarget(viewer.getBuildTarget())) {
      if (targetGroup.restrictsOutboundVisibility() &&
          !targetGroup.containsTarget(getBuildTarget())) {
        return false;
      }
    }

    if (getBuildTarget().getCellPath().equals(viewer.getBuildTarget().getCellPath()) &&
        getBuildTarget().getBaseName().equals(viewer.getBuildTarget().getBaseName())) {
      return true;
    }

    for (VisibilityPattern pattern : visibilityPatterns) {
      if (pattern.checkVisibility(graph, viewer, this)) {
        return true;
      }
    }

    return false;
  }

  public void isVisibleToOrThrow(TargetGraph graphContext, TargetNode<?, ?> viewer) {
    if (!isVisibleTo(graphContext, viewer)) {
      throw new HumanReadableException(
          "%s depends on %s, which is not visible",
          viewer,
          getBuildTarget());
    }
  }

  /**
   * Type safe checked cast of the constructor arg.
   */
  @SuppressWarnings("unchecked")
  public <V> Optional<TargetNode<V, ?>> castArg(Class<V> cls) {
    if (cls.isInstance(constructorArg)) {
      return Optional.of((TargetNode<V, ?>) this);
    } else {
      return Optional.empty();
    }
  }

  @Override
  public int compareTo(TargetNode<?, ?> o) {
    return getBuildTarget().compareTo(o.getBuildTarget());
  }

  @Override
  public final String toString() {
    return getBuildTarget().getFullyQualifiedName();
  }

  public <V, W extends Description<V>> TargetNode<V, W> withDescription(W description) {
    return factory.copyNodeWithDescription(this, description);
  }

  public TargetNode<T, U> withFlavors(ImmutableSet<Flavor> flavors) {
    return factory.copyNodeWithFlavors(this, flavors);
  }

  public TargetNode<T, U> withTargetConstructorArgDepsAndSelectedVerisons(
      BuildTarget target,
      T constructorArg,
      ImmutableSet<BuildTarget> declaredDeps,
      ImmutableSet<BuildTarget> extraDeps,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVerisons) {
    return new TargetNode<>(
        factory,
        getRawInputsHashCode(),
        getDescription(),
        constructorArg,
        filesystem,
        target,
        declaredDeps,
        extraDeps,
        getVisibilityPatterns(),
        getInputs(),
        getCellNames(),
        selectedVerisons);
  }

  public CellPathResolver getCellNames() {
    return cellNames;
  }

  public ImmutableSet<VisibilityPattern> getVisibilityPatterns() {
    return visibilityPatterns;
  }

  public Optional<ImmutableMap<BuildTarget, Version>> getSelectedVersions() {
    return selectedVersions;
  }

}
