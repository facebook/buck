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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.visibility.ObeysVisibility;
import com.facebook.buck.rules.visibility.VisibilityChecker;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.Version;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.immutables.value.Value;

/**
 * A {@link TargetNode} represents a node in the target graph which is created by the {@link
 * com.facebook.buck.parser.Parser} as a result of parsing BUCK files in a project. It is
 * responsible for processing the raw (python) inputs of a build rule, and gathering any build
 * targets and paths referenced from those inputs.
 */
@BuckStyleImmutable
@Value.Immutable(builder = false, prehash = true)
abstract class AbstractTargetNode<T, U extends Description<T>>
    implements Comparable<TargetNode<?, ?>>, ObeysVisibility {

  @Value.Parameter
  @Override
  public abstract BuildTarget getBuildTarget();

  @Value.Parameter
  public abstract NodeCopier getNodeCopier();

  /** @return A hash of the raw input from the build file used to construct the node. */
  @Value.Parameter
  public abstract HashCode getRawInputsHashCode();

  // TODO(#22139496): Currently, `Descriptions` don't implement content equality, so we exclude it
  // from the `equals`/`hashCode` implementation of `TargetNode`.  This should be fine, as we
  // already rely on restarting the daemon if the descriptions change in any meaningful way to
  // maintain parser cache integrity.
  @Value.Parameter
  @Value.Auxiliary
  public abstract U getDescription();

  @Value.Parameter
  public abstract T getConstructorArg();

  @Value.Parameter
  public abstract ProjectFilesystem getFilesystem();

  @Value.Parameter
  public abstract ImmutableSet<Path> getInputs();

  @Value.Parameter
  public abstract ImmutableSet<BuildTarget> getDeclaredDeps();

  @Value.Parameter
  public abstract ImmutableSortedSet<BuildTarget> getExtraDeps();

  /**
   * BuildTargets which, when changed, may change the BuildRules produced by this TargetNode, but
   * whose steps don't need executing in order to build this TargetNode's BuildRules.
   *
   * <p>A TargetNode may require metadata from other targets in order to be constructed, but may not
   * actually require those targets' build output. For example, some targets may execute queries
   * against the TargetGraph (e.g. detecting the names of rules of a certain type) but don't use the
   * output of those detected rules.
   */
  @Value.Parameter
  public abstract ImmutableSortedSet<BuildTarget> getTargetGraphOnlyDeps();

  @Value.Parameter
  public abstract CellPathResolver getCellNames();

  @Value.Parameter
  public abstract ImmutableSet<VisibilityPattern> getVisibilityPatterns();

  @Value.Parameter
  public abstract ImmutableSet<VisibilityPattern> getWithinViewPatterns();

  @Value.Parameter
  public abstract Optional<ImmutableMap<BuildTarget, Version>> getSelectedVersions();

  @Override
  @Value.Lazy
  public VisibilityChecker getVisibilityChecker() {
    return new VisibilityChecker(this, getVisibilityPatterns(), getWithinViewPatterns());
  }

  /** @return all targets which must be built before this one can be. */
  public Set<BuildTarget> getBuildDeps() {
    return Sets.union(getDeclaredDeps(), getExtraDeps());
  }

  /**
   * @return all targets which must be present in the TargetGraph before this one can be transformed
   *     into a BuildRule.
   */
  public Set<BuildTarget> getParseDeps() {
    return Sets.union(getBuildDeps(), getTargetGraphOnlyDeps());
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

  public boolean isVisibleTo(TargetNode<?, ?> viewer) {
    return getVisibilityChecker().isVisibleTo(viewer);
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
    if (cls.isInstance(getConstructorArg())) {
      return Optional.of((TargetNode<V, ?>) TargetNode.copyOf(this));
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

  // This method uses the TargetNodeFactory, rather than just calling withDescription, because
  // ImplicitDepsInferringDescriptions may give different results for deps.
  //
  // Note that this method strips away selected versions, and may be buggy because of it.
  public <V, W extends Description<V>> TargetNode<V, W> copyWithDescription(W description) {
    return getNodeCopier().copyNodeWithDescription(TargetNode.copyOf(this), description);
  }

  // This method uses the TargetNodeFactory, rather than just calling withBuildTarget, because
  // ImplicitDepsInferringDescriptions may give different results for deps based on flavors.
  //
  // Note that this method strips away selected versions, and may be buggy because of it.
  public TargetNode<T, U> copyWithFlavors(ImmutableSet<Flavor> flavors) {
    return getNodeCopier().copyNodeWithFlavors(TargetNode.copyOf(this), flavors);
  }

  // Note that this method bypasses TargetNodeFactory, and may be buggy because it doesn't
  // re-compute ImplicitDepsInferringDescription-detected deps..
  public TargetNode<T, U> withFlavors(ImmutableSet<Flavor> flavors) {
    return TargetNode.copyOf(this).withBuildTarget(getBuildTarget().withFlavors(flavors));
  }
}
