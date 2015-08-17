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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Ascii;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Represents a graph of IjModules and the dependencies between them.
 */
public class IjModuleGraph {

  enum DependencyType {
    /**
     * The current {@link IjModule} depends on the other element from test code only. This
     * only happens if a particular module contains both test and production code and only code in
     * the test folders needs to reference the other element.
     */
    TEST,
    /**
     * The current {@link IjModule} depends on the other element from production (non-test)
     * code.
     */
    PROD,
    /**
     * This dependency means that the other element contains a compiled counterpart to this element.
     * This is used when the current element uses BUCK features which cannot be expressed in
     * IntelliJ.
     */
    COMPILED_SHADOW,
    ;

    public static DependencyType merge(DependencyType left, DependencyType right) {
      if (left.equals(right)) {
        return left;
      }
      Preconditions.checkArgument(
          !left.equals(COMPILED_SHADOW) && !right.equals(COMPILED_SHADOW),
          "The COMPILED_SHADOW type cannot be merged with other types.");
      return DependencyType.PROD;
    }

    public static <T> void putWithMerge(Map<T, DependencyType> map, T key, DependencyType value) {
      DependencyType oldValue = map.get(key);
      if (oldValue != null) {
        value = merge(oldValue, value);
      }
      map.put(key, value);
    }
  }

  /**
   * Indicates how to aggregate {@link TargetNode}s into {@link IjModule}s.
   */
  public enum AggregationMode {
    AUTO,
    NONE,
    SHALLOW,
    ;

    public static final int MIN_SHALLOW_GRAPH_SIZE = 500;
    public static final int SHALLOW_MAX_PATH_LENGTH = 3;

    public Function<Path, Path> getBasePathTransform(int graphSize) {

      if (this == AUTO && graphSize < MIN_SHALLOW_GRAPH_SIZE || this == NONE) {
        return Functions.identity();
      }

      return new Function<Path, Path>() {
        @Nullable
        @Override
        public Path apply(@Nullable Path input) {
          if (input == null || input.getNameCount() <= SHALLOW_MAX_PATH_LENGTH) {
            return input;
          }
          return input.subpath(0, SHALLOW_MAX_PATH_LENGTH);
        }
      };
    }

    public static Function<String, AggregationMode> fromStringFunction() {
      return new Function<String, AggregationMode>() {
        @Override
        public AggregationMode apply(String input) {
          switch (Ascii.toLowerCase(input)) {
            case "shallow":
              return SHALLOW;
            case "none":
              return NONE;
            case "auto":
              return AUTO;
            default:
              throw new HumanReadableException("Invalid aggregation mode value %s.", input);
          }
        }
      };
    }
  }

