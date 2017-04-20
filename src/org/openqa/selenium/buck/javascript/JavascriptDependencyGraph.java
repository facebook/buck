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

package org.openqa.selenium.buck.javascript;

import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

public class JavascriptDependencyGraph {

  private static final Path BASE_JS = Paths.get("third_party/closure/goog/base.js");
  private Set<JavascriptSource> sources = Sets.newHashSet();
  private Map<String, Path> depToPath = Maps.newHashMap();

  public void amendGraph(JavascriptSource source) {
    sources.add(source);
    for (String provide : source.getProvides()) {
      depToPath.put(provide, source.getPath());
    }
  }

  public void amendGraph(Iterable<JavascriptSource> allSources) {
    for (JavascriptSource source : allSources) {
      sources.add(source);
      for (String provide : source.getProvides()) {
        depToPath.put(provide, source.getPath());
      }
    }
  }

  public ImmutableSet<Path> sortSources() {
    MutableDirectedGraph<String> graph = new MutableDirectedGraph<>();

    for (JavascriptSource source : sources) {
      for (String provide : source.getProvides()) {
        graph.addNode(provide);

        for (String require : source.getRequires()) {
          graph.addNode(require);
          graph.addEdge(provide, require);
        }
      }
    }

    // Final step, topo sort the graph of deps and map back to files.
    ImmutableList<String> sorted = TopologicalSort.sort(graph);
    ImmutableSet.Builder<Path> builder = ImmutableSet.builder();
    builder.add(BASE_JS);

    for (String dep : sorted) {
      Path element = depToPath.get(dep);

      if (element == null) {
        throw new HumanReadableException("Missing dependency for: %s in", dep, sorted);
      }

      builder.add(element);
    }

    return builder.build();
  }

  @Override
  public String toString() {
    return sources.toString();
  }
}
