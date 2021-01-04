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

package com.facebook.buck.features.project.intellij.model;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStylePrehashedValue;
import com.facebook.buck.features.project.intellij.IjDependencyListBuilder;
import com.facebook.buck.features.project.intellij.Util;
import com.facebook.buck.features.project.intellij.model.folders.IjFolder;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;

/** Represents a single IntelliJ module. */
@BuckStylePrehashedValue
public abstract class IjModule implements IjProjectElement {

  @Override
  @Value.Derived
  public String getName() {
    return Util.intelliJModuleNameFromPath(getModuleBasePath());
  }

  @Override
  @Value.Auxiliary
  public abstract ImmutableSet<BuildTarget> getTargets();

  @Value.Auxiliary
  public abstract ImmutableSet<BuildTarget> getNonSourceBuildTargets();

  @Value.Auxiliary
  public abstract Map<BuildTarget, List<IjFolder>> getTargetsToGeneratedSourcesMap();

  /** @return path to the top-most directory the module is responsible for. */
  public abstract Path getModuleBasePath();

  /** @return paths to various directories the module is responsible for. */
  @Value.Auxiliary
  public abstract ImmutableList<IjFolder> getFolders();

  /**
   * @return map of {@link BuildTarget}s the module depends on and information on whether it's a
   *     test-only dependency or not.
   */
  @Value.Auxiliary
  public abstract ImmutableMap<BuildTarget, DependencyType> getDependencies();

  @Value.Auxiliary
  public abstract Optional<IjModuleAndroidFacet> getAndroidFacet();

  /** @return a set of IjLibrary that the module requires to index all symbols correctly. */
  @Value.Auxiliary
  public abstract ImmutableSet<IjLibrary> getExtraLibraryDependencies();

  /** @return a set of module paths that the module requires to index correctly. */
  @Value.Auxiliary
  public abstract ImmutableSet<Path> getExtraModuleDependencies();

  /** @return Folders which contain the generated source code. */
  @Value.Auxiliary
  public abstract ImmutableList<IjFolder> getGeneratedSourceCodeFolders();

  @Value.Auxiliary
  public abstract Optional<String> getLanguageLevel();

  @Value.Auxiliary
  public abstract IjModuleType getModuleType();

  @Value.Auxiliary
  public abstract Optional<Path> getMetaInfDirectory();

  @Value.Auxiliary
  public abstract Optional<Path> getCompilerOutputPath();

  @Value.Check
  protected void checkDependencyConsistency() {
    for (Map.Entry<BuildTarget, DependencyType> entry : getDependencies().entrySet()) {
      BuildTarget depBuildTarget = entry.getKey();
      DependencyType dependencyType = entry.getValue();
      boolean isSelfDependency = getTargets().contains(depBuildTarget);

      if (dependencyType.equals(DependencyType.COMPILED_SHADOW)) {
        Preconditions.checkArgument(
            isSelfDependency,
            "Target %s is a COMPILED_SHADOW dependency of module %s and therefore should be part"
                + "of its target set.",
            depBuildTarget,
            getName());
      } else {
        Preconditions.checkArgument(
            !isSelfDependency,
            "Target %s is a regular dependency of module %s and therefore should not be part of "
                + "its target set.",
            depBuildTarget,
            getName());
      }
    }
  }

  @Override
  public void addAsDependency(
      DependencyType dependencyType, IjDependencyListBuilder dependencyListBuilder) {
    Preconditions.checkArgument(!dependencyType.equals(DependencyType.COMPILED_SHADOW));
    IjDependencyListBuilder.Scope scope = IjDependencyListBuilder.Scope.COMPILE;
    if (dependencyType.equals(DependencyType.TEST)) {
      scope = IjDependencyListBuilder.Scope.TEST;
    } else if (dependencyType.equals(DependencyType.RUNTIME)) {
      scope = IjDependencyListBuilder.Scope.RUNTIME;
    }
    dependencyListBuilder.addModule(getName(), scope, false /* exported */);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableIjModule.Builder {}
}
