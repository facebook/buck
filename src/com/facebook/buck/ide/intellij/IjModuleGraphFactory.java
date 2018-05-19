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
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.ide.intellij.aggregation.AggregationModule;
import com.facebook.buck.ide.intellij.aggregation.AggregationModuleFactory;
import com.facebook.buck.ide.intellij.aggregation.AggregationTree;
import com.facebook.buck.ide.intellij.model.DependencyType;
import com.facebook.buck.ide.intellij.model.IjLibrary;
import com.facebook.buck.ide.intellij.model.IjLibraryFactory;
import com.facebook.buck.ide.intellij.model.IjModule;
import com.facebook.buck.ide.intellij.model.IjModuleFactory;
import com.facebook.buck.ide.intellij.model.IjModuleType;
import com.facebook.buck.ide.intellij.model.IjProjectConfig;
import com.facebook.buck.ide.intellij.model.IjProjectElement;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
      IjProjectConfig projectConfig,
      TargetGraph targetGraph,
      IjModuleFactory moduleFactory,
      AggregationModuleFactory aggregationModuleFactory,
      int minimumPathDepth,
      ImmutableSet<String> ignoredTargetLabels) {

    Stream<TargetNode<?, ?>> nodes =
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
            .filter(targetNode -> isInRootCell(projectFilesystem, targetNode));

    ImmutableListMultimap<Path, TargetNode<?, ?>> baseTargetPathMultimap =
        (projectConfig.getProjectRoot().isEmpty()
                ? nodes
                : nodes.filter(
                    targetNode ->
                        shouldConvertToIjModule(projectConfig.getProjectRoot(), targetNode)))
            .collect(
                ImmutableListMultimap.toImmutableListMultimap(
                    targetNode -> targetNode.getBuildTarget().getBasePath(),
                    targetNode -> targetNode));

    AggregationTree aggregationTree =
        createAggregationTree(projectConfig, aggregationModuleFactory, baseTargetPathMultimap);

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

  private static boolean shouldConvertToIjModule(String projectRoot, TargetNode<?, ?> targetNode) {
    return targetNode.getBuildTarget().getBasePath().startsWith(projectRoot);
  }

  private static AggregationTree createAggregationTree(
      IjProjectConfig projectConfig,
      AggregationModuleFactory aggregationModuleFactory,
      ImmutableListMultimap<Path, TargetNode<?, ?>> targetNodesByBasePath) {
    Map<Path, AggregationModule> pathToAggregationModuleMap =
        targetNodesByBasePath
            .asMap()
            .entrySet()
            .stream()
            .collect(
                ImmutableMap.toImmutableMap(
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

    AggregationTree aggregationTree = new AggregationTree(projectConfig, rootAggregationModule);

    pathToAggregationModuleMap
        .entrySet()
        .stream()
        .filter(e -> !rootPath.equals(e.getKey()))
        .forEach(e -> aggregationTree.addModule(e.getKey(), e.getValue()));

    return aggregationTree;
  }

  private static ImmutableSet<IjProjectElement> getProjectElementFromBuildTargets(
      TargetGraph targetGraph,
      IjLibraryFactory libraryFactory,
      ImmutableMap<BuildTarget, IjModule> rulesToModules,
      IjModule module,
      Stream<BuildTarget> buildTargetStream) {
    return buildTargetStream
        .filter(
            input -> {
              // The exported deps closure can contain references back to targets contained
              // in the module, so filter those out.
              return !module.getTargets().contains(input);
            })
        .flatMap(
            depTarget -> {
              List<IjProjectElement> elements = new ArrayList<>();
              IjModule depModule = rulesToModules.get(depTarget);
              if (depModule != null) {
                elements.add(depModule);
              }
              if (depModule == null || depModule.getNonSourceBuildTargets().contains(depTarget)) {
                // all BuildTarget's are merged into IJModule
                // if a BuildTarget is not built from Java sources, it will also be added as a
                // library
                TargetNode<?, ?> targetNode = targetGraph.get(depTarget);
                elements.add(libraryFactory.getLibrary(targetNode).orElse(null));
              }
              return elements.stream();
            })
        .filter(Objects::nonNull)
        .collect(ImmutableSet.toImmutableSet());
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
      ProjectFilesystem projectFilesystem,
      IjProjectConfig projectConfig,
      TargetGraph targetGraph,
      IjLibraryFactory libraryFactory,
      IjModuleFactory moduleFactory,
      AggregationModuleFactory aggregationModuleFactory) {
    ImmutableSet<String> ignoredTargetLabels = projectConfig.getIgnoredTargetLabels();
    ImmutableMap<BuildTarget, IjModule> rulesToModules =
        createModules(
            projectFilesystem,
            projectConfig,
            targetGraph,
            moduleFactory,
            aggregationModuleFactory,
            projectConfig.getAggregationMode().getGraphMinimumDepth(targetGraph.getNodes().size()),
            ignoredTargetLabels);
    ExportedDepsClosureResolver exportedDepsClosureResolver =
        new ExportedDepsClosureResolver(targetGraph, ignoredTargetLabels);
    TransitiveDepsClosureResolver transitiveDepsClosureResolver =
        new TransitiveDepsClosureResolver(targetGraph, ignoredTargetLabels);
    ImmutableMap.Builder<IjProjectElement, ImmutableMap<IjProjectElement, DependencyType>>
        depsBuilder = ImmutableMap.builder();
    Set<IjLibrary> referencedLibraries = new HashSet<>();
    Optional<Path> extraCompileOutputRootPath = projectConfig.getExtraCompilerOutputModulesPath();

    for (IjModule module : ImmutableSet.copyOf(rulesToModules.values())) {
      Map<IjProjectElement, DependencyType> moduleDeps = new LinkedHashMap<>();

      for (Map.Entry<BuildTarget, DependencyType> entry : module.getDependencies().entrySet()) {
        BuildTarget depBuildTarget = entry.getKey();
        TargetNode<?, ?> depTargetNode = targetGraph.get(depBuildTarget);

        CommonDescriptionArg arg = (CommonDescriptionArg) depTargetNode.getConstructorArg();
        if (arg.labelsContainsAnyOf(ignoredTargetLabels)) {
          continue;
        }

        DependencyType depType = entry.getValue();
        ImmutableSet<IjProjectElement> depElements;
        ImmutableSet<IjProjectElement> transitiveDepElements = ImmutableSet.of();

        if (depType.equals(DependencyType.COMPILED_SHADOW)) {
          Optional<IjLibrary> library = libraryFactory.getLibrary(depTargetNode);
          if (library.isPresent()) {
            depElements = ImmutableSet.of(library.get());
          } else {
            depElements = ImmutableSet.of();
          }
        } else {
          depElements =
              getProjectElementFromBuildTargets(
                  targetGraph,
                  libraryFactory,
                  rulesToModules,
                  module,
                  Stream.concat(
                      exportedDepsClosureResolver.getExportedDepsClosure(depBuildTarget).stream(),
                      Stream.of(depBuildTarget)));
          if (projectConfig.isIncludeTransitiveDependency()) {
            transitiveDepElements =
                getProjectElementFromBuildTargets(
                    targetGraph,
                    libraryFactory,
                    rulesToModules,
                    module,
                    Stream.concat(
                        transitiveDepsClosureResolver
                            .getTransitiveDepsClosure(depBuildTarget)
                            .stream(),
                        Stream.of(depBuildTarget)));
          }
        }

        for (IjProjectElement depElement : transitiveDepElements) {
          Preconditions.checkState(!depElement.equals(module));
          DependencyType.putWithMerge(moduleDeps, depElement, DependencyType.RUNTIME);
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

      if (extraCompileOutputRootPath.isPresent()
          && !module.getExtraModuleDependencies().isEmpty()) {
        IjModule extraModule =
            createExtraModuleForCompilerOutput(module, extraCompileOutputRootPath.get());
        moduleDeps.put(extraModule, DependencyType.PROD);
        depsBuilder.put(extraModule, ImmutableMap.of());
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

  private static IjModule createExtraModuleForCompilerOutput(
      IjModule module, Path extraCompileOutputRootPath) {
    return IjModule.builder()
        .setModuleBasePath(extraCompileOutputRootPath.resolve(module.getModuleBasePath()))
        .setTargets(ImmutableSet.of())
        .addAllFolders(ImmutableSet.of())
        .putAllDependencies(ImmutableMap.of())
        .setLanguageLevel(module.getLanguageLevel())
        .setModuleType(IjModuleType.ANDROID_MODULE)
        .setCompilerOutputPath(module.getExtraModuleDependencies().asList().get(0))
        .build();
  }

  private static boolean isInRootCell(
      ProjectFilesystem projectFilesystem, TargetNode<?, ?> targetNode) {
    return targetNode.getFilesystem().equals(projectFilesystem);
  }

  private IjModuleGraphFactory() {}
}
