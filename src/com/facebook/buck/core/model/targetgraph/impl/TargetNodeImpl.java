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

import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.description.impl.DescriptionCache;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.targetgraph.NodeCopier;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.util.immutables.BuckStylePrehashedValue;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.visibility.VisibilityChecker;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.facebook.buck.versions.Version;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

/**
 * A {@link TargetNode} represents a node in the target graph which is created by the {@link
 * com.facebook.buck.parser.Parser} as a result of parsing BUCK files in a project. It is
 * responsible for processing the raw (python) inputs of a build rule, and gathering any build
 * targets and paths referenced from those inputs.
 */
@BuckStylePrehashedValue
public abstract class TargetNodeImpl<T extends ConstructorArg> implements TargetNode<T> {

  @Value.Check
  protected void check() {
    Preconditions.checkArgument(
        getFilesystem().getBuckPaths().getCellName().equals(getBuildTarget().getCell()),
        "filesystem cell '%s' must match target cell: %s",
        getFilesystem().getBuckPaths().getCellName(),
        getBuildTarget());
  }

  @Override
  public abstract BuildTarget getBuildTarget();

  @Override
  public abstract NodeCopier getNodeCopier();

  // TODO(#22139496): Currently, `Descriptions` don't implement content equality, so we exclude it
  // from the `equals`/`hashCode` implementation of `TargetNode`.  This should be fine, as we
  // already rely on restarting the daemon if the descriptions change in any meaningful way to
  // maintain parser cache integrity.

  @Value.Auxiliary
  @Override
  public abstract BaseDescription<T> getDescription();

  @Override
  public abstract T getConstructorArg();

  @Override
  public abstract ProjectFilesystem getFilesystem();

  @Override
  public abstract ImmutableSet<ForwardRelativePath> getInputs();

  @Override
  public abstract ImmutableSet<BuildTarget> getDeclaredDeps();

  @Override
  public abstract ImmutableSortedSet<BuildTarget> getExtraDeps();

  /**
   * BuildTargetPaths which, when changed, may change the BuildRules produced by this TargetNode,
   * but whose steps don't need executing in order to build this TargetNode's BuildRules.
   *
   * <p>A TargetNode may require metadata from other targets in order to be constructed, but may not
   * actually require those targets' build output. For example, some targets may execute queries
   * against the TargetGraph (e.g. detecting the names of rules of a certain type) but don't use the
   * output of those detected rules.
   */
  @Override
  public abstract ImmutableSortedSet<BuildTarget> getTargetGraphOnlyDeps();

  @Override
  public abstract ImmutableSortedSet<BuildTarget> getConfigurationDeps();

  @Override
  public abstract ImmutableSet<VisibilityPattern> getVisibilityPatterns();

  @Override
  public abstract ImmutableSet<VisibilityPattern> getWithinViewPatterns();

  @Override
  public abstract Optional<ImmutableMap<BuildTarget, Version>> getSelectedVersions();

  @Override
  @Value.Lazy
  public VisibilityChecker getVisibilityChecker() {
    return new VisibilityChecker(this, getVisibilityPatterns(), getWithinViewPatterns());
  }

  /** @return all targets which must be built before this one can be. */
  @Override
  public Set<BuildTarget> getBuildDeps() {
    return Sets.union(getDeclaredDeps(), getExtraDeps());
  }

  /**
   * @return all targets which must be present in the TargetGraph before this one can be transformed
   *     into a BuildRule.
   */
  @Override
  public Set<BuildTarget> getParseDeps() {
    return Sets.union(getBuildDeps(), getTargetGraphOnlyDeps());
  }

  @Override
  public Set<BuildTarget> getTotalDeps() {
    return Sets.union(getParseDeps(), getConfigurationDeps());
  }

  @Override
  public boolean isVisibleTo(TargetNode<?> viewer) {
    return getVisibilityChecker().isVisibleTo(viewer);
  }

  @Override
  public void isVisibleToOrThrow(TargetNode<?> viewer) {
    if (!isVisibleTo(viewer)) {
      throw new HumanReadableException(
          "%s depends on %s, which is not visible. More info at:\nhttps://buck.build/concept/visibility.html",
          viewer, getBuildTarget());
    }
  }

  @Override
  public RuleType getRuleType() {
    return DescriptionCache.getRuleType(getDescription());
  }

  @Override
  public int compareTo(TargetNode<?> o) {
    return getBuildTarget().compareTo(o.getBuildTarget());
  }

  @Override
  public final String toString() {
    return getBuildTarget().getFullyQualifiedName();
  }

  @Override
  public TargetNode<T> copyWithFlavors(ImmutableSet<Flavor> flavors) {
    return getNodeCopier().copyNodeWithFlavors(this, flavors);
  }

