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

package com.facebook.buck.java.intellij;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.Map;

/**
 * Represents a single IntelliJ module.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractIjModule implements IjProjectElement {

  @Override
  @Value.Derived
  public String getName() {
    return Util.intelliJModuleNameFromPath(getModuleBasePath());
  }

  @Override
  public abstract ImmutableSet<TargetNode<?>> getTargets();

  /**
   * @return path to the top-most directory the module is responsible for. This is also where the
   * corresponding .iml file is located.
   */
  public abstract Path getModuleBasePath();

  /**
   * @return paths to various directories the module is responsible for.
   */
  public abstract ImmutableSet<IjFolder> getFolders();

  /**
   * @return map of {@link BuildTarget}s the module depends on and information on whether it's a
   *         test-only dependency or not.
   */
  public abstract ImmutableMap<BuildTarget, IjModuleGraph.DependencyType> getDependencies();

  public abstract Optional<IjModuleAndroidFacet> getAndroidFacet();

  /**
   * @return a set of classpaths that the module requires to index correctly.
   */
  public abstract ImmutableSet<Path> getExtraClassPathDependencies();

  /**
   * @return path where the XML describing the module to IntelliJ will be written to.
   */
  @Value.Derived
  public Path getModuleImlFilePath() {
    return getModuleBasePath().resolve(getName() + ".iml");
  }

  @Value.Check
  protected void targetSetCantBeEmpty() {
    Preconditions.checkArgument(!getTargets().isEmpty());
  }

  @Value.Check
  protected void allRulesAreChildrenOfBasePath() {
    Path moduleBasePath = getModuleBasePath();
    for (TargetNode<?> target : getTargets()) {
      Path targetBasePath = target.getBuildTarget().getBasePath();
      Preconditions.checkArgument(
          targetBasePath.startsWith(moduleBasePath),
          "A module cannot be composed of targets which are outside of its base path.");
    }
  }

  @Value.Check
  protected void checkDependencyConsistency() {
    ImmutableSet<BuildTarget> buildTargets = FluentIterable.from(getTargets())
        .transform(TargetNode.TO_TARGET)
        .toSet();

    for (Map.Entry<BuildTarget, IjModuleGraph.DependencyType> entry :
        getDependencies().entrySet()) {
      BuildTarget depBuildTarget = entry.getKey();
      IjModuleGraph.DependencyType dependencyType = entry.getValue();
      boolean isSelfDependency = buildTargets.contains(depBuildTarget);

      if (dependencyType.equals(IjModuleGraph.DependencyType.COMPILED_SHADOW)) {
        Preconditions.checkArgument(
            isSelfDependency,
            "Target %s is a COMPILED_SHADOW dependency of module %s and therefore should be part" +
                "of its target set.",
            depBuildTarget,
            getName());
      } else {
        Preconditions.checkArgument(
            !isSelfDependency,
            "Target %s is a regular dependency of module %s and therefore should not be part of " +
                "its target set.",
            depBuildTarget,
            getName());
      }
    }
  }

  @Override
  public void addAsDependency(
      IjModuleGraph.DependencyType dependencyType, IjDependencyListBuilder dependencyListBuilder) {
    Preconditions.checkArgument(
        !dependencyType.equals(IjModuleGraph.DependencyType.COMPILED_SHADOW));
    IjDependencyListBuilder.Scope scope = IjDependencyListBuilder.Scope.COMPILE;
    if (dependencyType.equals(IjModuleGraph.DependencyType.TEST)) {
      scope = IjDependencyListBuilder.Scope.TEST;
    }
    dependencyListBuilder.addModule(getName(), scope, false /* exported */);
  }
}
