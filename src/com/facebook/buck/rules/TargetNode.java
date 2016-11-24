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
public interface TargetNode<T, U extends Description<T>>
    extends Comparable<TargetNode<?, ?>>, HasBuildTarget {

  /**
   * @return A hash of the raw input from the build file used to construct the node.
   */
  HashCode getRawInputsHashCode();

  U getDescription();

  BuildRuleType getType();

  T getConstructorArg();

  ProjectFilesystem getFilesystem();

  ImmutableSet<Path> getInputs();

  ImmutableSet<BuildTarget> getDeclaredDeps();

  ImmutableSet<BuildTarget> getExtraDeps();

  ImmutableSet<BuildTarget> getDeps();

  boolean isVisibleTo(TargetGraph graph, TargetNode<?, ?> viewer);

  void isVisibleToOrThrow(TargetGraph graphContext, TargetNode<?, ?> viewer);

  /**
   * Type safe checked cast of the constructor arg.
   */
  <V> Optional<TargetNode<V, ?>> castArg(Class<V> cls);

  <V, W extends Description<V>> TargetNode<V, W> withDescription(W description);

  TargetNode<T, U> withFlavors(ImmutableSet<Flavor> flavors);

  TargetNode<T, U> withConstructorArg(T constructorArg);

  TargetNode<T, U> withTargetConstructorArgDepsAndSelectedVersions(
      BuildTarget target,
      T constructorArg,
      ImmutableSet<BuildTarget> declaredDeps,
      ImmutableSet<BuildTarget> extraDeps,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVerisons);

  CellPathResolver getCellNames();

  ImmutableSet<VisibilityPattern> getVisibilityPatterns();

  Optional<ImmutableMap<BuildTarget, Version>> getSelectedVersions();

}
