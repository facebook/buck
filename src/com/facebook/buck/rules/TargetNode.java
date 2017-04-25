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
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.versions.Version;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A {@link TargetNode} represents a node in the target graph which is created by the {@link
 * com.facebook.buck.parser.Parser} as a result of parsing BUCK files in a project. It is
 * responsible for processing the raw (python) inputs of a build rule, and gathering any build
 * targets and paths referenced from those inputs.
 */
public class TargetNode<T, U extends Description<T>>
    implements Comparable<TargetNode<?, ?>>, ObeysVisibility {

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
  private final ImmutableSet<BuildTarget> targetGraphOnlyDeps;
  private final VisibilityChecker visibilityChecker;

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
      ImmutableSet<BuildTarget> targetGraphOnlyDeps,
      ImmutableSet<VisibilityPattern> visibilityPatterns,
      ImmutableSet<VisibilityPattern> withinViewPatterns,
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
    this.targetGraphOnlyDeps = targetGraphOnlyDeps;
    this.inputs = paths;
    this.selectedVersions = selectedVersions;
    this.visibilityChecker = new VisibilityChecker(this, visibilityPatterns, withinViewPatterns);
  }

  /** @return A hash of the raw input from the build file used to construct the node. */
  public HashCode getRawInputsHashCode() {
    return rawInputsHashCode;
  }

  public U getDescription() {
    return description;
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

  /** @return all targets which must be built before this one can be. */
  public Set<BuildTarget> getBuildDeps() {
    return Sets.union(declaredDeps, extraDeps);
  }

  /**
   * @return all targets which must be present in the TargetGraph before this one can be transformed
   *     into a BuildRule.
   */
  public Set<BuildTarget> getParseDeps() {
    return Sets.union(getBuildDeps(), targetGraphOnlyDeps);
  }

  /**
   * Stream-style API for getting dependencies. This may return duplicates if certain dependencies
   * are in both declared deps and exported deps.
   *
   * <p>This method can be faster than {@link #getBuildDeps()} in cases where repeated traversals
   * and set operations are not necessary, as it avoids creating the intermediate set.
   */
  public Stream<BuildTarget> getBuildDepsStream() {
    return Stream.concat(getDeclaredDeps().stream(), getExtraDeps().stream());
  }

  /**
   * BuildTargets which, when changed, may change the BuildRules produced by this TargetNode, but
   * whose steps don't need executing in order to build this TargetNode's BuildRules.
   *
   * <p>A TargetNode may require metadata from other targets in order to be constructed, but may not
   * actually require those targets' build output. For example, some targets may execute queries
   * against the TargetGraph (e.g. detecting the names of rules of a certain type) but don't use the
   * output of those detected rules.
   */
  public ImmutableSet<BuildTarget> getTargetGraphOnlyDeps() {
    return targetGraphOnlyDeps;
  }

  @Override
  public VisibilityChecker getVisibilityChecker() {
    return visibilityChecker;
  }

  public boolean isVisibleTo(TargetNode<?, ?> viewer) {
    return visibilityChecker.isVisibleTo(viewer);
  }

  public void isVisibleToOrThrow(TargetNode<?, ?> viewer) {
    if (!isVisibleTo(viewer)) {
      throw new HumanReadableException(
          "%s depends on %s, which is not visible", viewer, getBuildTarget());
    }
  }

  /** Type safe checked cast of the constructor arg. */
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

  public TargetNode<T, U> copyWithFlavors(ImmutableSet<Flavor> flavors) {
    return factory.copyNodeWithFlavors(this, flavors);
  }

  public TargetNode<T, U> withFlavors(ImmutableSet<Flavor> flavors) {
    return new TargetNode<>(
        factory,
        getRawInputsHashCode(),
        getDescription(),
        constructorArg,
        filesystem,
        getBuildTarget().withFlavors(flavors),
        declaredDeps,
        extraDeps,
        targetGraphOnlyDeps,
        getVisibilityPatterns(),
        getWithinViewPatterns(),
        getInputs(),
        getCellNames(),
        getSelectedVersions());
  }

  public TargetNode<T, U> withTargetConstructorArgDepsAndSelectedVerisons(
      BuildTarget target,
      T constructorArg,
      ImmutableSet<BuildTarget> declaredDeps,
      ImmutableSet<BuildTarget> extraDeps,
      ImmutableSet<BuildTarget> targetGraphOnlyDeps,
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
        targetGraphOnlyDeps,
        getVisibilityPatterns(),
        getWithinViewPatterns(),
        getInputs(),
        getCellNames(),
        selectedVerisons);
  }

  public CellPathResolver getCellNames() {
    return cellNames;
  }

  public ImmutableSet<VisibilityPattern> getVisibilityPatterns() {
    return visibilityChecker.getVisibilityPatterns();
  }

  public ImmutableSet<VisibilityPattern> getWithinViewPatterns() {
    return visibilityChecker.getWithinViewPatterns();
  }

  public Optional<ImmutableMap<BuildTarget, Version>> getSelectedVersions() {
    return selectedVersions;
  }
}
