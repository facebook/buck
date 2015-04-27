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

import com.facebook.buck.android.AndroidAarDescription;
import com.facebook.buck.android.AndroidBinaryDescription;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidPrebuiltAarDescription;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.NdkLibraryDescription;
import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.graph.DefaultTraversableGraph;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.java.JavaBinaryDescription;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.JavaTestDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ProjectConfigDescription;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

/**
 * Represents a graph of IjModules and the dependencies between them.
 */
public class IjModuleGraph extends DefaultTraversableGraph<IjModule> {

  /**
   * These target types are mapped onto .iml module files.
   */
  private static final ImmutableSet<BuildRuleType> SUPPORTED_MODULE_TYPES = ImmutableSet.of(
      AndroidAarDescription.TYPE,
      AndroidBinaryDescription.TYPE,
      AndroidLibraryDescription.TYPE,
      AndroidPrebuiltAarDescription.TYPE,
      AndroidResourceDescription.TYPE,
      JavaBinaryDescription.TYPE,
      JavaLibraryDescription.TYPE,
      JavaTestDescription.TYPE,
      ProjectConfigDescription.TYPE,
      NdkLibraryDescription.TYPE);

  private static final Predicate<TargetNode<?>> SUPPORTED_MODULE_TYPES_PREDICATE =
      new Predicate<TargetNode<?>>() {
        @Override
        public boolean apply(TargetNode<?> input) {
          return SUPPORTED_MODULE_TYPES.contains(input.getType());
        }
      };


  /**
   * Create all the modules we are capable of representing in IntelliJ from the supplied graph.
   *
   * @param targetGraph graph whose nodes will be converted to {@link IjModule}s.
   * @return map which for every BuildTarget points to the corresponding IjModule. Multiple
   *             BuildTarget can point to one IjModule (many:one mapping), the BuildTargets which
   *             can't be prepresented in IntelliJ are missing from this mapping.
   */
  private static ImmutableMap<BuildTarget, IjModule> createModules(
      TargetGraph targetGraph) {
    IjLibraryFactory libraryFactory = IjLibraryFactory.create(targetGraph.getNodes());
    IjModuleFactory moduleFactory = new IjModuleFactory(libraryFactory);
    ImmutableSet<TargetNode<?>> supportedTargets = FluentIterable.from(targetGraph.getNodes())
        .filter(SUPPORTED_MODULE_TYPES_PREDICATE)
        .toSet();
    ImmutableListMultimap<Path, TargetNode<?>> baseTargetPathMultimap =
        FluentIterable.from(supportedTargets).index(
            new Function<TargetNode<?>, Path>() {
              @Override
              public Path apply(TargetNode<?> input) {
                return input.getBuildTarget().getBasePath();
              }
            });

    ImmutableMap.Builder<BuildTarget, IjModule> moduleMapBuilder = new ImmutableMap.Builder<>();

    for (Path baseTargetPath: baseTargetPathMultimap.keySet()) {
      ImmutableSet<TargetNode<?>> targets =
          FluentIterable.from(baseTargetPathMultimap.get(baseTargetPath)).toSet();

      IjModule module = moduleFactory.createModule(baseTargetPath, targets);

      for (TargetNode<?> target : targets) {
        moduleMapBuilder.put(target.getBuildTarget(), module);
      }
    }

    return moduleMapBuilder.build();
  }

  /**
   * @param targetGraph input graph.
   * @return module graph corresponding to the supplied {@link TargetGraph}. Multiple targets from
   *         the same base path are mapped to a single module, therefore an IjModuleGraph edge
   *         exists between two modules (Ma, Mb) if a TargetGraph edge existed between a pair of
   *         nodes (Ta, Tb) and Ma contains Ta and Mb contains Tb.
   */
  public static IjModuleGraph from(TargetGraph targetGraph) {
    final MutableDirectedGraph<IjModule> moduleGraph = new MutableDirectedGraph<>();
    final ImmutableMap<BuildTarget, IjModule> rulesToModules =
        createModules(targetGraph);
    final ExportedDepsClosureResolver exportedDepsClosureResolver =
        new ExportedDepsClosureResolver(targetGraph);

    for (IjModule module : rulesToModules.values()) {
      moduleGraph.addNode(module);
    }

    AbstractBottomUpTraversal<TargetNode<?>, IjModuleGraph> bottomUpTraversal =
        new AbstractBottomUpTraversal<TargetNode<?>, IjModuleGraph>(targetGraph) {
          @Override
          public void visit(TargetNode<?> node) {
            IjModule module = rulesToModules.get(node.getBuildTarget());
            if (module == null) {
              return;
            }

            ImmutableSet<BuildTarget> deps = FluentIterable.from(node.getDeps())
                .transformAndConcat(
                    new Function<BuildTarget, Iterable<BuildTarget>>() {
                      @Override
                      public Iterable<BuildTarget> apply(BuildTarget input) {
                        return exportedDepsClosureResolver.getExportedDepsClosure(input);
                      }
                    })
                .append(node.getDeps())
                .toSet();

            for (BuildTarget dep : deps) {
              IjModule depModule = rulesToModules.get(dep);
              if (depModule == null || depModule.equals(module)) {
                continue;
              }
              moduleGraph.addEdge(module, depModule);
            }
          }

          @Override
          public IjModuleGraph getResult() {
            return new IjModuleGraph(moduleGraph);
          }
        };

    bottomUpTraversal.traverse();
    return bottomUpTraversal.getResult();
  }

  public IjModuleGraph(MutableDirectedGraph<IjModule> graph) {
    super(graph);
  }
}
