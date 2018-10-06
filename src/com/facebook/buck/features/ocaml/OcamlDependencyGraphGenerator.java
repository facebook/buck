/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.features.ocaml;

import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.facebook.buck.core.util.graph.TopologicalSort;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Parse output of ocamldep tool and build dependency graph of ocaml source files (*.ml &amp; *.mli)
 */
public class OcamlDependencyGraphGenerator {
  private static final String OCAML_SOURCE_AND_DEPS_SEPARATOR = ":";
  private static final String OCAML_DEPS_SEPARATOR = " ";
  private static final String LINE_SEPARATOR =
      Objects.requireNonNull(System.getProperty("line.separator"));

  @Nullable private MutableDirectedGraph<String> graph;

  public ImmutableList<String> generate(String depToolOutput) {
    parseDependencies(depToolOutput);
    Objects.requireNonNull(graph);
    ImmutableList<String> sortedDeps = TopologicalSort.sort(graph);

    // Two copies of dependencies as .cmo can map to .ml or .re

    return Stream.concat(
            sortedDeps
                .stream()
                .map(input -> replaceObjExtWithSourceExt(input, false /* isReason */)),
            sortedDeps
                .stream()
                .map(input -> replaceObjExtWithSourceExt(input, true /* isReason */)))
        .collect(ImmutableList.toImmutableList());
  }

  private String replaceObjExtWithSourceExt(String name, boolean isReason) {
    return name.replaceAll(
            OcamlCompilables.OCAML_CMX_REGEX,
            isReason ? OcamlCompilables.OCAML_RE : OcamlCompilables.OCAML_ML)
        .replaceAll(
            OcamlCompilables.OCAML_CMI_REGEX,
            isReason ? OcamlCompilables.OCAML_REI : OcamlCompilables.OCAML_MLI);
  }

  public ImmutableMap<Path, ImmutableList<Path>> generateDependencyMap(String depString) {
    ImmutableMap.Builder<Path, ImmutableList<Path>> mapBuilder = ImmutableMap.builder();
    Iterable<String> lines = Splitter.on(LINE_SEPARATOR).split(depString);
    for (String line : lines) {
      List<String> sourceAndDeps =
          Splitter.on(OCAML_SOURCE_AND_DEPS_SEPARATOR).trimResults().splitToList(line);
      if (sourceAndDeps.size() >= 1) {
        String sourceML = replaceObjExtWithSourceExt(sourceAndDeps.get(0), /* isReason */ false);
        String sourceRE = replaceObjExtWithSourceExt(sourceAndDeps.get(0), /* isReason */ true);
        if (sourceML.endsWith(OcamlCompilables.OCAML_ML)
            || sourceML.endsWith(OcamlCompilables.OCAML_MLI)) {

          // Two copies of dependencies as .cmo can map to .ml or .re
          ImmutableList<Path> dependencies =
              Splitter.on(OCAML_DEPS_SEPARATOR)
                  .trimResults()
                  .splitToList(sourceAndDeps.get(1))
                  .stream()
                  .filter(input -> !input.isEmpty())
                  .flatMap(
                      input ->
                          Stream.of(
                              Paths.get(replaceObjExtWithSourceExt(input, /* isReason */ false)),
                              Paths.get(replaceObjExtWithSourceExt(input, /* isReason */ true))))
                  .collect(ImmutableList.toImmutableList());
          mapBuilder.put(Paths.get(sourceML), dependencies).put(Paths.get(sourceRE), dependencies);
        }
      }
    }
    return mapBuilder.build();
  }

  private void parseDependencies(String stdout) {
    graph = new MutableDirectedGraph<>();
    Iterable<String> lines = Splitter.on(LINE_SEPARATOR).split(stdout);

    for (String line : lines) {
      List<String> sourceAndDeps =
          Splitter.on(OCAML_SOURCE_AND_DEPS_SEPARATOR).trimResults().splitToList(line);
      if (sourceAndDeps.size() >= 1) {
        String source = sourceAndDeps.get(0);
        if (source.endsWith(OcamlCompilables.OCAML_CMX)
            || source.endsWith(OcamlCompilables.OCAML_CMI)) {
          addSourceDeps(sourceAndDeps, source);
        }
      }
    }
  }

  private void addSourceDeps(List<String> sourceAndDeps, String source) {
    Objects.requireNonNull(graph);
    graph.addNode(source);
    if (sourceAndDeps.size() >= 2) {
      String deps = sourceAndDeps.get(1);
      if (!deps.isEmpty()) {
        List<String> dependencies =
            Splitter.on(OCAML_DEPS_SEPARATOR).trimResults().splitToList(deps);
        for (String dep : dependencies) {
          if (!dep.isEmpty()) {
            graph.addEdge(source, dep);
          }
        }
      }
    }
  }
}
