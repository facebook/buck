/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.features.project.intellij.model.DependencyType;
import com.facebook.buck.features.project.intellij.model.IjLibrary;
import com.facebook.buck.features.project.intellij.model.IjModule;
import com.facebook.buck.features.project.intellij.model.IjProjectElement;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Represents a graph of IjModules and the dependencies between them. */
public class IjModuleGraph {

  private ImmutableMap<IjProjectElement, ImmutableMap<IjProjectElement, DependencyType>> deps;

  public IjModuleGraph(
      ImmutableMap<IjProjectElement, ImmutableMap<IjProjectElement, DependencyType>> deps) {
    this.deps = deps;
    checkNamesAreUnique(deps);
  }

  public ImmutableSet<IjProjectElement> getNodes() {
    return deps.keySet();
  }

  public ImmutableSet<IjModule> getModules() {
    return deps.keySet()
        .stream()
        .filter(dep -> dep instanceof IjModule)
        .map(IjModule.class::cast)
        .collect(ImmutableSet.toImmutableSet());
  }

  public ImmutableSet<IjLibrary> getLibraries() {
    return deps.keySet()
        .stream()
        .filter(node -> node instanceof IjLibrary)
        .map(IjLibrary.class::cast)
        .collect(ImmutableSet.toImmutableSet());
  }

  public ImmutableMap<IjProjectElement, DependencyType> getDepsFor(IjProjectElement source) {
    return Optional.ofNullable(deps.get(source)).orElse(ImmutableMap.of());
  }

  public ImmutableMap<IjModule, DependencyType> getDependentModulesFor(IjModule source) {
    ImmutableMap<IjProjectElement, DependencyType> deps = getDepsFor(source);
    return deps.keySet()
        .stream()
        .filter(dep -> dep instanceof IjModule)
        .map(module -> (IjModule) module)
        .collect(
            ImmutableMap.toImmutableMap(k -> k, input -> Objects.requireNonNull(deps.get(input))));
  }

  public ImmutableMap<IjLibrary, DependencyType> getDependentLibrariesFor(IjModule source) {
    ImmutableMap<IjProjectElement, DependencyType> deps = getDepsFor(source);
    return deps.keySet()
        .stream()
        .filter(dep -> dep instanceof IjLibrary)
        .map(library -> (IjLibrary) library)
        .collect(
            ImmutableMap.toImmutableMap(k -> k, input -> Objects.requireNonNull(deps.get(input))));
  }

  private static void checkNamesAreUnique(
      ImmutableMap<IjProjectElement, ImmutableMap<IjProjectElement, DependencyType>> deps) {
    Set<String> names = new HashSet<>();
    for (IjProjectElement element : deps.keySet()) {
      String name = element.getName();
      Preconditions.checkArgument(!names.contains(name));
      names.add(name);
    }
  }
}
