/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.android.apkmodule;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.core.util.graph.DirectedAcyclicGraph;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.classes.ClasspathTraversal;
import com.facebook.buck.jvm.java.classes.ClasspathTraverser;
import com.facebook.buck.jvm.java.classes.DefaultClasspathTraverser;
import com.facebook.buck.jvm.java.classes.FileLike;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Utility class for grouping sets of targets and their dependencies into APK Modules containing
 * their exclusive dependencies. Targets that are dependencies of the root target are included in
 * the root. Targets that have dependencies in two are more groups are put the APKModule that
 * represents the dependent modules minimal cover based on the declared dependencies given. If the
 * minimal cover contains more than one APKModule, the target will belong to a new shared APKModule
 * that is a dependency of all APKModules in the minimal cover.
 */
public class APKModuleGraph implements AddsToRuleKey {

  public static final String ROOT_APKMODULE_NAME = "dex";

  private final TargetGraph targetGraph;
  @AddToRuleKey private final BuildTarget target;

  @AddToRuleKey
  private final Optional<ImmutableMap<String, ImmutableList<BuildTarget>>> suppliedSeedConfigMap;

  @AddToRuleKey
  private final Optional<ImmutableMap<String, ImmutableList<String>>> appModuleDependencies;

  @AddToRuleKey private final Optional<List<BuildTarget>> blacklistedModules;
  @AddToRuleKey private final Set<String> modulesWithResources;
  @AddToRuleKey private final Set<String> modulesWithManifest;
  private final Optional<Set<BuildTarget>> seedTargets;
  private final Map<APKModule, Set<BuildTarget>> buildTargetsMap = new HashMap<>();

  private final Supplier<ImmutableMap<BuildTarget, APKModule>> targetToModuleMapSupplier =
      MoreSuppliers.memoize(
          () -> {
            Builder<BuildTarget, APKModule> mapBuilder = ImmutableMap.builder();
            new AbstractBreadthFirstTraversal<APKModule>(getGraph().getNodesWithNoIncomingEdges()) {
              @Override
              public ImmutableSet<APKModule> visit(APKModule node) {
                if (node.equals(rootAPKModuleSupplier.get())) {
                  return ImmutableSet.of();
                }
                getBuildTargets(node).forEach(input -> mapBuilder.put(input, node));
                return getGraph().getOutgoingNodesFor(node);
              }
            }.start();
            return mapBuilder.build();
          });

  private final Supplier<APKModule> rootAPKModuleSupplier =
      MoreSuppliers.memoize(this::generateRootModule);

  private final Supplier<DirectedAcyclicGraph<APKModule>> graphSupplier =
      MoreSuppliers.memoize(this::generateGraph);

  private final Supplier<DirectedAcyclicGraph<String>> declaredDependencyGraphSupplier =
      MoreSuppliers.memoize(this::generateDeclaredDependencyGraph);

  private final Supplier<ImmutableSet<APKModule>> modulesSupplier =
      MoreSuppliers.memoize(
          () -> {
            ImmutableSet.Builder<APKModule> moduleBuilder = ImmutableSet.builder();
            new AbstractBreadthFirstTraversal<APKModule>(getRootAPKModule()) {
              @Override
              public Iterable<APKModule> visit(APKModule apkModule) throws RuntimeException {
                moduleBuilder.add(apkModule);
                return getGraph().getIncomingNodesFor(apkModule);
              }
            }.start();
            return moduleBuilder.build();
          });

  private final Supplier<ImmutableMultimap<BuildTarget, String>> sharedSeedsSupplier =
      MoreSuppliers.memoize(this::generateSharedSeeds);

  private final Supplier<Optional<ImmutableMap<String, ImmutableList<BuildTarget>>>>
      configMapSupplier = MoreSuppliers.memoize(this::generateSeedConfigMap);

