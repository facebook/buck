/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaFileParser;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.autodeps.JavaDepsFinder;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.facebook.buck.util.Console;
import com.google.common.base.Joiner;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Tool that implements the bulk of the work for {@code buck suggest}. For a given build target in
 * the target graph, it will divide its {@code srcs} into strongly connected components and use
 * those to suggest a new set of build rule definitions with maximally fine-grained dependencies.
 *
 * <p>Note that because this is a tool that is trying to provide information about the user's
 * dependencies, it generally favors printing errors to stderr rather than throwing exceptions and
 * halting. As a tool, it would be less useful if it did not provide any information until the user
 * cleaned up all of his or her code. The user is likely running {@code buck suggest} to enable them
 * to clean things up.
 */
class FineGrainedJavaDependencySuggester {

  private final BuildTarget suggestedTarget;
  private final TargetGraph graph;
  private final JavaDepsFinder javaDepsFinder;
  private final Console console;

  FineGrainedJavaDependencySuggester(
      BuildTarget suggestedTarget,
      TargetGraph graph,
      JavaDepsFinder javaDepsFinder,
      Console console) {
    this.suggestedTarget = suggestedTarget;
    this.graph = graph;
    this.javaDepsFinder = javaDepsFinder;
    this.console = console;
  }

  /**
   * Suggests a refactoring by printing it to stdout (with warnings printed to stderr).
   *
   * @throws IllegalArgumentException
   */
  void suggestRefactoring() {
    TargetNode<?> suggestedNode = graph.get(suggestedTarget);
    if (!(suggestedNode.getConstructorArg() instanceof JavaLibraryDescription.CoreArg)) {
      console.printErrorText(
          String.format("'%s' does not correspond to a Java rule", suggestedTarget));
      throw new IllegalArgumentException();
    }

    JavaLibraryDescription.CoreArg arg =
        (JavaLibraryDescription.CoreArg) suggestedNode.getConstructorArg();
    JavaFileParser javaFileParser = javaDepsFinder.getJavaFileParser();
    Multimap<String, String> providedSymbolToRequiredSymbols = HashMultimap.create();
    Map<String, PathSourcePath> providedSymbolToSrc = new HashMap<>();
    for (SourcePath src : arg.getSrcs()) {
      extractProvidedSymbolInfoFromSourceFile(
          src, javaFileParser, providedSymbolToRequiredSymbols, providedSymbolToSrc);
    }

    // Create a MutableDirectedGraph from the providedSymbolToRequiredSymbols.
    MutableDirectedGraph<String> symbolsDependencies = new MutableDirectedGraph<>();
    // Iterate the keys of providedSymbolToSrc rather than providedSymbolToRequiredSymbols because
    // providedSymbolToRequiredSymbols will not have any entries for providedSymbols with no
    // dependencies.
    for (String providedSymbol : providedSymbolToSrc.keySet()) {
      // Add a node for the providedSymbol in case it has no edges.
      symbolsDependencies.addNode(providedSymbol);
      for (String requiredSymbol : providedSymbolToRequiredSymbols.get(providedSymbol)) {
        if (providedSymbolToRequiredSymbols.containsKey(requiredSymbol)
            && !providedSymbol.equals(requiredSymbol)) {
          symbolsDependencies.addEdge(providedSymbol, requiredSymbol);
        }
      }
    }

    // Determine the strongly connected components.
    Set<Set<String>> stronglyConnectedComponents =
        symbolsDependencies.findStronglyConnectedComponents();
    // Maps a providedSymbol to the component that contains it.
    Map<String, NamedStronglyConnectedComponent> namedComponentsIndex = new TreeMap<>();
    Set<NamedStronglyConnectedComponent> namedComponents = new TreeSet<>();
    for (Set<String> stronglyConnectedComponent : stronglyConnectedComponents) {
      // We just use the first provided symbol in the strongly connected component as the canonical
      // name for the component. Maybe not the best name, but certainly not the worst.
      String name = Iterables.getFirst(stronglyConnectedComponent, /* defaultValue */ null);
      if (name == null) {
        throw new IllegalStateException(
            "A strongly connected component was created with zero nodes.");
      }

      NamedStronglyConnectedComponent namedComponent =
          new NamedStronglyConnectedComponent(name, stronglyConnectedComponent);
      namedComponents.add(namedComponent);
      for (String providedSymbol : stronglyConnectedComponent) {
        namedComponentsIndex.put(providedSymbol, namedComponent);
      }
    }

    // Visibility argument.
    StringBuilder visibilityBuilder = new StringBuilder("  visibility = [\n");
    SortedSet<String> visibilities =
        FluentIterable.from(suggestedNode.getVisibilityPatterns())
            .transform(VisibilityPattern::getRepresentation)
            .toSortedSet(Ordering.natural());
    for (String visibility : visibilities) {
      visibilityBuilder.append("    '").append(visibility).append("',\n");
    }
    visibilityBuilder.append("  ],\n");
    String visibilityArg = visibilityBuilder.toString();

    // Print out the new version of the original rule.
    console
        .getStdOut()
        .printf(
            "java_library(\n" + "  name = '%s',\n" + "  exported_deps = [\n",
            suggestedTarget.getShortName());
    for (NamedStronglyConnectedComponent namedComponent : namedComponents) {
      console.getStdOut().printf("    ':%s',\n", namedComponent.name);
    }
    console.getStdOut().print("  ],\n" + visibilityArg + ")\n");

    // Print out a rule for each of the strongly connected components.
    JavaDepsFinder.DependencyInfo dependencyInfo = javaDepsFinder.findDependencyInfoForGraph(graph);
    for (NamedStronglyConnectedComponent namedComponent : namedComponents) {
      String buildRuleDefinition =
          createBuildRuleDefinition(
              namedComponent,
              providedSymbolToSrc,
              providedSymbolToRequiredSymbols,
              namedComponentsIndex,
              dependencyInfo,
              symbolsDependencies,
              visibilityArg);
      console.getStdOut().print(buildRuleDefinition);
    }
  }

