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

package com.facebook.buck.ide.intellij;

import com.facebook.buck.ide.intellij.model.DependencyType;
import com.facebook.buck.ide.intellij.model.IjModule;
import com.facebook.buck.ide.intellij.model.IjModuleAndroidFacet;
import com.facebook.buck.ide.intellij.model.IjModuleType;
import com.facebook.buck.ide.intellij.model.folders.IjFolder;
import com.facebook.buck.ide.intellij.model.folders.SourceFolder;
import com.facebook.buck.ide.intellij.model.folders.TestFolder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Holds all of the mutable state required during {@link IjModule} creation. */
public class ModuleBuildContext {

  private final ImmutableSet<BuildTarget> circularDependencyInducingTargets;

  private Optional<IjModuleAndroidFacet.Builder> androidFacetBuilder;
  private ImmutableSet.Builder<Path> extraClassPathDependenciesBuilder;
  private ImmutableSet.Builder<IjFolder> generatedSourceCodeFoldersBuilder;
  private Map<Path, IjFolder> sourceFoldersMergeMap;
  // See comment in getDependencies for these two member variables.
  private Map<BuildTarget, DependencyType> dependencyTypeMap;
  private Multimap<Path, BuildTarget> dependencyOriginMap;
  private IjModuleType moduleType;
  private Optional<Path> metaInfDirectory;
  private Optional<String> javaLanguageLevel;

  public ModuleBuildContext(ImmutableSet<BuildTarget> circularDependencyInducingTargets) {
    this.circularDependencyInducingTargets = circularDependencyInducingTargets;
    this.androidFacetBuilder = Optional.empty();
    this.extraClassPathDependenciesBuilder = new ImmutableSet.Builder<>();
    this.generatedSourceCodeFoldersBuilder = ImmutableSet.builder();
    this.sourceFoldersMergeMap = new HashMap<>();
    this.dependencyTypeMap = new HashMap<>();
    this.dependencyOriginMap = HashMultimap.create();
    this.moduleType = IjModuleType.UNKNOWN_MODULE;
    this.metaInfDirectory = Optional.empty();
    this.javaLanguageLevel = Optional.empty();
  }

  public void ensureAndroidFacetBuilder() {
    if (!androidFacetBuilder.isPresent()) {
      androidFacetBuilder = Optional.of(IjModuleAndroidFacet.builder());
    }
  }

  public IjModuleAndroidFacet.Builder getOrCreateAndroidFacetBuilder() {
    ensureAndroidFacetBuilder();
    return androidFacetBuilder.get();
  }

  public boolean isAndroidFacetBuilderPresent() {
    return androidFacetBuilder.isPresent();
  }

  public Optional<IjModuleAndroidFacet> getAndroidFacet() {
    return androidFacetBuilder.map(IjModuleAndroidFacet.Builder::build);
  }

  public ImmutableSet<IjFolder> getSourceFolders() {
    return ImmutableSet.copyOf(sourceFoldersMergeMap.values());
  }

  public void addExtraClassPathDependency(Path path) {
    extraClassPathDependenciesBuilder.add(path);
  }

  public ImmutableSet<Path> getExtraClassPathDependencies() {
    return extraClassPathDependenciesBuilder.build();
  }

  public void addGeneratedSourceCodeFolder(IjFolder generatedFolder) {
    generatedSourceCodeFoldersBuilder.add(generatedFolder);
  }

  public ImmutableSet<IjFolder> getGeneratedSourceCodeFolders() {
    return generatedSourceCodeFoldersBuilder.build();
  }

  public IjModuleType getModuleType() {
    return moduleType;
  }

  public void setModuleType(IjModuleType moduleType) {
    if (moduleType.hasHigherPriorityThan(this.moduleType)) {
      this.moduleType = moduleType;
    }
  }

  public Optional<Path> getMetaInfDirectory() {
    return metaInfDirectory;
  }

  public void setMetaInfDirectory(Path metaInfDirectory) {
    this.metaInfDirectory = Optional.of(metaInfDirectory);
  }

