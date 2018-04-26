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

package com.facebook.buck.ide.intellij.model;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.ide.intellij.IjDependencyListBuilder;
import com.facebook.buck.ide.intellij.Util;
import com.facebook.buck.ide.intellij.model.folders.IjFolder;
import com.facebook.buck.io.file.MorePaths;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;

/** Represents a single IntelliJ module. */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractIjModule implements IjProjectElement {

  @Override
  @Value.Derived
  public String getName() {
    return Util.intelliJModuleNameFromPath(MorePaths.pathWithUnixSeparators(getModuleBasePath()));
  }

  @Override
  public abstract ImmutableSet<BuildTarget> getTargets();

  public abstract ImmutableSet<BuildTarget> getNonSourceBuildTargets();

  /**
   * @return path to the top-most directory the module is responsible for. This is also where the
   *     corresponding .iml file is located.
   */
  public abstract Path getModuleBasePath();

  /** @return paths to various directories the module is responsible for. */
  public abstract ImmutableList<IjFolder> getFolders();

  /**
   * @return map of {@link BuildTarget}s the module depends on and information on whether it's a
   *     test-only dependency or not.
   */
  public abstract ImmutableMap<BuildTarget, DependencyType> getDependencies();

  public abstract Optional<IjModuleAndroidFacet> getAndroidFacet();

  /** @return a set of classpaths that the module requires to index correctly. */
  public abstract ImmutableSet<Path> getExtraClassPathDependencies();

  /** @return a set of module paths that the module requires to index correctly. */
  public abstract ImmutableSet<Path> getExtraModuleDependencies();

  /** @return Folders which contain the generated source code. */
  public abstract ImmutableList<IjFolder> getGeneratedSourceCodeFolders();

  public abstract Optional<String> getLanguageLevel();

  public abstract IjModuleType getModuleType();

  public abstract Optional<Path> getMetaInfDirectory();

  public abstract Optional<Path> getCompilerOutputPath();

  /** @return path where the XML describing the module to IntelliJ will be written to. */
  @Value.Derived
  public Path getModuleImlFilePath() {
    return getModuleBasePath().resolve(getName() + ".iml");
  }

  @Value.Check
  protected void allRulesAreChildrenOfBasePath() {
    Path moduleBasePath = getModuleBasePath();
    for (BuildTarget target : getTargets()) {
      Path targetBasePath = target.getBasePath();
      Preconditions.checkArgument(
          targetBasePath.startsWith(moduleBasePath),
          "A module cannot be composed of targets which are outside of its base path.");
    }
  }

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

  @Override
  public int hashCode() {
    return getModuleImlFilePath().hashCode();
  }

  @Override
  public boolean equals(Object another) {
    return this == another
        || another instanceof IjModule
            && getModuleImlFilePath().equals(((IjModule) another).getModuleImlFilePath());
  }
}