  /** Extracts the features from {@code src} and updates the collections accordingly. */
  private void extractProvidedSymbolInfoFromSourceFile(
      SourcePath src,
      JavaFileParser javaFileParser,
      Multimap<String, String> providedSymbolToRequiredSymbols,
      Map<String, PathSourcePath> providedSymbolToSrc) {
    if (!(src instanceof PathSourcePath)) {
      return;
    }

    PathSourcePath path = (PathSourcePath) src;
    ProjectFilesystem filesystem = path.getFilesystem();
    Optional<String> contents = filesystem.readFileIfItExists(path.getRelativePath());
    if (!contents.isPresent()) {
      throw new RuntimeException(String.format("Could not read file '%s'", path.getRelativePath()));
    }

    JavaFileParser.JavaFileFeatures features =
        javaFileParser.extractFeaturesFromJavaCode(contents.get());
    // If there are multiple provided symbols, that is because there are inner classes. Choosing
    // the shortest name will effectively select the top-level type.
    String providedSymbol = Iterables.getFirst(features.providedSymbols, /* defaultValue */ null);
    if (providedSymbol == null) {
      console
          .getStdErr()
          .printf("%s cowardly refuses to provide any types.\n", path.getRelativePath());
      return;
    }

    providedSymbolToSrc.put(providedSymbol, path);
    providedSymbolToRequiredSymbols.putAll(providedSymbol, features.requiredSymbols);
    providedSymbolToRequiredSymbols.putAll(providedSymbol, features.exportedSymbols);
  }

