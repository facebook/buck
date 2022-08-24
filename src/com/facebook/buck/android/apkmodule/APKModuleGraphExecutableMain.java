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

package com.facebook.buck.android.apkmodule;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Ordering;
import com.google.common.collect.TreeMultimap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.function.Function;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

/** Main entry point for constructing an {@link APKModuleGraph} from an external graph. */
public class APKModuleGraphExecutableMain {

  @Option(name = "--root-target", required = true)
  private String rootTarget;

  @Option(name = "--target-graph", required = true)
  private String targetGraphPath;

  @Option(name = "--seed-config-map", required = true)
  private String seedConfigMapPath;

  @Option(name = "--app-module-dependencies-map", required = true)
  private String appModuleDependenciesPath;

  @Option(name = "--output", required = true)
  private String outputPath;

  @Option(name = "--always-in-main-apk-seeds")
  private String alwaysInMainApkSeedsPath;

  @Option(name = "--targets-to-jars")
  private String targetsToJarsPath;

  @Option(name = "--targets-to-so-names")
  private String targetsToSoNamesPath;

  @Option(name = "--output-module-info-and-target-to-module-only")
  private boolean outputModuleInfoAndTargetToModuleOnly;

  public static void main(String[] args) throws IOException {
    APKModuleGraphExecutableMain main = new APKModuleGraphExecutableMain();
    CmdLineParser parser = new CmdLineParser(main);
    try {
      parser.parseArgument(args);
      main.run();
      System.exit(0);
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      parser.printUsage(System.err);
      System.exit(1);
    }
  }

  private void run() throws IOException {
    Map<String, ImmutableList<String>> rawTargetGraphMap =
        ObjectMappers.READER.readValue(
            ObjectMappers.createParser(Paths.get(targetGraphPath)),
            new TypeReference<Map<String, ImmutableList<String>>>() {});

    ExternalTargetGraph targetGraph = buildTargetGraph(rawTargetGraphMap);

    Map<String, ImmutableList<String>> rawSeedConfigMap =
        ObjectMappers.READER.readValue(
            ObjectMappers.createParser(Paths.get(seedConfigMapPath)),
            new TypeReference<Map<String, ImmutableList<String>>>() {});
    ImmutableMap<String, ImmutableList<ExternalTargetGraph.ExternalBuildTarget>> seedConfigMap =
        rawSeedConfigMap.entrySet().stream()
            .collect(
                ImmutableMap.toImmutableMap(
                    Map.Entry::getKey,
                    e ->
                        e.getValue().stream()
                            .map(targetGraph::getBuildTarget)
                            .collect(ImmutableList.toImmutableList())));

    ImmutableMap<String, ImmutableList<String>> appModuleDependenciesMap =
        ObjectMappers.READER.readValue(
            ObjectMappers.createParser(Paths.get(appModuleDependenciesPath)),
            new TypeReference<ImmutableMap<String, ImmutableList<String>>>() {});

    Optional<List<ExternalTargetGraph.ExternalBuildTarget>> alwaysInMainApkSeeds =
        alwaysInMainApkSeedsPath == null
            ? Optional.empty()
            : Optional.of(
                Files.readAllLines(Paths.get(alwaysInMainApkSeedsPath)).stream()
                    .map(targetGraph::getBuildTarget)
                    .filter(Objects::nonNull)
                    .collect(ImmutableList.toImmutableList()));

    APKModuleGraph<ExternalTargetGraph.ExternalBuildTarget> apkModuleGraph =
        new APKModuleGraph<>(
            targetGraph,
            targetGraph.getBuildTarget(rootTarget),
            Optional.of(seedConfigMap),
            Optional.of(appModuleDependenciesMap),
            alwaysInMainApkSeeds);

    if (outputModuleInfoAndTargetToModuleOnly) {
      Files.write(
          Paths.get(outputPath), getModuleInfoAndTargetToModuleMappingLines(apkModuleGraph));
      return;
    }

    Optional<ImmutableMultimap<APKModule, String>> apkModuleToClassesMap = Optional.empty();
    if (targetsToJarsPath != null) {
      ImmutableMultimap.Builder<APKModule, Path> builder = ImmutableSetMultimap.builder();
      for (String line : Files.readAllLines(Paths.get(targetsToJarsPath))) {
        String[] parts = line.split(" ");
        Preconditions.checkState(parts.length == 2);
        ExternalTargetGraph.ExternalBuildTarget target = targetGraph.getBuildTarget(parts[0]);
        APKModule module = apkModuleGraph.findModuleForTarget(target);
        builder.put(module, Paths.get(parts[1]));
      }

      apkModuleToClassesMap =
          Optional.of(
              APKModuleGraph.getAPKModuleToClassesMap(
                  builder.build(),
                  Function.identity(),
                  AbsPath.of(Paths.get(".").normalize().toAbsolutePath()),
                  ImmutableSet.of()));
    }

    Optional<ImmutableMultimap<APKModule, String>> apkModuleToNativeLibraryMap = Optional.empty();
    if (targetsToSoNamesPath != null) {
      ImmutableMultimap.Builder<APKModule, String> builder = ImmutableSetMultimap.builder();
      for (String line : Files.readAllLines(Paths.get(targetsToSoNamesPath))) {
        String[] parts = line.split(" ");
        // First part is the target string, second part is the so name, and third is whether it can
        // be an asset or not.
        Preconditions.checkState(parts.length == 3);
        ExternalTargetGraph.ExternalBuildTarget target = targetGraph.getBuildTarget(parts[0]);
        APKModule module = apkModuleGraph.findModuleForTarget(target);
        // We write out the .so's module if it is not in the root module, or if it can be an
        // asset.
        if (!module.isRootModule() || Boolean.parseBoolean(parts[2])) {
          builder.put(module, parts[1]);
        }
      }

      apkModuleToNativeLibraryMap = Optional.of(builder.build());
    }

    List<String> metadataLines =
        APKModuleMetadataUtil.getMetadataLines(
            apkModuleGraph,
            ExternalTargetGraph.ExternalBuildTarget::getName,
            apkModuleToClassesMap,
            apkModuleToNativeLibraryMap);

    Files.write(Paths.get(outputPath), metadataLines);
  }