  /**
   * Constructor for the {@code APKModule} graph generator object that produces a graph with only a
   * root module.
   */
  public APKModuleGraph(TargetGraph targetGraph, BuildTarget target) {
    this(
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        ImmutableSet.of(),
        ImmutableSet.of(),
        targetGraph,
        target);
  }
  /**
   * Constructor for the {@code APKModule} graph generator object
   *
   * @param seedConfigMap A map of names to seed targets to use for creating {@code APKModule}.
   * @param appModuleDependencies
   *     <p>a mapping of declared dependencies between module names. If a APKModule <b>m1</b>
   *     depends on <b>m2</b>, it implies to buck that in order for <b>m1</b> to be available for
   *     execution <b>m2</b> must be available for use as well. Because of this, we can say that
   *     including a buck target in <b>m2</b> effectively includes the buck-target in <b>m2's</b>
   *     dependent <b>m1</b>. In other words, <b>m2</b> covers <b>m1</b>. Therefore, if a buck
   *     target is required by both these modules, we can safely place it in the minimal cover which
   *     is the APKModule <b>m2</b>.
   * @param blacklistedModules A list of targets that will NOT be included in any module.
   * @param modulesWithResources A list of modules with resources
   * @param modulesWithManifest A list of modules with manifests. This parameter will be later used
   *     to fetch Manifest information for each module.
   * @param targetGraph The full target graph of the build
   * @param target The root target to use to traverse the graph
   */
  public APKModuleGraph(
      Optional<ImmutableMap<String, ImmutableList<BuildTarget>>> seedConfigMap,
      Optional<ImmutableMap<String, ImmutableList<String>>> appModuleDependencies,
      Optional<List<BuildTarget>> blacklistedModules,
      Set<String> modulesWithResources,
      Set<String> modulesWithManifest,
      TargetGraph targetGraph,
      BuildTarget target) {
    this.targetGraph = targetGraph;
    this.appModuleDependencies = appModuleDependencies;
    this.blacklistedModules = blacklistedModules;
    this.modulesWithResources = modulesWithResources;
    this.modulesWithManifest = modulesWithManifest;
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
      TargetGraph targetGraph, BuildTarget target, Optional<Set<BuildTarget>> seedTargets) {
    this.targetGraph = targetGraph;
    this.target = target;
    this.seedTargets = seedTargets;
    this.suppliedSeedConfigMap = Optional.empty();
    this.appModuleDependencies = Optional.empty();
    this.blacklistedModules = Optional.empty();
    this.modulesWithResources = ImmutableSet.of();
    this.modulesWithManifest = ImmutableSet.of();
  }

  public ImmutableSortedMap<APKModule, ImmutableSortedSet<APKModule>> toOutgoingEdgesMap() {
    return getAPKModules().stream()
        .collect(
            ImmutableSortedMap.toImmutableSortedMap(
                Ordering.natural(),
                module -> module,
                module -> ImmutableSortedSet.copyOf(getGraph().getOutgoingNodesFor(module))));
  }

  /**
   * Utility method for flattening a list of queries into the a list of the build targets they
   * resolve to.
   *
   * @param queries list of queries, they are expected to have already been resolved
   * @return list of build targets queries resolve to joined together
   */
  public static Optional<List<BuildTarget>> extractTargetsFromQueries(
      Optional<List<Query>> queries) {
    if (!queries.isPresent()) {
      return Optional.empty();
    }

    ImmutableList<BuildTarget> targets =
        queries.get().stream()
            .map(query -> Optional.ofNullable(query.getResolvedQuery()))
            .filter(resolution -> resolution.isPresent())
            .flatMap(resolution -> resolution.get().stream())
            .collect(ImmutableList.toImmutableList());

    return Optional.of(targets);
  }