  /**
   * Create all the modules we are capable of representing in IntelliJ from the supplied graph.
   *
   * @param targetGraph graph whose nodes will be converted to {@link IjModule}s.
   * @return map which for every BuildTarget points to the corresponding IjModule. Multiple
   * BuildTarget can point to one IjModule (many:one mapping), the BuildTargets which
   * can't be prepresented in IntelliJ are missing from this mapping.
   */
  private static ImmutableMap<BuildTarget, IjModule> createModules(
      TargetGraph targetGraph,
      IjModuleFactory moduleFactory,
      final Function<Path, Path> basePathTransform) {
    ImmutableSet<TargetNode<?>> supportedTargets = FluentIterable.from(targetGraph.getNodes())
        .filter(IjModuleFactory.SUPPORTED_MODULE_TYPES_PREDICATE)
        .toSet();
    ImmutableListMultimap<Path, TargetNode<?>> baseTargetPathMultimap =
        FluentIterable.from(supportedTargets).index(
            new Function<TargetNode<?>, Path>() {
              @Override
              public Path apply(TargetNode<?> input) {
                return basePathTransform.apply(input.getBuildTarget().getBasePath());
              }
            });

    ImmutableMap.Builder<BuildTarget, IjModule> moduleMapBuilder = new ImmutableMap.Builder<>();

    for (Path baseTargetPath : baseTargetPathMultimap.keySet()) {
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
   * @param libraryFactory library factory.
   * @param moduleFactory module factory.
   * @param aggregationMode module aggregation mode.
   * @return module graph corresponding to the supplied {@link TargetGraph}. Multiple targets from
   * the same base path are mapped to a single module, therefore an IjModuleGraph edge
   * exists between two modules (Ma, Mb) if a TargetGraph edge existed between a pair of
   * nodes (Ta, Tb) and Ma contains Ta and Mb contains Tb.
   */
  public static IjModuleGraph from(
      final TargetGraph targetGraph,
      final IjLibraryFactory libraryFactory,
      final IjModuleFactory moduleFactory,
      AggregationMode aggregationMode) {
    final ImmutableMap<BuildTarget, IjModule> rulesToModules =
        createModules(
            targetGraph,
            moduleFactory,
            aggregationMode.getBasePathTransform(targetGraph.getNodes().size()));
    final ExportedDepsClosureResolver exportedDepsClosureResolver =
        new ExportedDepsClosureResolver(targetGraph);
    ImmutableMap.Builder<IjProjectElement, ImmutableMap<IjProjectElement, DependencyType>>
        depsBuilder = ImmutableMap.builder();
    final Set<IjLibrary> referencedLibraries = new HashSet<>();

    for (final IjModule module : FluentIterable.from(rulesToModules.values()).toSet()) {
      Map<IjProjectElement, DependencyType> moduleDeps = new HashMap<>();

      for (Map.Entry<BuildTarget, DependencyType> entry : module.getDependencies().entrySet()) {
        BuildTarget depBuildTarget = entry.getKey();
        DependencyType depType = entry.getValue();
        ImmutableSet<IjProjectElement> depElements;

        if (depType.equals(DependencyType.COMPILED_SHADOW)) {
          TargetNode<?> targetNode = Preconditions.checkNotNull(targetGraph.get(depBuildTarget));
          Optional<IjLibrary> library = libraryFactory.getLibrary(targetNode);
          if (library.isPresent()) {
            depElements = ImmutableSet.<IjProjectElement>of(library.get());
          } else {
            depElements = ImmutableSet.of();
          }
        } else {
          depElements = FluentIterable.from(
              exportedDepsClosureResolver.getExportedDepsClosure(depBuildTarget))
              .append(depBuildTarget)
              .filter(
                  new Predicate<BuildTarget>() {
                    @Override
                    public boolean apply(BuildTarget input) {
                      // The exported deps closure can contain references back to targets contained
                      // in the module, so filter those out.
                      TargetNode<?> targetNode = targetGraph.get(input);
                      return !module.getTargets().contains(targetNode);
                    }
                  })
              .transform(
                  new Function<BuildTarget, IjProjectElement>() {
                    @Nullable
                    @Override
                    public IjProjectElement apply(BuildTarget depTarget) {
                      IjModule depModule = rulesToModules.get(depTarget);
                      if (depModule != null) {
                        return depModule;
                      }
                      TargetNode<?> targetNode =
                          Preconditions.checkNotNull(targetGraph.get(depTarget));
                      IjLibrary library = libraryFactory.getLibrary(targetNode).orNull();
                      return library;
                    }
                  })
              .filter(Predicates.notNull())
              .toSet();
        }

        for (IjProjectElement depElement : depElements) {
          Preconditions.checkState(!depElement.equals(module));
          DependencyType.putWithMerge(moduleDeps, depElement, depType);
        }
      }

      if (!module.getExtraClassPathDependencies().isEmpty()) {
        IjLibrary extraClassPathLibrary = IjLibrary.builder()
            .setClassPaths(module.getExtraClassPathDependencies())
            .setTargets(ImmutableSet.<TargetNode<?>>of())
            .setName("library_" + module.getName() + "_extra_classpath")
            .build();
        moduleDeps.put(extraClassPathLibrary, DependencyType.PROD);
      }

      referencedLibraries.addAll(
          FluentIterable.from(moduleDeps.keySet())
              .filter(IjLibrary.class)
              .toSet());

      depsBuilder.put(module, ImmutableMap.copyOf(moduleDeps));
    }

    for (IjLibrary library : referencedLibraries) {
      depsBuilder.put(library, ImmutableMap.<IjProjectElement, DependencyType>of());
    }

    return new IjModuleGraph(depsBuilder.build());
  }

  public ImmutableSet<IjProjectElement> getNodes() {
    return deps.keySet();
  }

  public ImmutableSet<IjModule> getModuleNodes() {
    return FluentIterable.from(deps.keySet()).filter(IjModule.class).toSet();
  }

  public ImmutableMap<IjProjectElement, DependencyType> getDepsFor(IjProjectElement source) {
    return Optional.fromNullable(deps.get(source))
        .or(ImmutableMap.<IjProjectElement, DependencyType>of());
  }

  public ImmutableMap<IjModule, DependencyType> getDependentModulesFor(IjModule source) {
    final ImmutableMap<IjProjectElement, DependencyType> deps = getDepsFor(source);
    return FluentIterable.from(deps.keySet()).filter(IjModule.class)
        .toMap(
            new Function<IjModule, DependencyType>() {
              @Override
              public DependencyType apply(IjModule input) {
                return Preconditions.checkNotNull(deps.get(input));
              }
            });
  }

  public ImmutableMap<IjLibrary, DependencyType> getDependentLibrariesFor(IjModule source) {
    final ImmutableMap<IjProjectElement, DependencyType> deps = getDepsFor(source);
    return FluentIterable.from(deps.keySet()).filter(IjLibrary.class)
        .toMap(
            new Function<IjLibrary, DependencyType>() {
              @Override
              public DependencyType apply(IjLibrary input) {
                return Preconditions.checkNotNull(deps.get(input));
              }
            });
  }

  private static void checkNamesAreUnique(
      ImmutableMap<IjProjectElement, ImmutableMap<IjProjectElement, DependencyType>> deps) {
    Set<String> names = new HashSet<>();
    for (IjProjectElement element : deps.keySet()) {
      String name = element.getName();
      Preconditions.checkArgument(!names.contains(name));
      names.add(name);
    }
  }

  private ImmutableMap<IjProjectElement, ImmutableMap<IjProjectElement, DependencyType>> deps;

  public IjModuleGraph(
      ImmutableMap<IjProjectElement, ImmutableMap<IjProjectElement, DependencyType>> deps) {
    this.deps = deps;
    checkNamesAreUnique(deps);
  }
}
