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

import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
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
   * BuildTarget can point to one IjModule (many:one mapping), the BuildTargets which
   * can't be prepresented in IntelliJ are missing from this mapping.
   */
  private static ImmutableMap<BuildTarget, IjModule> createModules(
      ProjectFilesystem projectFilesystem,
      IjProjectConfig projectConfig,
      TargetGraph targetGraph,
      IjModuleFactory moduleFactory,
      final int minimumPathDepth) {

    final BlockedPathNode blockedPathTree = createAggregationHaltPoints(projectConfig, targetGraph);

    ImmutableListMultimap<Path, TargetNode<?, ?>> baseTargetPathMultimap = targetGraph.getNodes()
        .stream()
        .filter(input -> SupportedTargetTypeRegistry.isTargetTypeSupported(
            input.getDescription().getClass()))
        // IntelliJ doesn't support referring to source files which aren't below the root of the
        // project. Filter out those cases proactively, so that we don't try to resolve files
        // relative to the wrong ProjectFilesystem.
        // Maybe one day someone will fix this.
        .filter(targetNode -> isInRootCell(projectFilesystem, targetNode))
        .collect(
            MoreCollectors.toImmutableListMultimap(
                targetNode -> {
                  Path path;
                  Path basePath = targetNode.getBuildTarget().getBasePath();
                  if (targetNode.getConstructorArg() instanceof AndroidResourceDescription.Arg) {
                    path = basePath;
                  } else {
                    path = simplifyPath(basePath, minimumPathDepth, blockedPathTree);
                  }
                  return path;
                },
                targetNode -> targetNode));

    ImmutableMap.Builder<BuildTarget, IjModule> moduleMapBuilder = new ImmutableMap.Builder<>();

    for (Path baseTargetPath : baseTargetPathMultimap.keySet()) {
      ImmutableSet<TargetNode<?, ?>> targets =
          ImmutableSet.copyOf(baseTargetPathMultimap.get(baseTargetPath));

      IjModule module = moduleFactory.createModule(baseTargetPath, targets);

      for (TargetNode<?, ?> target : targets) {
        moduleMapBuilder.put(target.getBuildTarget(), module);
      }
    }

    return moduleMapBuilder.build();
  }

  static Path simplifyPath(
      Path basePath,
      int minimumPathDepth,
      BlockedPathNode blockedPathTree) {
    int depthForPath = calculatePathDepth(basePath, minimumPathDepth, blockedPathTree);
    return basePath.subpath(0, depthForPath);
  }

  static int calculatePathDepth(
      Path basePath,
      int minimumPathDepth,
      BlockedPathNode blockedPathTree) {
    int maxDepth = basePath.getNameCount();
    if (minimumPathDepth >= maxDepth) {
      return maxDepth;
    }

    int depthForPath =
        blockedPathTree.findLowestPotentialBlockedOnPath(basePath, 0, maxDepth);

    return depthForPath < minimumPathDepth ? minimumPathDepth : depthForPath;
  }

  /**
   * Create the set of paths which should terminate aggregation.
   */
  private static BlockedPathNode createAggregationHaltPoints(
      IjProjectConfig projectConfig,
      TargetGraph targetGraph) {
    BlockedPathNode blockRoot = new BlockedPathNode();

    for (TargetNode<?, ?> node : targetGraph.getNodes()) {
      if (node.getConstructorArg() instanceof AndroidResourceDescription.Arg ||
          isNonDefaultJava(node, projectConfig.getJavaBuckConfig().getDefaultJavacOptions())) {
        Path blockedPath = node.getBuildTarget().getBasePath();
        blockRoot.markAsBlocked(blockedPath, 0, blockedPath.getNameCount());
      }
    }

    return blockRoot;
  }

  private static boolean isNonDefaultJava(TargetNode<?, ?> node, JavacOptions defaultJavacOptions) {
    if (!(node.getDescription() instanceof JavaLibraryDescription)) {
      return false;
    }

    String defaultSourceLevel = defaultJavacOptions.getSourceLevel();
    String defaultTargetLevel = defaultJavacOptions.getTargetLevel();
    JavaLibraryDescription.Arg arg = (JavaLibraryDescription.Arg) node.getConstructorArg();
    return !defaultSourceLevel.equals(arg.source.orElse(defaultSourceLevel)) ||
        !defaultTargetLevel.equals(arg.target.orElse(defaultTargetLevel));
  }

  /**
   * @param projectConfig the project config used
   * @param targetGraph input graph.
   * @param libraryFactory library factory.
   * @param moduleFactory module factory.
   * @return module graph corresponding to the supplied {@link TargetGraph}. Multiple targets from
   * the same base path are mapped to a single module, therefore an IjModuleGraph edge
   * exists between two modules (Ma, Mb) if a TargetGraph edge existed between a pair of
   * nodes (Ta, Tb) and Ma contains Ta and Mb contains Tb.
   */
  public static IjModuleGraph from(
      final ProjectFilesystem projectFilesystem,
      final IjProjectConfig projectConfig,
      final TargetGraph targetGraph,
      final IjLibraryFactory libraryFactory,
      final IjModuleFactory moduleFactory) {
    final ImmutableMap<BuildTarget, IjModule> rulesToModules =
        createModules(
            projectFilesystem,
            projectConfig,
            targetGraph,
            moduleFactory,
            projectConfig.getAggregationMode().getGraphMinimumDepth(targetGraph.getNodes().size()));
    final ExportedDepsClosureResolver exportedDepsClosureResolver =
        new ExportedDepsClosureResolver(targetGraph);
    ImmutableMap.Builder<IjProjectElement, ImmutableMap<IjProjectElement, DependencyType>>
        depsBuilder = ImmutableMap.builder();
    final Set<IjLibrary> referencedLibraries = new HashSet<>();

    for (final IjModule module : ImmutableSet.copyOf(rulesToModules.values())) {
      Map<IjProjectElement, DependencyType> moduleDeps = new HashMap<>();

      for (Map.Entry<BuildTarget, DependencyType> entry : module.getDependencies().entrySet()) {
        BuildTarget depBuildTarget = entry.getKey();
        DependencyType depType = entry.getValue();
        ImmutableSet<IjProjectElement> depElements;

        if (depType.equals(DependencyType.COMPILED_SHADOW)) {
          TargetNode<?, ?> targetNode = targetGraph.get(depBuildTarget);
          Optional<IjLibrary> library = libraryFactory.getLibrary(targetNode);
          if (library.isPresent()) {
            depElements = ImmutableSet.of(library.get());
          } else {
            depElements = ImmutableSet.of();
          }
        } else {
          depElements = Stream
              .concat(
                  exportedDepsClosureResolver.getExportedDepsClosure(depBuildTarget).stream(),
                  Stream.of(depBuildTarget))
              .filter(input -> {
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
                    TargetNode<?, ?> targetNode = targetGraph.get(input);
                    return !module.getTargets().contains(targetNode);
                  })
              .map(
                  depTarget-> {
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
        IjLibrary extraClassPathLibrary = IjLibrary.builder()
            .setClassPaths(module.getExtraClassPathDependencies())
            .setTargets(ImmutableSet.of())
            .setName("library_" + module.getName() + "_extra_classpath")
            .build();
        moduleDeps.put(extraClassPathLibrary, DependencyType.PROD);
      }

      moduleDeps.keySet()
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
      ProjectFilesystem projectFilesystem,
      TargetNode<?, ?> targetNode) {
    return targetNode.getFilesystem().equals(projectFilesystem);
  }

  private IjModuleGraphFactory() {
  }
}
