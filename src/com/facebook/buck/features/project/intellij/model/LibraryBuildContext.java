/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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
import com.facebook.buck.features.project.intellij.IjDependencyListBuilder;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * This is the mutable version of {@link IjLibrary}. When we construct the IJModuleGraph, we use
 * this class to merge and store attributes of same libraries with different configurations.
 *
 * <p>The behavior is controlled by buck config: `isTargetConfigurationInLibrariesEnabled`.
 *
 * <p>When the config is enabled, we have target-configuration info in the library names, this means
 * we will have unique LibraryBuildContext for each of them and merging means merging with itself.
 *
 * <p>When the config is disabled, we don't have the target-configuration in the library names. So
 * the libraries with different configurations keep getting added to this context and when we build
 * the immutable version, we merge all the attributes of all the libraries stored so far.
 */
public class LibraryBuildContext implements IjProjectElement {

  private final String name;
  private IjLibrary.Level level;
  private final IjLibrary.Type type;
  private final Set<BuildTarget> targets = new HashSet<>();
  private final List<IjLibrary> libraries = new ArrayList<>();
  private IjLibrary aggregatedLibrary;

  public LibraryBuildContext(IjLibrary ijLibrary) {
    this.name = ijLibrary.getName();
    this.level = ijLibrary.getLevel();
    this.type = ijLibrary.getType();
    this.targets.addAll(ijLibrary.getTargets());
    this.libraries.add(ijLibrary);
  }

  @Override
  public String getName() {
    return name;
  }

  public IjLibrary.Level getLevel() {
    return level;
  }

  @Override
  public ImmutableSet<BuildTarget> getTargets() {
    return ImmutableSet.copyOf(targets);
  }

  /** All the libraries with same name will be stored in the context. */
  public void merge(LibraryBuildContext libraryBuildContext) {
    Preconditions.checkState(
        aggregatedLibrary == null, "Merge can't be called after building the aggregated library");
    targets.addAll(libraryBuildContext.getTargets());
    libraries.addAll(libraryBuildContext.libraries);
  }

  /** This is to update the level of the library as PROJECT/MODULE. */
  public LibraryBuildContext withLevel(IjLibrary.Level level) {
    Preconditions.checkState(
        aggregatedLibrary == null, "Method not allowed after building the aggregated library");
    this.level = level;
    return this;
  }

  /**
   * Builds the immutable version of the library by aggregating all the libraries in the context.
   */
  public IjLibrary getAggregatedLibrary() {
    if (aggregatedLibrary == null) {
      aggregatedLibrary = getIjLibraryBuilder().build();
    }
    return aggregatedLibrary;
  }

  /** Returns the current mutable state of the IjLibrary. */
  private IjLibrary.Builder getIjLibraryBuilder() {
    Preconditions.checkState(
        aggregatedLibrary == null, "Method not allowed after building the aggregated library");
    IjLibrary.Builder aggregatedLibraryBuilder =
        IjLibrary.builder().setName(name).setType(type).setLevel(level).setTargets(targets);

    for (IjLibrary library : libraries) {
      aggregatedLibraryBuilder.addAllTargets(library.getTargets());
      aggregatedLibraryBuilder.addAllAnnotationJars(library.getAnnotationJars());
      aggregatedLibraryBuilder.addAllBinaryJars(library.getBinaryJars());
      aggregatedLibraryBuilder.addAllClassPaths(library.getClassPaths());
      aggregatedLibraryBuilder.addAllSourceJars(library.getSourceJars());
      aggregatedLibraryBuilder.addAllJavadocUrls(library.getJavadocUrls());
      aggregatedLibraryBuilder.addAllSourceDirs(library.getSourceDirs());
    }
    return aggregatedLibraryBuilder;
  }

  @Override
  public void addAsDependency(
      DependencyType dependencyType, IjDependencyListBuilder dependencyListBuilder) {
    Preconditions.checkNotNull(
        aggregatedLibrary, "Method not allowed before building the aggregated library");
    aggregatedLibrary.addAsDependency(dependencyType, dependencyListBuilder);
  }
}
