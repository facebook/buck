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

import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.nio.file.Path;

/**
 * Represents a prebuilt library (.jar or .aar) as seen by IntelliJ.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractIjLibrary implements IjProjectElement {
  @Override
  public abstract String getName();

  @Override
  public abstract ImmutableSet<TargetNode<?>> getTargets();

  /**
   * @return path to the binary (.jar or .aar) the library represents.
   */
  public abstract Optional<Path> getBinaryJar();

  /**
   * @return classPath paths
   */
  public abstract ImmutableSet<Path> getClassPaths();

  /**
   * @return path to the jar containing sources for the library.
   */
  public abstract Optional<Path> getSourceJar();

  /**
   * @return url to the javadoc.
   */
  public abstract Optional<String> getJavadocUrl();

  @Value.Check
  protected void eitherBinaryJarOrClassPathPresent() {
    Preconditions.checkArgument(getBinaryJar().isPresent() ^ !getClassPaths().isEmpty());
  }

  @Override
  public void addAsDependency(
      IjModuleGraph.DependencyType dependencyType,
      IjDependencyListBuilder dependencyListBuilder) {
    if (dependencyType.equals(IjModuleGraph.DependencyType.COMPILED_SHADOW)) {
      dependencyListBuilder.addCompiledShadow(getName());
    } else {
      IjDependencyListBuilder.Scope scope = IjDependencyListBuilder.Scope.COMPILE;
      if (dependencyType.equals(IjModuleGraph.DependencyType.TEST)) {
        scope = IjDependencyListBuilder.Scope.TEST;
      }
      dependencyListBuilder.addLibrary(getName(), scope, false /* exported */);
    }
  }
}
