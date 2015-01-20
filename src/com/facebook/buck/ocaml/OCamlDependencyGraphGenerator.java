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

package com.facebook.buck.ocaml;

import com.facebook.buck.graph.DefaultDirectedAcyclicGraph;
import com.facebook.buck.graph.DirectedAcyclicGraph;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Paths;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Parse output of ocamldep tool and build dependency graph of ocaml source files (*.ml & *.mli)
 */
public class OCamlDependencyGraphGenerator {
  private static final String OCAML_SOURCE_AND_DEPS_SEPARATOR = ":";
  private static final String OCAML_DEPS_SEPARATOR = " ";
  private static final String LINE_SEPARATOR = System.getProperty("line.separator");

  @Nullable
  private MutableDirectedGraph<String> graph;

  public DirectedAcyclicGraph<String> getGraph() {
    Preconditions.checkNotNull(graph);
    return new DefaultDirectedAcyclicGraph<>(graph);
  }

  public ImmutableList<String> generate(String depToolOutput) {
    parseDependencies(depToolOutput);
    Preconditions.checkNotNull(graph);
    final ImmutableList<String> sortedDeps = TopologicalSort.sort(
        graph, Predicates.<String>alwaysTrue());

    return FluentIterable.from(sortedDeps).transform(
        new Function<String, String>() {
          @Override
          public String apply(String input) {
            return input.replaceAll(
                OCamlCompilables.OCAML_CMX_REGEX, OCamlCompilables.OCAML_ML).replaceAll(
                OCamlCompilables.OCAML_CMI_REGEX, OCamlCompilables.OCAML_MLI);
          }
        }).toList();
  }

  private String replaceObjExtWithSourceExt(String name) {
    return name.replaceAll(
          OCamlCompilables.OCAML_CMX_REGEX,
          OCamlCompilables.OCAML_ML)
        .replaceAll(
            OCamlCompilables.OCAML_CMI_REGEX,
            OCamlCompilables.OCAML_MLI);
  }
  public ImmutableMap<SourcePath, ImmutableList<SourcePath>> generateDependencyMap(
      String depString) {
    ImmutableMap.Builder<SourcePath, ImmutableList<SourcePath>> mapBuilder = ImmutableMap.builder();
    Iterable<String> lines = Splitter.on(LINE_SEPARATOR).split(depString);
    for (String line : lines) {
      List<String> sourceAndDeps = Splitter.on(OCAML_SOURCE_AND_DEPS_SEPARATOR)
          .trimResults().splitToList(line);
      if (sourceAndDeps.size() >= 1) {
        String source = replaceObjExtWithSourceExt(sourceAndDeps.get(0));
        if (source.endsWith(OCamlCompilables.OCAML_ML) ||
            source.endsWith(OCamlCompilables.OCAML_MLI)) {
          FluentIterable<SourcePath> dependencies = FluentIterable
              .from(
                Splitter.on(OCAML_DEPS_SEPARATOR)
                  .trimResults().splitToList(sourceAndDeps.get(1)))
              .filter(new Predicate<String>() {
                        @Override
                        public boolean apply(String input) {
                          return !input.isEmpty();
                        }
                      })
              .transform(
                  new Function<String, SourcePath>() {
                    @Override
                    public SourcePath apply(String input) {
                      return new PathSourcePath(Paths.get(replaceObjExtWithSourceExt(input)));
                    }
                  });
          mapBuilder.put(
              new PathSourcePath(Paths.get(source)),
              ImmutableList.copyOf(dependencies));
        }
      }
    }
    return mapBuilder.build();
  }

  private void parseDependencies(String stdout) {
    graph = new MutableDirectedGraph<>();
    Iterable<String> lines = Splitter.on(LINE_SEPARATOR).split(stdout);

    for (String line : lines) {
      List<String> sourceAndDeps = Splitter.on(OCAML_SOURCE_AND_DEPS_SEPARATOR)
          .trimResults().splitToList(line);
      if (sourceAndDeps.size() >= 1) {
        String source = sourceAndDeps.get(0);
        if (source.endsWith(OCamlCompilables.OCAML_CMX) || source.endsWith(
            OCamlCompilables.OCAML_CMI)) {
          addSourceDeps(sourceAndDeps, source);
        }
      }
    }
  }

  private void addSourceDeps(List<String> sourceAndDeps, String source) {
    Preconditions.checkNotNull(graph);
    graph.addNode(source);
    if (sourceAndDeps.size() >= 2) {
      String deps = sourceAndDeps.get(1);
      if (!deps.isEmpty()) {
        List<String> dependencies = Splitter.on(OCAML_DEPS_SEPARATOR)
            .trimResults().splitToList(deps);
        for (String dep : dependencies) {
          if (!dep.isEmpty()) {
            graph.addEdge(source, dep);
          }
        }
      }
    }
  }
}
