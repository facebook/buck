/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.graph.DirectedAcyclicGraph;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.jvm.core.SuggestBuildRules;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleDependencyVisitors;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Set;

final class DefaultSuggestBuildRules implements SuggestBuildRules {
  private final Supplier<ImmutableList<JavaLibrary>> sortedTransitiveNotDeclaredDeps;
  private final JarResolver jarResolver;

  /**
   * @return A function that takes a list of failed imports from a javac invocation and returns a
   *    set of rules to suggest that the developer import to satisfy those imports.
   */
  static SuggestBuildRules createSuggestBuildFunction(
      final JarResolver jarResolver,
      final ImmutableSetMultimap<JavaLibrary, Path> declaredClasspathEntries,
      final ImmutableSetMultimap<JavaLibrary, Path> transitiveClasspathEntries,
      final Iterable<BuildRule> buildRules) {

    final Supplier<ImmutableList<JavaLibrary>> sortedTransitiveNotDeclaredDeps =
        Suppliers.memoize(
            new Supplier<ImmutableList<JavaLibrary>>() {
              @Override
              public ImmutableList<JavaLibrary> get() {
                Set<JavaLibrary> transitiveNotDeclaredDeps = Sets.difference(
                    transitiveClasspathEntries.keySet(),
                    Sets.union(ImmutableSet.of(this), declaredClasspathEntries.keySet()));
                DirectedAcyclicGraph<BuildRule> graph =
                    BuildRuleDependencyVisitors.getBuildRuleDirectedGraphFilteredBy(
                        buildRules,
                        Predicates.instanceOf(JavaLibrary.class),
                        Predicates.instanceOf(JavaLibrary.class));
                return FluentIterable
                    .from(TopologicalSort.sort(graph, Predicates.<BuildRule>alwaysTrue()))
                    .filter(JavaLibrary.class)
                    .filter(Predicates.in(transitiveNotDeclaredDeps))
                    .toList()
                    .reverse();
              }
            });

    return new DefaultSuggestBuildRules(sortedTransitiveNotDeclaredDeps, jarResolver);
  }

  @Override
  public ImmutableSet<String> suggest(
      ImmutableSet<String> failedImports) {
    ImmutableSet.Builder<String> suggestedDeps = ImmutableSet.builder();

    Set<String> remainingImports = Sets.newHashSet(failedImports);

    for (JavaLibrary transitiveNotDeclaredDep : sortedTransitiveNotDeclaredDeps.get()) {
      if (isMissingBuildRule(
          transitiveNotDeclaredDep,
          remainingImports,
          jarResolver)) {
        suggestedDeps.add(transitiveNotDeclaredDep.getFullyQualifiedName());
      }
      // If we've wiped out all remaining imports, break the loop looking for them.
      if (remainingImports.isEmpty()) {
        break;
      }
    }
    return suggestedDeps.build();
  }

  private DefaultSuggestBuildRules(
      Supplier<ImmutableList<JavaLibrary>> sortedTransitiveNotDeclaredDeps,
      JarResolver jarResolver) {
    this.sortedTransitiveNotDeclaredDeps = sortedTransitiveNotDeclaredDeps;
    this.jarResolver = jarResolver;
  }

  /**
   *  @param transitiveNotDeclaredRule A {@link BuildRule} that is contained in the transitive
   *      dependency list but is not declared as a dependency.
   *  @param failedImports A Set of remaining failed imports.  This function will mutate this set
   *      and remove any imports satisfied by {@code transitiveNotDeclaredDep}.
   *  @return whether or not adding {@code transitiveNotDeclaredDep} as a dependency to this build
   *      rule would have satisfied one of the {@code failedImports}.
   */
  private boolean isMissingBuildRule(
      BuildRule transitiveNotDeclaredRule,
      Set<String> failedImports,
      JarResolver jarResolver) {
    if (!(transitiveNotDeclaredRule instanceof JavaLibrary)) {
      return false;
    }

    ImmutableSet<Path> classPaths =
        ImmutableSet.copyOf(
            ((JavaLibrary) transitiveNotDeclaredRule).getOutputClasspathEntries().values());
    boolean containsMissingBuildRule = false;
    // Open the output jar for every jar contained as the output of transitiveNotDeclaredDep.  With
    // the exception of rules that export their dependencies, this will result in a single
    // classpath.
    for (Path classPath : classPaths) {
      ImmutableSet<String> topLevelSymbols = jarResolver.resolve(classPath);

      for (String symbolName : topLevelSymbols) {
        if (failedImports.contains(symbolName)) {
          failedImports.remove(symbolName);
          containsMissingBuildRule = true;

          // If we've found all of the missing imports, bail out early.
          if (failedImports.isEmpty()) {
            return true;
          }
        }
      }
    }
    return containsMissingBuildRule;
  }
}
