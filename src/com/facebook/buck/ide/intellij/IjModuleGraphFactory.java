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

import com.facebook.buck.ide.intellij.aggregation.AggregationModule;
import com.facebook.buck.ide.intellij.aggregation.AggregationModuleFactory;
import com.facebook.buck.ide.intellij.aggregation.AggregationTree;
import com.facebook.buck.ide.intellij.model.DependencyType;
import com.facebook.buck.ide.intellij.model.IjLibrary;
import com.facebook.buck.ide.intellij.model.IjLibraryFactory;
import com.facebook.buck.ide.intellij.model.IjModule;
import com.facebook.buck.ide.intellij.model.IjModuleFactory;
import com.facebook.buck.ide.intellij.model.IjProjectConfig;
import com.facebook.buck.ide.intellij.model.IjProjectElement;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class IjModuleGraphFactory {
  /**
   * Create all the modules we are capable of representing in IntelliJ from the supplied graph.
   *
   * @param targetGraph graph whose nodes will be converted to {@link IjModule}s.
   * @return map which for every BuildTarget points to the corresponding IjModule. Multiple
   *     BuildTarget can point to one IjModule (many:one mapping), the BuildTargets which can't be
   *     prepresented in IntelliJ are missing from this mapping.
   */
  private static ImmutableMap<BuildTarget, IjModule> createModules(
      ProjectFilesystem projectFilesystem,
      TargetGraph targetGraph,
      IjModuleFactory moduleFactory,
      AggregationModuleFactory aggregationModuleFactory,
      final int minimumPathDepth,
      ImmutableSet<String> ignoredTargetLabels) {

    ImmutableListMultimap<Path, TargetNode<?, ?>> baseTargetPathMultimap =
        targetGraph
            .getNodes()
            .stream()
            .filter(
                input ->
                    SupportedTargetTypeRegistry.isTargetTypeSupported(
                        input.getDescription().getClass()))
            .filter(
                targetNode -> {
                  CommonDescriptionArg arg = (CommonDescriptionArg) targetNode.getConstructorArg();
                  return !arg.labelsContainsAnyOf(ignoredTargetLabels);
                })
            // IntelliJ doesn't support referring to source files which aren't below the root of the
            // project. Filter out those cases proactively, so that we don't try to resolve files
            // relative to the wrong ProjectFilesystem.
            // Maybe one day someone will fix this.
            .filter(targetNode -> isInRootCell(projectFilesystem, targetNode))
            .collect(
                MoreCollectors.toImmutableListMultimap(
                    targetNode -> targetNode.getBuildTarget().getBasePath(),
                    targetNode -> targetNode));

    AggregationTree aggregationTree =
        createAggregationTree(aggregationModuleFactory, baseTargetPathMultimap);

    aggregationTree.aggregateModules(minimumPathDepth);

    ImmutableMap.Builder<BuildTarget, IjModule> moduleByBuildTarget = new ImmutableMap.Builder<>();

    aggregationTree
        .getModules()
        .stream()
        .filter(aggregationModule -> !aggregationModule.getTargets().isEmpty())
        .forEach(
            aggregationModule -> {
              IjModule module =
                  moduleFactory.createModule(
                      aggregationModule.getModuleBasePath(),
                      aggregationModule.getTargets(),
                      aggregationModule.getExcludes());
              module
                  .getTargets()
                  .forEach(buildTarget -> moduleByBuildTarget.put(buildTarget, module));
            });

    return moduleByBuildTarget.build();
  }

  private static AggregationTree createAggregationTree(
      AggregationModuleFactory aggregationModuleFactory,
      ImmutableListMultimap<Path, TargetNode<?, ?>> targetNodesByBasePath) {
    Map<Path, AggregationModule> pathToAggregationModuleMap =
        targetNodesByBasePath
            .asMap()
            .entrySet()
            .stream()
            .collect(
                MoreCollectors.toImmutableMap(
                    Map.Entry::getKey,
                    pathWithTargetNode ->
                        aggregationModuleFactory.createAggregationModule(
                            pathWithTargetNode.getKey(),
                            ImmutableSet.copyOf(pathWithTargetNode.getValue()))));

    Path rootPath = Paths.get("");

    AggregationModule rootAggregationModule = pathToAggregationModuleMap.get(rootPath);
    if (rootAggregationModule == null) {
      rootAggregationModule =
          aggregationModuleFactory.createAggregationModule(rootPath, ImmutableSet.of());
    }

    AggregationTree aggregationTree = new AggregationTree(rootAggregationModule);

    pathToAggregationModuleMap
        .entrySet()
        .stream()
        .filter(e -> !rootPath.equals(e.getKey()))
        .forEach(e -> aggregationTree.addModule(e.getKey(), e.getValue()));

    return aggregationTree;
  }

  /**
   * @param projectConfig the project config used
   * @param targetGraph input graph.
   * @param libraryFactory library factory.
   * @param moduleFactory module factory.
   * @return module graph corresponding to the supplied {@link TargetGraph}. Multiple targets from
   *     the same base path are mapped to a single module, therefore an IjModuleGraph edge exists
   *     between two modules (Ma, Mb) if a TargetGraph edge existed between a pair of nodes (Ta, Tb)
   *     and Ma contains Ta and Mb contains Tb.
   */
  public static IjModuleGraph from(
      final ProjectFilesystem projectFilesystem,
      final IjProjectConfig projectConfig,
      final TargetGraph targetGraph,
      final IjLibraryFactory libraryFactory,
      final IjModuleFactory moduleFactory,
      final AggregationModuleFactory aggregationModuleFactory) {
    ImmutableSet<String> ignoredTargetLabels = projectConfig.getIgnoredTargetLabels();
    final ImmutableMap<BuildTarget, IjModule> rulesToModules =
        createModules(
            projectFilesystem,
            targetGraph,
            moduleFactory,
            aggregationModuleFactory,
            projectConfig.getAggregationMode().getGraphMinimumDepth(targetGraph.getNodes().size()),
            ignoredTargetLabels);
    final ExportedDepsClosureResolver exportedDepsClosureResolver =
        new ExportedDepsClosureResolver(targetGraph, ignoredTargetLabels);
    ImmutableMap.Builder<IjProjectElement, ImmutableMap<IjProjectElement, DependencyType>>
        depsBuilder = ImmutableMap.builder();
    final Set<IjLibrary> referencedLibraries = new HashSet<>();

    for (final IjModule module : ImmutableSet.copyOf(rulesToModules.values())) {
      Map<IjProjectElement, DependencyType> moduleDeps = new HashMap<>();

      for (Map.Entry<BuildTarget, DependencyType> entry : module.getDependencies().entrySet()) {
        BuildTarget depBuildTarget = entry.getKey();
        TargetNode<?, ?> depTargetNode = targetGraph.get(depBuildTarget);

        CommonDescriptionArg arg = (CommonDescriptionArg) depTargetNode.getConstructorArg();
        if (arg.labelsContainsAnyOf(ignoredTargetLabels)) {
          continue;
        }

        DependencyType depType = entry.getValue();
        ImmutableSet<IjProjectElement> depElements;

        if (depType.equals(DependencyType.COMPILED_SHADOW)) {
          Optional<IjLibrary> library = libraryFactory.getLibrary(depTargetNode);
          if (library.isPresent()) {
            depElements = ImmutableSet.of(library.get());
          } else {
            depElements = ImmutableSet.of();
          }
        } else {
          depElements =
              Stream.concat(
                      exportedDepsClosureResolver.getExportedDepsClosure(depBuildTarget).stream(),
                      Stream.of(depBuildTarget))
                  .filter(
                      input -> {
                        TargetNode<?, ?> targetNode = targetGraph.get(input);
                        // IntelliJ doesn't support referring to source files which aren't below the root of
                        // the project. Filter out those cases proactively, so that we don't try to resolve
                        // files relative to the wrong ProjectFilesystem.
                        // Maybe one day someone will fix this.
                        return isInRootCell(projectFilesystem, targetNode);
                      })
                  .filter(
                      input -> {
                        // The exported deps closure can contain references back to targets contained
                        // in the module, so filter those out.
                        return !module.getTargets().contains(input);
                      })
                  .map(
                      depTarget -> {
                        IjModule depModule = rulesToModules.get(depTarget);
                        if (depModule != null) {
                          return depModule;
                        }
                        TargetNode<?, ?> targetNode = targetGraph.get(depTarget);
                        return libraryFactory.getLibrary(targetNode).orElse(null);
                      })
                  .filter(Objects::nonNull)
                  .collect(MoreCollectors.toImmutableSet());
        }

        for (IjProjectElement depElement : depElements) {
          Preconditions.checkState(!depElement.equals(module));
          DependencyType.putWithMerge(moduleDeps, depElement, depType);
        }
      }

      if (!module.getExtraClassPathDependencies().isEmpty()) {
        IjLibrary extraClassPathLibrary =
            IjLibrary.builder()
                .setClassPaths(module.getExtraClassPathDependencies())
                .setTargets(ImmutableSet.of())
                .setName("library_" + module.getName() + "_extra_classpath")
                .build();
        moduleDeps.put(extraClassPathLibrary, DependencyType.PROD);
      }

      moduleDeps
          .keySet()
          .stream()
          .filter(dep -> dep instanceof IjLibrary)
          .map(library -> (IjLibrary) library)
          .forEach(referencedLibraries::add);

      depsBuilder.put(module, ImmutableMap.copyOf(moduleDeps));
    }

    referencedLibraries.forEach(library -> depsBuilder.put(library, ImmutableMap.of()));

    return new IjModuleGraph(depsBuilder.build());
  }

  private static boolean isInRootCell(
      ProjectFilesystem projectFilesystem, TargetNode<?, ?> targetNode) {
    return targetNode.getFilesystem().equals(projectFilesystem);
  }

  private IjModuleGraphFactory() {}
}