  /** Creates the build rule definition for the {@code namedComponent}. */
  private String createBuildRuleDefinition(
      NamedStronglyConnectedComponent namedComponent,
      Map<String, PathSourcePath> providedSymbolToSrc,
      Multimap<String, String> providedSymbolToRequiredSymbols,
      Map<String, NamedStronglyConnectedComponent> namedComponentsIndex,
      JavaDepsFinder.DependencyInfo dependencyInfo,
      MutableDirectedGraph<String> symbolsDependencies,
      String visibilityArg) {
    TargetNode<?> suggestedNode = graph.get(suggestedTarget);
    SortedSet<String> deps = new TreeSet<>(LOCAL_DEPS_FIRST_COMPARATOR);
    SortedSet<PathSourcePath> srcs = new TreeSet<>();
    for (String providedSymbol : namedComponent.symbols) {
      PathSourcePath src = providedSymbolToSrc.get(providedSymbol);
      srcs.add(src);

      for (String requiredSymbol : providedSymbolToRequiredSymbols.get(providedSymbol)) {
        // First, check to see whether the requiredSymbol is in one of the newly created
        // strongly connected components. If so, add it to the deps so long as it is not the
        // strongly connected component that we are currently exploring.
        NamedStronglyConnectedComponent requiredComponent =
            namedComponentsIndex.get(requiredSymbol);
        if (requiredComponent != null) {
          if (!requiredComponent.equals(namedComponent)) {
            deps.add(":" + requiredComponent.name);
          }
          continue;
        }

        Set<TargetNode<?>> depProviders = dependencyInfo.symbolToProviders.get(requiredSymbol);
        if (depProviders == null || depProviders.size() == 0) {
          console.getStdErr().printf("# Suspicious: no provider for '%s'\n", requiredSymbol);
          continue;
        }

        depProviders =
            FluentIterable.from(depProviders)
                .filter(provider -> provider.isVisibleTo(suggestedNode))
                .toSet();
        TargetNode<?> depProvider;
        if (depProviders.size() == 1) {
          depProvider = Iterables.getOnlyElement(depProviders);
        } else {
          console
              .getStdErr()
              .printf(
                  "# Suspicious: no lone provider for '%s': [%s]\n",
                  requiredSymbol, Joiner.on(", ").join(depProviders));
          continue;
        }

        if (!depProvider.equals(suggestedNode)) {
          deps.add(depProvider.toString());
        }
      }

      // Find deps within package.
      for (String requiredSymbol : symbolsDependencies.getOutgoingNodesFor(providedSymbol)) {
        NamedStronglyConnectedComponent componentDep =
            Objects.requireNonNull(namedComponentsIndex.get(requiredSymbol));
        if (!componentDep.equals(namedComponent)) {
          deps.add(":" + componentDep.name);
        }
      }
    }

    Path basePathForSuggestedTarget = suggestedTarget.getBasePath();
    Iterable<String> relativeSrcs =
        FluentIterable.from(srcs)
            .transform(
                input -> basePathForSuggestedTarget.relativize(input.getRelativePath()).toString());

    StringBuilder rule =
        new StringBuilder(
            "\njava_library(\n" + "  name = '" + namedComponent.name + "',\n" + "  srcs = [\n");
    for (String src : relativeSrcs) {
      rule.append(String.format("    '%s',\n", src));
    }
    rule.append("  ],\n" + "  deps = [\n");
    for (String dep : deps) {
      rule.append(String.format("    '%s',\n", dep));
    }
    rule.append("  ],\n").append(visibilityArg).append(")\n");
    return rule.toString();
  }

  /**
   * A strongly connected component is going to become a java_library() rule. These components have
   * dependencies on one another, so it's important to be able to determine their names so they can
   * be listed in the deps.
   */
  private static class NamedStronglyConnectedComponent
      implements Comparable<NamedStronglyConnectedComponent> {
    private final String name;
    private final Set<String> symbols;

    NamedStronglyConnectedComponent(String name, Set<String> symbols) {
      this.name = name;
      this.symbols = symbols;
    }

    @Override
    public int compareTo(NamedStronglyConnectedComponent that) {
      return this.name.compareTo(that.name);
    }
  }

  private static final Comparator<String> LOCAL_DEPS_FIRST_COMPARATOR =
      (dep1, dep2) -> {
        boolean isDep1Local = dep1.startsWith(":");
        boolean isDep2Local = dep2.startsWith(":");
        if (isDep1Local == isDep2Local) {
          return dep1.compareTo(dep2);
        } else if (isDep1Local) {
          return -1;
        } else {
          return 1;
        }
      };
}
