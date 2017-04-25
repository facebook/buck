/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.graph.DirectedAcyclicGraph;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.classes.ClasspathTraversal;
import com.facebook.buck.jvm.java.classes.ClasspathTraverser;
import com.facebook.buck.jvm.java.classes.DefaultClasspathTraverser;
import com.facebook.buck.jvm.java.classes.FileLike;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Function;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Utility class for grouping sets of targets and their dependencies into APK Modules containing
 * their exclusive dependencies. Targets that are dependencies of the root target are included in
 * the root. Targets that are dependencies of two or more groups but not dependencies of the root
 * are added to their own group.
 */
public class APKModuleGraph {

  static final String ROOT_APKMODULE_NAME = "dex";

  private final TargetGraph targetGraph;
  private final BuildTarget target;
  private final Optional<Map<String, List<BuildTarget>>> suppliedSeedConfigMap;
  private final Optional<Set<BuildTarget>> seedTargets;

  private final Supplier<ImmutableMap<BuildTarget, APKModule>> targetToModuleMapSupplier =
      Suppliers.memoize(
          new Supplier<ImmutableMap<BuildTarget, APKModule>>() {
            @Override
            public ImmutableMap<BuildTarget, APKModule> get() {
              final ImmutableMap.Builder<BuildTarget, APKModule> mapBuilder =
                  ImmutableMap.builder();
              new AbstractBreadthFirstTraversal<APKModule>(
                  getGraph().getNodesWithNoIncomingEdges()) {
                @Override
                public ImmutableSet<APKModule> visit(final APKModule node) {
                  if (node.equals(rootAPKModuleSupplier.get())) {
                    return ImmutableSet.of();
                  }
                  node.getBuildTargets().forEach(input -> mapBuilder.put(input, node));
                  return getGraph().getOutgoingNodesFor(node);
                }
              }.start();
              return mapBuilder.build();
            }
          });

  private final Supplier<APKModule> rootAPKModuleSupplier =
      Suppliers.memoize(this::generateRootModule);

  private final Supplier<DirectedAcyclicGraph<APKModule>> graphSupplier =
      Suppliers.memoize(this::generateGraph);

  private final Supplier<ImmutableSet<APKModule>> modulesSupplier =
      Suppliers.memoize(
          () -> {
            final ImmutableSet.Builder<APKModule> moduleBuilder = ImmutableSet.builder();
            new AbstractBreadthFirstTraversal<APKModule>(getRootAPKModule()) {
              @Override
              public Iterable<APKModule> visit(APKModule apkModule) throws RuntimeException {
                moduleBuilder.add(apkModule);
                return getGraph().getIncomingNodesFor(apkModule);
              }
            }.start();
            return moduleBuilder.build();
          });

  private final Supplier<Optional<Map<String, List<BuildTarget>>>> configMapSupplier =
      Suppliers.memoize(this::generateSeedConfigMap);

  /**
   * Constructor for the {@code APKModule} graph generator object
   *
   * @param seedConfigMap A map of names to seed targets to use for creating {@code APKModule}.
   * @param targetGraph The full target graph of the build
   * @param target The root target to use to traverse the graph
   */
  public APKModuleGraph(
      final Optional<Map<String, List<BuildTarget>>> seedConfigMap,
      final TargetGraph targetGraph,
      final BuildTarget target) {
    this.targetGraph = targetGraph;
    this.target = target;
    this.seedTargets = Optional.empty();
    this.suppliedSeedConfigMap = seedConfigMap;
  }

  /**
   * Constructor for the {@code APKModule} graph generator object
   *
   * @param targetGraph The full target graph of the build
   * @param target The root target to use to traverse the graph
   * @param seedTargets The set of seed targets to use for creating {@code APKModule}.
   */
  public APKModuleGraph(
      final TargetGraph targetGraph,
      final BuildTarget target,
      final Optional<Set<BuildTarget>> seedTargets) {
    this.targetGraph = targetGraph;
    this.target = target;
    this.seedTargets = seedTargets;
    this.suppliedSeedConfigMap = Optional.empty();
  }

  private Optional<Map<String, List<BuildTarget>>> generateSeedConfigMap() {
    if (suppliedSeedConfigMap.isPresent()) {
      return suppliedSeedConfigMap;
    }
    if (!seedTargets.isPresent()) {
      return Optional.empty();
    }
    HashMap<String, List<BuildTarget>> seedConfigMapMutable = new HashMap<>();
    for (BuildTarget seedTarget : seedTargets.get()) {
      final String moduleName = generateNameFromTarget(seedTarget);
      seedConfigMapMutable.put(moduleName, ImmutableList.of(seedTarget));
    }
    ImmutableMap<String, List<BuildTarget>> seedConfigMapImmutable =
        ImmutableMap.copyOf(seedConfigMapMutable);
    return Optional.of(seedConfigMapImmutable);
  }