  @Override
  public TargetNodeImpl<T> withFlavors(ImmutableSet<Flavor> flavors) {
    return ImmutableTargetNodeImpl.of(
        getBuildTarget().withFlavors(flavors),
        getNodeCopier(),
        getDescription(),
        getConstructorArg(),
        getFilesystem(),
        getInputs(),
        getDeclaredDeps(),
        getExtraDeps(),
        getTargetGraphOnlyDeps(),
        getConfigurationDeps(),
        getVisibilityPatterns(),
        getWithinViewPatterns(),
        getSelectedVersions());
  }

  @Override
  public TargetNodeImpl<T> withBuildTarget(BuildTarget buildTarget) {
    return ImmutableTargetNodeImpl.of(
        buildTarget,
        getNodeCopier(),
        getDescription(),
        getConstructorArg(),
        getFilesystem(),
        getInputs(),
        getDeclaredDeps(),
        getExtraDeps(),
        getTargetGraphOnlyDeps(),
        getConfigurationDeps(),
        getVisibilityPatterns(),
        getWithinViewPatterns(),
        getSelectedVersions());
  }

  @Override
  public TargetNodeImpl<T> withConstructorArg(T constructorArg) {
    return ImmutableTargetNodeImpl.of(
        getBuildTarget(),
        getNodeCopier(),
        getDescription(),
        constructorArg,
        getFilesystem(),
        getInputs(),
        getDeclaredDeps(),
        getExtraDeps(),
        getTargetGraphOnlyDeps(),
        getConfigurationDeps(),
        getVisibilityPatterns(),
        getWithinViewPatterns(),
        getSelectedVersions());
  }

  @Override
  public TargetNodeImpl<T> withDeclaredDeps(Iterable<? extends BuildTarget> declaredDeps) {
    return ImmutableTargetNodeImpl.of(
        getBuildTarget(),
        getNodeCopier(),
        getDescription(),
        getConstructorArg(),
        getFilesystem(),
        getInputs(),
        declaredDeps,
        getExtraDeps(),
        getTargetGraphOnlyDeps(),
        getConfigurationDeps(),
        getVisibilityPatterns(),
        getWithinViewPatterns(),
        getSelectedVersions());
  }

  @Override
  public TargetNodeImpl<T> withExtraDeps(ImmutableSortedSet<BuildTarget> extraDeps) {
    return ImmutableTargetNodeImpl.of(
        getBuildTarget(),
        getNodeCopier(),
        getDescription(),
        getConstructorArg(),
        getFilesystem(),
        getInputs(),
        getDeclaredDeps(),
        extraDeps,
        getTargetGraphOnlyDeps(),
        getConfigurationDeps(),
        getVisibilityPatterns(),
        getWithinViewPatterns(),
        getSelectedVersions());
  }

  @Override
  public TargetNodeImpl<T> withTargetGraphOnlyDeps(
      ImmutableSortedSet<BuildTarget> targetGraphOnlyDeps) {
    return ImmutableTargetNodeImpl.of(
        getBuildTarget(),
        getNodeCopier(),
        getDescription(),
        getConstructorArg(),
        getFilesystem(),
        getInputs(),
        getDeclaredDeps(),
        getExtraDeps(),
        targetGraphOnlyDeps,
        getConfigurationDeps(),
        getVisibilityPatterns(),
        getWithinViewPatterns(),
        getSelectedVersions());
  }

  @Override
  public TargetNodeImpl<T> withSelectedVersions(
      Optional<? extends ImmutableMap<BuildTarget, Version>> selectedVersions) {
    return ImmutableTargetNodeImpl.of(
        getBuildTarget(),
        getNodeCopier(),
        getDescription(),
        getConstructorArg(),
        getFilesystem(),
        getInputs(),
        getDeclaredDeps(),
        getExtraDeps(),
        getTargetGraphOnlyDeps(),
        getConfigurationDeps(),
        getVisibilityPatterns(),
        getWithinViewPatterns(),
        selectedVersions);
  }

  public static <T extends ConstructorArg> TargetNodeImpl<T> of(
      BuildTarget buildTarget,
      NodeCopier nodeCopier,
      BaseDescription<T> description,
      T constructorArg,
      ProjectFilesystem filesystem,
      ImmutableSet<ForwardRelativePath> inputs,
      ImmutableSet<BuildTarget> declaredDeps,
      ImmutableSortedSet<BuildTarget> extraDeps,
      ImmutableSortedSet<BuildTarget> targetGraphOnlyDeps,
      ImmutableSortedSet<BuildTarget> configurationDeps,
      ImmutableSet<VisibilityPattern> visibilityPatterns,
      ImmutableSet<VisibilityPattern> withinViewPatterns,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions) {
    return ImmutableTargetNodeImpl.of(
        buildTarget,
        nodeCopier,
        description,
        constructorArg,
        filesystem,
        inputs,
        declaredDeps,
        extraDeps,
        targetGraphOnlyDeps,
        configurationDeps,
        visibilityPatterns,
        withinViewPatterns,
        selectedVersions);
  }
}