  public Optional<String> getJavaLanguageLevel() {
    return javaLanguageLevel;
  }

  public void setJavaLanguageLevel(Optional<String> javaLanguageLevel) {
    if (!this.javaLanguageLevel.isPresent()) {
      this.javaLanguageLevel = javaLanguageLevel;
    }
  }

  /**
   * Adds a source folder to the context. If a folder with the same path has already been added the
   * types of the two folders will be merged.
   *
   * @param folder folder to add/merge.
   */
  public void addSourceFolder(IjFolder folder) {
    Path path = folder.getPath();
    IjFolder otherFolder = sourceFoldersMergeMap.get(path);
    if (otherFolder != null) {
      folder = mergeAllowingTestToBePromotedToSource(folder, otherFolder);
    }
    sourceFoldersMergeMap.put(path, folder);
  }

  private IjFolder mergeAllowingTestToBePromotedToSource(IjFolder from, IjFolder to) {
    if ((from instanceof TestFolder && to instanceof SourceFolder)
        || (to instanceof TestFolder && from instanceof SourceFolder)) {
      return new SourceFolder(
          to.getPath(),
          from.getWantsPackagePrefix() || to.getWantsPackagePrefix(),
          IjFolder.combineInputs(from, to));
    }

    Preconditions.checkArgument(from.getClass() == to.getClass());

    return from.merge(to);
  }

  public void addDeps(Iterable<BuildTarget> buildTargets, DependencyType dependencyType) {
    addDeps(ImmutableSet.of(), buildTargets, dependencyType);
  }

  public void addCompileShadowDep(BuildTarget buildTarget) {
    DependencyType.putWithMerge(dependencyTypeMap, buildTarget, DependencyType.COMPILED_SHADOW);
  }

  /**
   * Record a dependency on a {@link BuildTarget}. The dependency's type will be merged if multiple
   * {@link TargetNode}s refer to it or if multiple TargetNodes include sources from the same
   * directory.
   *
   * @param sourcePaths the {@link Path}s to sources which need this dependency to build. Can be
   *     empty.
   * @param buildTargets the {@link BuildTarget}s to depend on
   * @param dependencyType what is the dependency needed for.
   */
  public void addDeps(
      ImmutableSet<Path> sourcePaths,
      Iterable<BuildTarget> buildTargets,
      DependencyType dependencyType) {
    for (BuildTarget buildTarget : buildTargets) {
      if (circularDependencyInducingTargets.contains(buildTarget)) {
        continue;
      }
      if (sourcePaths.isEmpty()) {
        DependencyType.putWithMerge(dependencyTypeMap, buildTarget, dependencyType);
      } else {
        for (Path sourcePath : sourcePaths) {
          dependencyOriginMap.put(sourcePath, buildTarget);
        }
      }
    }
  }

  public ImmutableMap<BuildTarget, DependencyType> getDependencies() {
    // Some targets may introduce dependencies without contributing to the IjFolder set. These
    // are recorded in the dependencyTypeMap.
    // Dependencies associated with source paths inherit the type from the folder. This is because
    // IntelliJ only operates on folders and so it is impossible to distinguish between test and
    // production code if it's in the same folder. That in turn means test-only dependencies need
    // to be "promoted" to production dependencies in the above scenario to keep code compiling.
    // It is also possible that a target is included in both maps, in which case the type gets
    // merged anyway.
    // Merging types does not back-propagate: if TargetA depends on TargetB and the type of
    // TargetB has been changed that does not mean the dependency type of TargetA is changed too.
    Map<BuildTarget, DependencyType> result = new HashMap<>(dependencyTypeMap);
    for (Path path : dependencyOriginMap.keySet()) {
      DependencyType dependencyType =
          Preconditions.checkNotNull(sourceFoldersMergeMap.get(path)) instanceof TestFolder
              ? DependencyType.TEST
              : DependencyType.PROD;
      for (BuildTarget buildTarget : dependencyOriginMap.get(path)) {
        DependencyType.putWithMerge(result, buildTarget, dependencyType);
      }
    }
    return ImmutableMap.copyOf(result);
  }
}