  /**
   * Lazy generate the graph on first use
   *
   * @return the DAG representing APKModules and their dependency relationships
   */
  public DirectedAcyclicGraph<APKModule> getGraph() {
    return graphSupplier.get();
  }

  /**
   * Get the APKModule representing the core application that is always included in the apk
   *
   * @return the root APK Module
   */
  public APKModule getRootAPKModule() {
    return rootAPKModuleSupplier.get();
  }

  public ImmutableSet<APKModule> getAPKModules() {
    return modulesSupplier.get();
  }

  public Optional<Map<String, List<BuildTarget>>> getSeedConfigMap() {
    return configMapSupplier.get();
  }

  /**
   * Get the Module that contains the given target
   *
   * @param target target to serach for in modules
   * @return the module that contains the target
   */
  public APKModule findModuleForTarget(BuildTarget target) {
    APKModule module = targetToModuleMapSupplier.get().get(target);
    return (module == null ? rootAPKModuleSupplier.get() : module);
  }

  /**
   * Group the classes in the input jars into a multimap based on the APKModule they belong to
   *
   * @param apkModuleToJarPathMap the mapping of APKModules to the path for the jar files
   * @param translatorFunction function used to translate obfuscated names
   * @param filesystem filesystem representation for resolving paths
   * @return The mapping of APKModules to the class names they contain
   * @throws IOException
   */
  public static ImmutableMultimap<APKModule, String> getAPKModuleToClassesMap(
      final ImmutableMultimap<APKModule, Path> apkModuleToJarPathMap,
      final Function<String, String> translatorFunction,
      final ProjectFilesystem filesystem)
      throws IOException {
    final ImmutableMultimap.Builder<APKModule, String> builder = ImmutableMultimap.builder();
    if (!apkModuleToJarPathMap.isEmpty()) {
      for (final APKModule dexStore : apkModuleToJarPathMap.keySet()) {
        for (Path jarFilePath : apkModuleToJarPathMap.get(dexStore)) {
          ClasspathTraverser classpathTraverser = new DefaultClasspathTraverser();
          classpathTraverser.traverse(
              new ClasspathTraversal(ImmutableSet.of(jarFilePath), filesystem) {
                @Override
                public void visit(FileLike entry) {
                  if (!entry.getRelativePath().endsWith(".class")) {
                    // ignore everything but class files in the jar.
                    return;
                  }

                  String classpath = entry.getRelativePath().replaceAll("\\.class$", "");

                  builder.put(dexStore, translatorFunction.apply(classpath));
                }
              });
        }
      }
    }
    return builder.build();
  }

  /**
   * Generate the graph by identifying root targets, then marking targets with the seeds they are
   * reachable with, then consolidating the targets reachable by multiple seeds into shared modules
   *
   * @return The graph of APKModules with edges representing dependencies between modules
   */
  private DirectedAcyclicGraph<APKModule> generateGraph() {
    final MutableDirectedGraph<APKModule> apkModuleGraph = new MutableDirectedGraph<>();

    apkModuleGraph.addNode(rootAPKModuleSupplier.get());

    if (getSeedConfigMap().isPresent()) {
      HashMultimap<BuildTarget, String> targetToContainingApkModulesMap =
          mapTargetsToContainingModules();
      generateSharedModules(apkModuleGraph, targetToContainingApkModulesMap);
    }

    return new DirectedAcyclicGraph<>(apkModuleGraph);
  }

  /**
   * This walks through the target graph starting from the root target and adds all reachable
   * targets that are not seed targets to the root module
   *
   * @return The root APK Module
   */
  private APKModule generateRootModule() {
    final Set<BuildTarget> rootTargets = new HashSet<>();
    if (targetGraph != TargetGraph.EMPTY) {
      new AbstractBreadthFirstTraversal<TargetNode<?, ?>>(targetGraph.get(target)) {
        @Override
        public ImmutableSet<TargetNode<?, ?>> visit(TargetNode<?, ?> node) {

          ImmutableSet.Builder<TargetNode<?, ?>> depsBuilder = ImmutableSet.builder();
          for (BuildTarget depTarget : node.getBuildDeps()) {
            if (!isSeedTarget(depTarget)) {
              depsBuilder.add(targetGraph.get(depTarget));
              rootTargets.add(depTarget);
            }
          }
          return depsBuilder.build();
        }
      }.start();
    }
    return APKModule.builder().setName(ROOT_APKMODULE_NAME).setBuildTargets(rootTargets).build();
  }