  private Optional<ImmutableMap<String, ImmutableList<BuildTarget>>> generateSeedConfigMap() {
    if (suppliedSeedConfigMap.isPresent()) {
      return suppliedSeedConfigMap;
    }
    if (!seedTargets.isPresent()) {
      return Optional.empty();
    }
    HashMap<String, ImmutableList<BuildTarget>> seedConfigMapMutable = new HashMap<>();
    for (BuildTarget seedTarget : seedTargets.get()) {
      String moduleName = generateNameFromTarget(seedTarget);
      seedConfigMapMutable.put(moduleName, ImmutableList.of(seedTarget));
    }
    ImmutableMap<String, ImmutableList<BuildTarget>> seedConfigMapImmutable =
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
   * Lazy generate the declared dependency graph.
   *
   * @return the DAG representing the declared dependency relationship of declared app module
   *     configurations.
   */
  private DirectedAcyclicGraph<String> getDeclaredDependencyGraph() {
    return declaredDependencyGraphSupplier.get();
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

  public Optional<ImmutableMap<String, ImmutableList<BuildTarget>>> getSeedConfigMap() {
    verifyNoSharedSeeds();
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
   * Get the Module that should contain the resources for the given target
   *
   * @param target target to serach for in modules
   * @return the module that contains the target
   */
  public APKModule findResourceModuleForTarget(BuildTarget target) {
    APKModule module = targetToModuleMapSupplier.get().get(target);
    return (module == null || !module.hasResources()) ? rootAPKModuleSupplier.get() : module;
  }

  /**
   * Get the Module that should contain the manifest for the given target or else fallback to the same logic of findResourceModuleForTarget.
   *
   * @param target target to search for in modules
   * @return the module that contains the target
   */
  public APKModule findManifestModuleForTarget(BuildTarget target) {
    APKModule module = targetToModuleMapSupplier.get().get(target);
    return (module == null || !module.hasManifest()) ? findResourceModuleForTarget(target) : module;
  }


  /**
   * Group the classes in the input jars into a multimap based on the APKModule they belong to
   *
   * @param apkModuleToJarPathMap the mapping of APKModules to the path for the jar files
   * @param translatorFunction function used to translate the class names to obfuscated names
   * @param filesystem filesystem representation for resolving paths
   * @return The mapping of APKModules to the class names they contain
   * @throws IOException
   */
  public static ImmutableMultimap<APKModule, String> getAPKModuleToClassesMap(
      ImmutableMultimap<APKModule, Path> apkModuleToJarPathMap,
      Function<String, String> translatorFunction,
      ProjectFilesystem filesystem)
      throws IOException {
    ImmutableMultimap.Builder<APKModule, String> builder = ImmutableSetMultimap.builder();
    if (!apkModuleToJarPathMap.isEmpty()) {
      for (APKModule dexStore : apkModuleToJarPathMap.keySet()) {
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

                  if (translatorFunction.apply(classpath) != null) {
                    builder.put(dexStore, translatorFunction.apply(classpath));
                  }
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
    MutableDirectedGraph<APKModule> apkModuleGraph = new MutableDirectedGraph<>();

    apkModuleGraph.addNode(rootAPKModuleSupplier.get());

    if (getSeedConfigMap().isPresent()) {
      Multimap<BuildTarget, String> targetToContainingApkModulesMap =
          mapTargetsToContainingModules();
      generateSharedModules(apkModuleGraph, targetToContainingApkModulesMap);
      // add declared dependencies as well.
      Map<String, APKModule> nameToAPKModules = new HashMap<>();
      for (APKModule node : apkModuleGraph.getNodes()) {
        nameToAPKModules.put(node.getName(), node);
      }
      DirectedAcyclicGraph<String> declaredDependencies = getDeclaredDependencyGraph();
      for (String source : declaredDependencies.getNodes()) {
        for (String sink : declaredDependencies.getOutgoingNodesFor(source)) {
          apkModuleGraph.addEdge(nameToAPKModules.get(source), nameToAPKModules.get(sink));
        }
      }
    }

    return new DirectedAcyclicGraph<>(apkModuleGraph);
  }

  private DirectedAcyclicGraph<String> generateDeclaredDependencyGraph() {
    MutableDirectedGraph<String> declaredDependencyGraph = new MutableDirectedGraph<>();

    if (appModuleDependencies.isPresent()) {
      for (Map.Entry<String, ImmutableList<String>> moduleDependencies :
          appModuleDependencies.get().entrySet()) {
        for (String moduleDep : moduleDependencies.getValue()) {
          declaredDependencyGraph.addEdge(moduleDependencies.getKey(), moduleDep);
        }
      }
    }

    DirectedAcyclicGraph<String> result = new DirectedAcyclicGraph<>(declaredDependencyGraph);
    verifyNoUnrecognizedModulesInDependencyGraph(result);
    return result;
  }

  private void verifyNoUnrecognizedModulesInDependencyGraph(
      DirectedAcyclicGraph<String> dependencyGraph) {
    Set<String> configModules =
        getSeedConfigMap().isPresent() ? getSeedConfigMap().get().keySet() : new HashSet<>();
    Set<String> unrecognizedModules = new HashSet<>(dependencyGraph.getNodes());
    unrecognizedModules.removeAll(configModules);
    if (!unrecognizedModules.isEmpty()) {
      StringBuilder errorString =
          new StringBuilder("Unrecognized App Modules in Dependency Graph: ");
      for (String module : unrecognizedModules) {
        errorString.append(module).append(" ");
      }
      throw new IllegalStateException(errorString.toString());
    }
  }

  /**
   * This walks through the target graph starting from the root target and adds all reachable
   * targets that are not seed targets to the root module
   *
   * @return The root APK Module
   */
  private APKModule generateRootModule() {
    Set<BuildTarget> rootTargets = new HashSet<>();
    if (targetGraph != TargetGraph.EMPTY) {
      Set<TargetNode<?>> rootNodes = new HashSet<>();
      rootNodes.add(targetGraph.get(target));
      if (blacklistedModules.isPresent()) {
        for (BuildTarget targetModule : blacklistedModules.get()) {
          rootNodes.add(targetGraph.get(targetModule));
          rootTargets.add(targetModule);
        }
      }
      new AbstractBreadthFirstTraversal<TargetNode<?>>(rootNodes) {
        @Override
        public Iterable<TargetNode<?>> visit(TargetNode<?> node) {

          ImmutableSet.Builder<TargetNode<?>> depsBuilder = ImmutableSet.builder();
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
    APKModule rootModule = APKModule.of(ROOT_APKMODULE_NAME, true, true);
    buildTargetsMap.put(rootModule, ImmutableSet.copyOf(rootTargets));
    return rootModule;
  }

  /**
   * For each seed target, find its reachable targets and mark them in a multimap as being reachable
   * by that module for later sorting into exclusive and shared targets
   *
   * @return the Multimap containing targets and the seed modules that contain them
   */
  private Multimap<BuildTarget, String> mapTargetsToContainingModules() {
    Multimap<BuildTarget, String> targetToContainingApkModuleNameMap =
        MultimapBuilder.treeKeys().treeSetValues().build();
    for (Map.Entry<String, ImmutableList<BuildTarget>> seedConfig :
        getSeedConfigMap().get().entrySet()) {
      String seedModuleName = seedConfig.getKey();
      for (BuildTarget seedTarget : seedConfig.getValue()) {
        targetToContainingApkModuleNameMap.put(seedTarget, seedModuleName);
        new AbstractBreadthFirstTraversal<TargetNode<?>>(targetGraph.get(seedTarget)) {
          @Override
          public ImmutableSet<TargetNode<?>> visit(TargetNode<?> node) {

            ImmutableSet.Builder<TargetNode<?>> depsBuilder = ImmutableSet.builder();
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
    // Now to generate the minimal covers of APKModules for each set of APKModules that contain
    // a buildTarget
    DirectedAcyclicGraph<String> declaredDependencies = getDeclaredDependencyGraph();
    Multimap<BuildTarget, String> targetModuleEntriesToRemove =
        MultimapBuilder.treeKeys().treeSetValues().build();
    for (BuildTarget key : targetToContainingApkModuleNameMap.keySet()) {
      Collection<String> modulesForTarget = targetToContainingApkModuleNameMap.get(key);
      new AbstractBreadthFirstTraversal<String>(modulesForTarget) {
        @Override
        public Iterable<String> visit(String moduleName) throws RuntimeException {
          Collection<String> dependentModules =
              declaredDependencies.getIncomingNodesFor(moduleName);
          for (String dependent : dependentModules) {
            if (modulesForTarget.contains(dependent)) {
              targetModuleEntriesToRemove.put(key, dependent);
            }
          }
          return dependentModules;
        }
      }.start();
    }
    for (Map.Entry<BuildTarget, String> entryToRemove : targetModuleEntriesToRemove.entries()) {
      targetToContainingApkModuleNameMap.remove(entryToRemove.getKey(), entryToRemove.getValue());
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
      Multimap<BuildTarget, String> targetToContainingApkModulesMap) {

    // Sort the module-covers of all targets to determine shared module names.
    TreeSet<TreeSet<String>> sortedContainingModuleSets =
        new TreeSet<>(
            new Comparator<TreeSet<String>>() {
              @Override
              public int compare(TreeSet<String> left, TreeSet<String> right) {
                int sizeDiff = left.size() - right.size();
                if (sizeDiff != 0) {
                  return sizeDiff;
                }
                Iterator<String> leftIter = left.iterator();
                Iterator<String> rightIter = right.iterator();
                while (leftIter.hasNext()) {
                  String leftElement = leftIter.next();
                  String rightElement = rightIter.next();
                  int stringComparison = leftElement.compareTo(rightElement);
                  if (stringComparison != 0) {
                    return stringComparison;
                  }
                }
                return 0;
              }
            });
    for (Map.Entry<BuildTarget, Collection<String>> entry :
        targetToContainingApkModulesMap.asMap().entrySet()) {
      TreeSet<String> containingModuleSet = new TreeSet<>(entry.getValue());
      sortedContainingModuleSets.add(containingModuleSet);
    }

    // build modules based on all entries.
    Map<ImmutableSet<String>, APKModule> combinedModuleHashToModuleMap = new HashMap<>();
    int currentId = 0;
    for (TreeSet<String> moduleCover : sortedContainingModuleSets) {
      String moduleName =
          moduleCover.size() == 1 ? moduleCover.iterator().next() : "shared" + currentId++;
      APKModule module = APKModule.of(moduleName, modulesWithResources.contains(moduleName), modulesWithManifest.contains(moduleName));
      combinedModuleHashToModuleMap.put(ImmutableSet.copyOf(moduleCover), module);
    }

    // add Targets per module;
    for (Map.Entry<BuildTarget, Collection<String>> entry :
        targetToContainingApkModulesMap.asMap().entrySet()) {
      ImmutableSet<String> containingModuleSet = ImmutableSet.copyOf(entry.getValue());
      for (Map.Entry<ImmutableSet<String>, APKModule> existingEntry :
          combinedModuleHashToModuleMap.entrySet()) {
        if (existingEntry.getKey().equals(containingModuleSet)) {
          getBuildTargets(existingEntry.getValue()).add(entry.getKey());
          break;
        }
      }
    }

    // Find the seed modules and add them to the graph
    Map<String, APKModule> seedModules = new HashMap<>();
    for (Map.Entry<ImmutableSet<String>, APKModule> entry :
        combinedModuleHashToModuleMap.entrySet()) {
      if (entry.getKey().size() == 1) {
        APKModule seed = entry.getValue();
        apkModuleGraph.addNode(seed);
        seedModules.put(entry.getKey().iterator().next(), seed);
        apkModuleGraph.addEdge(seed, rootAPKModuleSupplier.get());
      }
    }

    // Find the shared modules and add them to the graph
    for (Map.Entry<ImmutableSet<String>, APKModule> entry :
        combinedModuleHashToModuleMap.entrySet()) {
      if (entry.getKey().size() > 1) {
        APKModule shared = entry.getValue();
        apkModuleGraph.addNode(shared);
        apkModuleGraph.addEdge(shared, rootAPKModuleSupplier.get());
        for (String seedName : entry.getKey()) {
          apkModuleGraph.addEdge(seedModules.get(seedName), shared);
        }
      }
    }
  }

  private boolean isInRootModule(BuildTarget depTarget) {
    return getBuildTargets(rootAPKModuleSupplier.get()).contains(depTarget);
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
    String name =
        androidModuleTarget
            .getCellRelativeBasePath()
            .getPath()
            .toString()
            .replaceAll(replacementPattern, ".");
    if (name.endsWith(shortName)) {
      // return just the base path, ignoring the target name that is the same as its parent
      return name;
    } else {
      return name.isEmpty() ? shortName : name + "." + shortName;
    }
  }

  private void verifyNoSharedSeeds() {
    ImmutableMultimap<BuildTarget, String> sharedSeeds = sharedSeedsSupplier.get();
    if (!sharedSeeds.isEmpty()) {
      StringBuilder errorMessage = new StringBuilder();
      for (BuildTarget seed : sharedSeeds.keySet()) {
        errorMessage
            .append("BuildTarget: ")
            .append(seed)
            .append(" is used as seed in multiple modules: ");
        for (String module : sharedSeeds.get(seed)) {
          errorMessage.append(module).append(' ');
        }
        errorMessage.append('\n');
      }
      throw new IllegalArgumentException(errorMessage.toString());
    }
  }

  private ImmutableMultimap<BuildTarget, String> generateSharedSeeds() {
    Optional<ImmutableMap<String, ImmutableList<BuildTarget>>> seedConfigMap =
        configMapSupplier.get();
    HashMultimap<BuildTarget, String> sharedSeedMapBuilder = HashMultimap.create();
    if (!seedConfigMap.isPresent()) {
      return ImmutableMultimap.copyOf(sharedSeedMapBuilder);
    }
    // first: invert the seedConfigMap to get BuildTarget -> Seeds
    for (Map.Entry<String, ImmutableList<BuildTarget>> entry : seedConfigMap.get().entrySet()) {
      for (BuildTarget buildTarget : entry.getValue()) {
        sharedSeedMapBuilder.put(buildTarget, entry.getKey());
      }
    }
    // second: remove keys that have only one value.
    Set<BuildTarget> nonSharedSeeds = new HashSet<>();
    for (BuildTarget buildTarget : sharedSeedMapBuilder.keySet()) {
      if (sharedSeedMapBuilder.get(buildTarget).size() <= 1) {
        nonSharedSeeds.add(buildTarget);
      }
    }
    for (BuildTarget targetToRemove : nonSharedSeeds) {
      sharedSeedMapBuilder.removeAll(targetToRemove);
    }
    return ImmutableMultimap.copyOf(sharedSeedMapBuilder);
  }

  public Set<BuildTarget> getBuildTargets(APKModule module) {
    return buildTargetsMap.computeIfAbsent(module, (m) -> new HashSet<>());
  }
}
