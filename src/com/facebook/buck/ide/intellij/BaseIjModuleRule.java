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

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.ide.intellij.aggregation.AggregationContext;
import com.facebook.buck.ide.intellij.model.DependencyType;
import com.facebook.buck.ide.intellij.model.IjModule;
import com.facebook.buck.ide.intellij.model.IjModuleFactoryResolver;
import com.facebook.buck.ide.intellij.model.IjModuleRule;
import com.facebook.buck.ide.intellij.model.IjProjectConfig;
import com.facebook.buck.ide.intellij.model.folders.IJFolderFactory;
import com.facebook.buck.ide.intellij.model.folders.IjResourceFolderType;
import com.facebook.buck.ide.intellij.model.folders.ResourceFolderFactory;
import com.facebook.buck.ide.intellij.model.folders.SourceFolder;
import com.facebook.buck.ide.intellij.model.folders.TestFolder;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import com.facebook.buck.model.BuildTargets;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public abstract class BaseIjModuleRule<T extends CommonDescriptionArg> implements IjModuleRule<T> {

  protected final ProjectFilesystem projectFilesystem;
  protected final IjModuleFactoryResolver moduleFactoryResolver;
  protected final IjProjectConfig projectConfig;

  protected BaseIjModuleRule(
      ProjectFilesystem projectFilesystem,
      IjModuleFactoryResolver moduleFactoryResolver,
      IjProjectConfig projectConfig) {
    this.projectFilesystem = projectFilesystem;
    this.moduleFactoryResolver = moduleFactoryResolver;
    this.projectConfig = projectConfig;
  }

  /**
   * Calculate the set of directories containing inputs to the target.
   *
   * @param paths inputs to a given target.
   * @return index of path to set of inputs in that path
   */
  protected static ImmutableMultimap<Path, Path> getSourceFoldersToInputsIndex(
      ImmutableCollection<Path> paths) {
    Path defaultParent = Paths.get("");
    return paths
        .stream()
        .collect(
            ImmutableListMultimap.toImmutableListMultimap(
                path -> {
                  Path parent = path.getParent();
                  return parent == null ? defaultParent : parent;
                },
                path -> path));
  }

  /**
   * Add the set of input paths to the {@link IjModule.Builder} as source folders.
   *
   * @param foldersToInputsIndex mapping of source folders to their inputs.
   * @param wantsPackagePrefix whether folders should be annotated with a package prefix. This only
   *     makes sense when the source folder is Java source code.
   * @param context the module to add the folders to.
   */
  protected void addSourceFolders(
      IJFolderFactory factory,
      ImmutableMultimap<Path, Path> foldersToInputsIndex,
      boolean wantsPackagePrefix,
      ModuleBuildContext context) {
    for (Map.Entry<Path, Collection<Path>> entry : foldersToInputsIndex.asMap().entrySet()) {
      context.addSourceFolder(
          factory.create(
              entry.getKey(),
              wantsPackagePrefix,
              ImmutableSortedSet.copyOf(Ordering.natural(), entry.getValue())));
    }
  }

  protected void addResourceFolders(
      ResourceFolderFactory factory,
      ImmutableMultimap<Path, Path> foldersToInputsIndex,
      Path resourcesRoot,
      ModuleBuildContext context) {
    for (Map.Entry<Path, Collection<Path>> entry : foldersToInputsIndex.asMap().entrySet()) {
      context.addSourceFolder(
          factory.create(
              entry.getKey(),
              resourcesRoot,
              ImmutableSortedSet.copyOf(Ordering.natural(), entry.getValue())));
    }
  }

  private void addDepsAndFolder(
      IJFolderFactory folderFactory,
      DependencyType dependencyType,
      TargetNode<T, ?> targetNode,
      boolean wantsPackagePrefix,
      ModuleBuildContext context,
      ImmutableSet<Path> resourcePaths) {
    ImmutableMultimap<Path, Path> foldersToInputsIndex =
        getSourceFoldersToInputsIndex(targetNode.getInputs());

    if (!resourcePaths.isEmpty()) {
      foldersToInputsIndex =
          foldersToInputsIndex
              .entries()
              .stream()
              .filter(entry -> !resourcePaths.contains(entry.getValue()))
              .collect(
                  ImmutableListMultimap.toImmutableListMultimap(
                      Map.Entry::getKey, Map.Entry::getValue));
    }

    addSourceFolders(folderFactory, foldersToInputsIndex, wantsPackagePrefix, context);
    addDeps(foldersToInputsIndex, targetNode, dependencyType, context);

    addGeneratedOutputIfNeeded(folderFactory, targetNode, context);

    if (targetNode.getConstructorArg() instanceof JvmLibraryArg) {
      addAnnotationOutputIfNeeded(folderFactory, targetNode, context);
    }
  }

  private void addDepsAndFolder(
      IJFolderFactory folderFactory,
      DependencyType dependencyType,
      TargetNode<T, ?> targetNode,
      boolean wantsPackagePrefix,
      ModuleBuildContext context) {
    addDepsAndFolder(
        folderFactory, dependencyType, targetNode, wantsPackagePrefix, context, ImmutableSet.of());
  }

  protected void addDepsAndSources(
      TargetNode<T, ?> targetNode,
      boolean wantsPackagePrefix,
      ModuleBuildContext context,
      ImmutableSet<Path> resourcePaths) {
    addDepsAndFolder(
        SourceFolder.FACTORY,
        DependencyType.PROD,
        targetNode,
        wantsPackagePrefix,
        context,
        resourcePaths);
  }

  protected void addDepsAndSources(
      TargetNode<T, ?> targetNode, boolean wantsPackagePrefix, ModuleBuildContext context) {
    addDepsAndFolder(
        SourceFolder.FACTORY, DependencyType.PROD, targetNode, wantsPackagePrefix, context);
  }

  protected void addDepsAndTestSources(
      TargetNode<T, ?> targetNode,
      boolean wantsPackagePrefix,
      ModuleBuildContext context,
      ImmutableSet<Path> resourcePaths) {
    addDepsAndFolder(
        TestFolder.FACTORY,
        DependencyType.TEST,
        targetNode,
        wantsPackagePrefix,
        context,
        resourcePaths);
  }

  protected void addDepsAndTestSources(
      TargetNode<T, ?> targetNode, boolean wantsPackagePrefix, ModuleBuildContext context) {
    addDepsAndFolder(
        TestFolder.FACTORY, DependencyType.TEST, targetNode, wantsPackagePrefix, context);
  }

  protected ImmutableSet<Path> getResourcePaths(Collection<SourcePath> resources) {
    return resources
        .stream()
        .filter(PathSourcePath.class::isInstance)
        .map(PathSourcePath.class::cast)
        .map(PathSourcePath::getRelativePath)
        .collect(ImmutableSet.toImmutableSet());
  }

  protected ImmutableSet<Path> getResourcePaths(
      Collection<SourcePath> resources, Path resourcesRoot) {
    return resources
        .stream()
        .filter(PathSourcePath.class::isInstance)
        .map(PathSourcePath.class::cast)
        .map(PathSourcePath::getRelativePath)
        .filter(path -> path.startsWith(resourcesRoot))
        .collect(ImmutableSet.toImmutableSet());
  }

  protected ImmutableMultimap<Path, Path> getResourcesRootsToResources(
      JavaPackageFinder packageFinder, ImmutableSet<Path> resourcePaths) {
    return resourcePaths
        .stream()
        .collect(
            ImmutableListMultimap.toImmutableListMultimap(
                path ->
                    MorePaths.stripCommonSuffix(
                            path.getParent(), packageFinder.findJavaPackageFolder(path))
                        .getFirst(),
                Function.identity()));
  }

  // This function should only be called if resources_root is present. If there is no
  // resources_root, then we use the java src_roots option from .buckconfig for the resource root,
  // so marking the containing folder of the resources as a regular source folder will work
  // correctly. On the other hand, if there is a resources_root, then for resources under this root,
  // we need to create java-resource folders with the correct relativeOutputPath set. We also return
  // a filter that removes the resources that we've added, so that folders containing those
  // resources will not be added as regular source folders.
  protected void addResourceFolders(
      IjResourceFolderType ijResourceFolderType,
      ImmutableCollection<Path> resourcePaths,
      Path resourcesRoot,
      ModuleBuildContext context) {
    addResourceFolders(
        ijResourceFolderType.getFactory(),
        getSourceFoldersToInputsIndex(resourcePaths),
        resourcesRoot,
        context);
  }

  private void addDeps(
      ImmutableMultimap<Path, Path> foldersToInputsIndex,
      TargetNode<T, ?> targetNode,
      DependencyType dependencyType,
      ModuleBuildContext context) {
    context.addDeps(foldersToInputsIndex.keySet(), targetNode.getBuildDeps(), dependencyType);
  }

  @SuppressWarnings("unchecked")
  private void addAnnotationOutputIfNeeded(
      IJFolderFactory folderFactory, TargetNode<T, ?> targetNode, ModuleBuildContext context) {
    TargetNode<? extends JvmLibraryArg, ?> jvmLibraryTargetNode =
        (TargetNode<? extends JvmLibraryArg, ?>) targetNode;

    Optional<Path> annotationOutput =
        moduleFactoryResolver.getAnnotationOutputPath(jvmLibraryTargetNode);
    if (!annotationOutput.isPresent()) {
      return;
    }

    Path annotationOutputPath = annotationOutput.get();
    context.addGeneratedSourceCodeFolder(
        folderFactory.create(
            annotationOutputPath, false, ImmutableSortedSet.of(annotationOutputPath)));
  }

  private void addGeneratedOutputIfNeeded(
      IJFolderFactory folderFactory, TargetNode<T, ?> targetNode, ModuleBuildContext context) {

    ImmutableSet<Path> generatedSourcePaths = findConfiguredGeneratedSourcePaths(targetNode);

    for (Path generatedSourcePath : generatedSourcePaths) {
      context.addGeneratedSourceCodeFolder(
          folderFactory.create(
              generatedSourcePath, false, ImmutableSortedSet.of(generatedSourcePath)));
    }
  }

  private ImmutableSet<Path> findConfiguredGeneratedSourcePaths(TargetNode<T, ?> targetNode) {
    ImmutableSet.Builder<Path> generatedSourcePaths = ImmutableSet.builder();

    generatedSourcePaths.addAll(findConfiguredGeneratedSourcePathsUsingLabels(targetNode));

    return generatedSourcePaths.build();
  }

  private ImmutableSet<Path> findConfiguredGeneratedSourcePathsUsingLabels(
      TargetNode<T, ?> targetNode) {
    BuildTarget buildTarget = targetNode.getBuildTarget();
    ImmutableMap<String, String> labelToGeneratedSourcesMap =
        projectConfig.getLabelToGeneratedSourcesMap();

    return targetNode
        .getConstructorArg()
        .getLabels()
        .stream()
        .map(labelToGeneratedSourcesMap::get)
        .filter(Objects::nonNull)
        .map(pattern -> pattern.replaceAll("%name%", buildTarget.getShortNameAndFlavorPostfix()))
        .map(path -> BuildTargets.getGenPath(projectFilesystem, buildTarget, path))
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public void applyDuringAggregation(AggregationContext context, TargetNode<T, ?> targetNode) {
    context.setModuleType(detectModuleType(targetNode));
  }
}