  /**
   * For each seed target, find its reachable targets and mark them in a multimap as being reachable
   * by that module for later sorting into exclusive and shared targets
   *
   * @return the Multimap containing targets and the seed modules that contain them
   */
  private HashMultimap<BuildTarget, String> mapTargetsToContainingModules() {
    final HashMultimap<BuildTarget, String> targetToContainingApkModuleNameMap =
        HashMultimap.create();
    for (Map.Entry<String, List<BuildTarget>> seedConfig : getSeedConfigMap().get().entrySet()) {
      final String seedModuleName = seedConfig.getKey();
      for (BuildTarget seedTarget : seedConfig.getValue()) {
        targetToContainingApkModuleNameMap.put(seedTarget, seedModuleName);
        new AbstractBreadthFirstTraversal<TargetNode<?, ?>>(targetGraph.get(seedTarget)) {
          @Override
          public ImmutableSet<TargetNode<?, ?>> visit(TargetNode<?, ?> node) {

            ImmutableSet.Builder<TargetNode<?, ?>> depsBuilder = ImmutableSet.builder();
            for (BuildTarget depTarget : node.getBuildDeps()) {
              if (!isInRootModule(depTarget) && !isSeedTarget(depTarget)) {
                depsBuilder.add(targetGraph.get(depTarget));
                targetToContainingApkModuleNameMap.put(depTarget, seedModuleName);
              }
            }
            return depsBuilder.build();
          }
        }.start();
      }
    }
    return targetToContainingApkModuleNameMap;
  }

  /**
   * Loop through each of the targets we visited while generating seed modules: If the are exclusive
   * to that module, add them to that module. If they are not exclusive to that module, find or
   * create an appropriate shared module and fill out its dependencies
   *
   * @param apkModuleGraph the current graph we're building
   * @param targetToContainingApkModulesMap the targets mapped to the seed targets they are
   *     reachable from
   */
  private void generateSharedModules(
      MutableDirectedGraph<APKModule> apkModuleGraph,
      HashMultimap<BuildTarget, String> targetToContainingApkModulesMap) {

    // Sort the targets into APKModuleBuilders based on their seed dependencies
    final Map<ImmutableSet<String>, APKModule.Builder> combinedModuleHashToModuleMap =
        new HashMap<>();
    for (Map.Entry<BuildTarget, Collection<String>> entry :
        targetToContainingApkModulesMap.asMap().entrySet()) {
      ImmutableSet<String> containingModuleSet = ImmutableSet.copyOf(entry.getValue());
      boolean exists = false;
      for (Map.Entry<ImmutableSet<String>, APKModule.Builder> existingEntry :
          combinedModuleHashToModuleMap.entrySet()) {
        if (existingEntry.getKey().equals(containingModuleSet)) {
          existingEntry.getValue().addBuildTargets(entry.getKey());
          exists = true;
          break;
        }
      }

      if (!exists) {
        String name =
            containingModuleSet.size() == 1
                ? containingModuleSet.iterator().next()
                : generateNameFromTarget(entry.getKey());
        combinedModuleHashToModuleMap.put(
            containingModuleSet, APKModule.builder().setName(name).addBuildTargets(entry.getKey()));
      }
    }

    // Find the seed modules and add them to the graph
    Map<String, APKModule> seedModules = new HashMap<>();
    for (Map.Entry<ImmutableSet<String>, APKModule.Builder> entry :
        combinedModuleHashToModuleMap.entrySet()) {
      if (entry.getKey().size() == 1) {
        APKModule seed = entry.getValue().build();
        apkModuleGraph.addNode(seed);
        seedModules.put(entry.getKey().iterator().next(), seed);
        apkModuleGraph.addEdge(seed, rootAPKModuleSupplier.get());
      }
    }

    // Find the shared modules and add them to the graph
    for (Map.Entry<ImmutableSet<String>, APKModule.Builder> entry :
        combinedModuleHashToModuleMap.entrySet()) {
      if (entry.getKey().size() > 1) {
        APKModule shared = entry.getValue().build();
        apkModuleGraph.addNode(shared);
        apkModuleGraph.addEdge(shared, rootAPKModuleSupplier.get());
        for (String seedName : entry.getKey()) {
          apkModuleGraph.addEdge(seedModules.get(seedName), shared);
        }
      }
    }
  }

  private boolean isInRootModule(BuildTarget depTarget) {
    ImmutableSet<BuildTarget> rootTargets = rootAPKModuleSupplier.get().getBuildTargets();
    return rootTargets != null && rootTargets.contains(depTarget);
  }

  private boolean isSeedTarget(BuildTarget depTarget) {
    if (!getSeedConfigMap().isPresent()) {
      return false;
    }
    for (List<BuildTarget> targetsPerConfig : getSeedConfigMap().get().values()) {
      if (targetsPerConfig.contains(depTarget)) {
        return true;
      }
    }
    return false;
  }

  private static String generateNameFromTarget(BuildTarget androidModuleTarget) {
    String replacementPattern = "[/\\\\#-]";
    String shortName =
        androidModuleTarget.getShortNameAndFlavorPostfix().replaceAll(replacementPattern, ".");
    String name = androidModuleTarget.getBasePath().toString().replaceAll(replacementPattern, ".");
    if (name.endsWith(shortName)) {
      // return just the base path, ignoring the target name that is the same as its parent
      return name;
    } else {
      return name.isEmpty() ? shortName : name + "." + shortName;
    }
  }
}
