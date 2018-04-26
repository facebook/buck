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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import org.immutables.value.Value;

/** Represents a prebuilt library (.jar or .aar) as seen by IntelliJ. */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractIjLibrary implements IjProjectElement {
  @Override
  public abstract String getName();

  @Override
  public abstract ImmutableSet<BuildTarget> getTargets();

  /** @return path to the binary (.jar or .aar) the library represents. */
  public abstract ImmutableSet<Path> getBinaryJars();

  /** @return classPath paths */
  public abstract ImmutableSet<Path> getClassPaths();

  /** @return path to the jar containing sources for the library. */
  public abstract ImmutableSet<Path> getSourceJars();

  /** @return url to the javadoc. */
  public abstract ImmutableSet<String> getJavadocUrls();

  @Value.Check
  protected void eitherBinaryJarOrClassPathPresent() {
    // IntelliJ library should have a binary jar or classpath, but we also allow it to have an
    // optional res folder so that resources can be loaded properly.
    boolean hasClasspathsWithoutRes =
        getClassPaths().stream().anyMatch(input -> !input.endsWith("res"));

    Preconditions.checkArgument(!getBinaryJars().isEmpty() ^ hasClasspathsWithoutRes);
  }

  @Override
  public void addAsDependency(
      DependencyType dependencyType, IjDependencyListBuilder dependencyListBuilder) {
    if (dependencyType.equals(DependencyType.COMPILED_SHADOW)) {
      dependencyListBuilder.addCompiledShadow(getName());
    } else {
      IjDependencyListBuilder.Scope scope = IjDependencyListBuilder.Scope.COMPILE;
      if (dependencyType.equals(DependencyType.TEST)) {
        scope = IjDependencyListBuilder.Scope.TEST;
      } else if (dependencyType.equals(DependencyType.RUNTIME)) {
        scope = IjDependencyListBuilder.Scope.RUNTIME;
      }
      dependencyListBuilder.addLibrary(getName(), scope, false /* exported */);
    }
  }
}
