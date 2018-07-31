/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.model.targetgraph;

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.HasBuildTarget;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.visibility.ObeysVisibility;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.facebook.buck.versions.Version;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
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
public interface TargetNode<T> extends Comparable<TargetNode<?>>, ObeysVisibility, HasBuildTarget {

  @Override
  BuildTarget getBuildTarget();

  NodeCopier getNodeCopier();

  /** @return A hash of the raw input from the build file used to construct the node. */
  HashCode getRawInputsHashCode();

  BaseDescription<T> getDescription();

  T getConstructorArg();

  ProjectFilesystem getFilesystem();

  ImmutableSet<Path> getInputs();

  ImmutableSet<BuildTarget> getDeclaredDeps();

  ImmutableSortedSet<BuildTarget> getExtraDeps();

  /**
   * BuildTargetPaths which, when changed, may change the BuildRules produced by this TargetNode,
   * but whose steps don't need executing in order to build this TargetNode's BuildRules.
   *
   * <p>A TargetNode may require metadata from other targets in order to be constructed, but may not
   * actually require those targets' build output. For example, some targets may execute queries
   * against the TargetGraph (e.g. detecting the names of rules of a certain type) but don't use the
   * output of those detected rules.
   */
  ImmutableSortedSet<BuildTarget> getTargetGraphOnlyDeps();

  CellPathResolver getCellNames();

  ImmutableSet<VisibilityPattern> getVisibilityPatterns();

  ImmutableSet<VisibilityPattern> getWithinViewPatterns();

  Optional<ImmutableMap<BuildTarget, Version>> getSelectedVersions();

  /** @return all targets which must be built before this one can be. */
  Set<BuildTarget> getBuildDeps();

  /**
   * @return all targets which must be present in the TargetGraph before this one can be transformed
   *     into a BuildRule.
   */
  Set<BuildTarget> getParseDeps();

  /**
   * Stream-style API for getting dependencies. This may return duplicates if certain dependencies
   * are in both declared deps and exported deps.
   *
   * <p>This method can be faster than {@link #getBuildDeps()} in cases where repeated traversals
   * and set operations are not necessary, as it avoids creating the intermediate set.
   */
  Stream<BuildTarget> getBuildDepsStream();

  boolean isVisibleTo(TargetNode<?> viewer);

  void isVisibleToOrThrow(TargetNode<?> viewer);

  RuleType getRuleType();

  TargetNode<T> copy();

  /**
   * This method copies this target node with applying logic in {@link
   * com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription} that may give
   * different results for deps based on flavors.
   *
   * <p>Note that this method strips away selected versions, and may be buggy because of it.
   */
  TargetNode<T> copyWithFlavors(ImmutableSet<Flavor> flavors);

  /**
   * This method copies this target node without applying logic in {@link
   * com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription}
   */
  TargetNode<T> withFlavors(ImmutableSet<Flavor> flavors);

  TargetNode<T> withBuildTarget(BuildTarget buildTarget);

  TargetNode<T> withConstructorArg(T constructorArg);

  TargetNode<T> withDeclaredDeps(Iterable<? extends BuildTarget> declaredDeps);

  TargetNode<T> withExtraDeps(ImmutableSortedSet<BuildTarget> extraDeps);

  TargetNode<T> withTargetGraphOnlyDeps(ImmutableSortedSet<BuildTarget> targetGraphOnlyDeps);

  TargetNode<T> withSelectedVersions(
      Optional<? extends ImmutableMap<BuildTarget, Version>> selectedVersions);
}
