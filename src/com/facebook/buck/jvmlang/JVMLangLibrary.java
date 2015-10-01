/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.jvmlang;

import com.facebook.buck.classpaths.JavaLibraryClasspathProvider;
import com.facebook.buck.java.HasClasspathEntries;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.ExportDependencies;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

public abstract class JVMLangLibrary extends AbstractBuildRule implements HasClasspathEntries, ExportDependencies, JavaLibrary {

  private final Supplier<ImmutableSetMultimap<JavaLibrary, Path>>
      transitiveClasspathEntriesSupplier;
  private final Supplier<ImmutableSet<JavaLibrary>> transitiveClasspathDepsSupplier;
  private final ImmutableSortedSet<BuildRule> exportedDeps;
  private final Optional<Path> outputJar;

  protected JVMLangLibrary(
      final BuildRuleParams buildRuleParams,
      final SourcePathResolver resolver,
      final ImmutableSortedSet<BuildRule> exportedDeps,
      Set<? extends SourcePath> srcs,
      Set<? extends SourcePath> resources) {

    super(buildRuleParams, resolver);

    if (!srcs.isEmpty() || !resources.isEmpty()) {
      this.outputJar = Optional.of(getOutputJarPath(getBuildTarget()));
    } else {
      this.outputJar = Optional.absent();
    }

    this.exportedDeps = exportedDeps;

    this.transitiveClasspathEntriesSupplier =
        Suppliers.memoize(
            new Supplier<ImmutableSetMultimap<JavaLibrary, Path>>() {
              @Override
              public ImmutableSetMultimap<JavaLibrary, Path> get() {
                return JavaLibraryClasspathProvider.getTransitiveClasspathEntries(
                    JVMLangLibrary.this,
                    outputJar);
              }
            });

    this.transitiveClasspathDepsSupplier =
        Suppliers.memoize(
            new Supplier<ImmutableSet<JavaLibrary>>() {
              @Override
              public ImmutableSet<JavaLibrary> get() {
                return JavaLibraryClasspathProvider.getTransitiveClasspathDeps(
                    JVMLangLibrary.this,
                    outputJar);
              }
            });

  }

  @Override
  public ImmutableSortedSet<BuildRule> getDepsForTransitiveClasspathEntries() {
    return ImmutableSortedSet.copyOf(Sets.union(getDeclaredDeps(), exportedDeps));
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries() {
    return transitiveClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSet<JavaLibrary> getTransitiveClasspathDeps() {
    return transitiveClasspathDepsSupplier.get();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getExportedDeps() {
    return exportedDeps;
  }

  @Override
  public final Path getPathToOutput() {
    return outputJar.orNull();
  }

  public final Optional<Path> getOutputJar() {
    return outputJar;
  }

  protected static Path getOutputJarDirPath(BuildTarget target) {
    return BuildTargets.getGenPath(target, "lib__%s__output");
  }

  public static Path getOutputJarPath(BuildTarget target) {
    return Paths.get(
        String.format(
            "%s/%s.jar",
            getOutputJarDirPath(target),
            target.getShortName()));
  }


}
