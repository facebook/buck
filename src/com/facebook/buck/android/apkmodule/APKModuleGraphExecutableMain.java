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

import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
                    .collect(ImmutableList.toImmutableList()));

    APKModuleGraph<ExternalTargetGraph.ExternalBuildTarget> apkModuleGraph =
        new APKModuleGraph<>(
            targetGraph,
            targetGraph.getBuildTarget(rootTarget),
            Optional.of(seedConfigMap),
            Optional.of(appModuleDependenciesMap),
            alwaysInMainApkSeeds);

    List<String> metadataLines =
        APKModuleMetadataUtil.getMetadataLines(
            apkModuleGraph,
            ExternalTargetGraph.ExternalBuildTarget::getName,
            Optional.empty(),
            Optional.empty());
    Files.write(Paths.get(outputPath), metadataLines);

    System.exit(0);
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
}