  private ExternalTargetGraph buildTargetGraph(
      Map<String, ImmutableList<String>> rawTargetGraphMap) {
    ImmutableMap<String, ExternalTargetGraph.ExternalBuildTarget> buildTargetMap =
        rawTargetGraphMap.keySet().stream()
            .collect(
                ImmutableMap.toImmutableMap(
                    Function.identity(), ExternalTargetGraph.ExternalBuildTarget::new));

    ImmutableMap<ExternalTargetGraph.ExternalBuildTarget, ExternalTargetGraph.ExternalTargetNode>
        underlyingMap =
            rawTargetGraphMap.entrySet().stream()
                .collect(
                    ImmutableMap.toImmutableMap(
                        entry -> buildTargetMap.get(entry.getKey()),
                        entry ->
                            new ExternalTargetGraph.ExternalTargetNode(
                                buildTargetMap.get(entry.getKey()),
                                entry.getValue().stream()
                                    .map(buildTargetMap::get)
                                    .collect(ImmutableSet.toImmutableSet()))));

    return new ExternalTargetGraph(underlyingMap, buildTargetMap);
  }

  /**
   * API is: first line contains single integer N, the number of modules next N lines contain
   * 'module_name canary_class_name module_dep1 module_dep2 ... module_depN' all other lines contain
   * 'target module_containing_target'
   */
  private List<String> getModuleInfoAndTargetToModuleMappingLines(
      APKModuleGraph<ExternalTargetGraph.ExternalBuildTarget> apkModuleGraph) {
    // Module to module deps map is already sorted
    SortedMap<APKModule, ? extends SortedSet<APKModule>> moduleToDepsMap =
        apkModuleGraph.toOutgoingEdgesMap();
    LinkedList<String> metadataLines = new LinkedList<>();
    metadataLines.add(Integer.toString(moduleToDepsMap.size()));
    for (Map.Entry<APKModule, ? extends SortedSet<APKModule>> entry : moduleToDepsMap.entrySet()) {
      APKModule module = entry.getKey();
      ImmutableList<String> moduleDeps =
          entry.getValue().stream()
              .map(APKModule::getName)
              .collect(ImmutableList.toImmutableList());
      metadataLines.add(
          String.format(
              "%s %s %s",
              module.getName(), module.getCanaryClassName(), String.join(" ", moduleDeps)));
    }

    TreeMultimap<APKModule, String> orderedModuleToTargetsMap =
        TreeMultimap.create(Comparator.comparing(APKModule::getName), Ordering.natural());
    for (APKModule module : apkModuleGraph.getAPKModules()) {
      for (ExternalTargetGraph.ExternalBuildTarget target :
          apkModuleGraph.getBuildTargets(module)) {
        orderedModuleToTargetsMap.put(module, target.getName());
      }
    }
    for (APKModule module : orderedModuleToTargetsMap.keySet()) {
      String moduleName = module.getName();
      for (String target : orderedModuleToTargetsMap.get(module)) {
        metadataLines.add(String.format("%s %s", target, moduleName));
      }
    }

    return metadataLines;
  }
}
