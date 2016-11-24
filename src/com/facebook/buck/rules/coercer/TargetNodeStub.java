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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.VisibilityPattern;
import com.facebook.buck.versions.Version;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;

import java.nio.file.Path;
import java.util.Optional;

public class TargetNodeStub<T, U extends Description<T>> implements TargetNode<T, U> {

  private final BuildTarget target;
  private final Class<T> constructorArgType;
  private final Class<U> descriptionType;

  TargetNodeStub(
      BuildTarget target,
      Class<T> constructorArgType,
      Class<U> descriptionType) {
    this.target = target;
    this.constructorArgType = constructorArgType;
    this.descriptionType = descriptionType;
  }

  public Class<T> getConstructorArgType() {
    return constructorArgType;
  }

  public Class<U> getDescriptionType() {
    return descriptionType;
  }

  @Override
  public HashCode getRawInputsHashCode() {
    throw new UnsupportedOperationException();
  }

  @Override
  public U getDescription() {
    throw new UnsupportedOperationException();
  }

  @Override
  public BuildRuleType getType() {
    throw new UnsupportedOperationException();
  }

  @Override
  public T getConstructorArg() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ProjectFilesystem getFilesystem() {
    throw new UnsupportedOperationException();
  }

  @Override
  public BuildTarget getBuildTarget() {
    return target;
  }

  @Override
  public ImmutableSet<Path> getInputs() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableSet<BuildTarget> getDeclaredDeps() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableSet<BuildTarget> getExtraDeps() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableSet<BuildTarget> getDeps() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isVisibleTo(TargetGraph graph, TargetNode<?, ?> viewer) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void isVisibleToOrThrow(TargetGraph graphContext, TargetNode<?, ?> viewer) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <V> Optional<TargetNode<V, ?>> castArg(Class<V> cls) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int compareTo(TargetNode<?, ?> o) {
    return getBuildTarget().compareTo(o.getBuildTarget());
  }

  @Override
  public final String toString() {
    return getBuildTarget().getFullyQualifiedName();
  }

  @Override
  public <V, W extends Description<V>> TargetNode<V, W> withDescription(W description) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TargetNode<T, U> withFlavors(ImmutableSet<Flavor> flavors) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TargetNode<T, U> withConstructorArg(T constructorArg) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TargetNode<T, U> withTargetConstructorArgDepsAndSelectedVerisons(
      BuildTarget target,
      T constructorArg,
      ImmutableSet<BuildTarget> declaredDeps,
      ImmutableSet<BuildTarget> extraDeps,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVerisons) {
    throw new UnsupportedOperationException();
  }

  @Override
  public CellPathResolver getCellNames() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableSet<VisibilityPattern> getVisibilityPatterns() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<ImmutableMap<BuildTarget, Version>> getSelectedVersions() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int hashCode() {
    return getBuildTarget().hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof TargetNode<?, ?>)) {
      return false;
    }
    TargetNode<?, ?> other = (TargetNode<?, ?>) obj;
    return getBuildTarget().equals(other.getBuildTarget());
  }
}
