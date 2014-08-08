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

package com.facebook.buck.cxx;

import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractDependencyVisitor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Functions;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

public class CxxLinkableEnhancer {

  // Utility class doesn't instantiate.
  private CxxLinkableEnhancer() {}

  /**
   * Topologically sort the dependency chain represented by the given inputs and filter
   * by the given class.
   */
  private static <A extends BuildRule> ImmutableList<BuildRule> topoSort(Iterable<A> inputs) {

    // Build up a graph of the inputs and their transitive dependencies.
    final MutableDirectedGraph<BuildRule> graph = new MutableDirectedGraph<>();
    AbstractDependencyVisitor visitor = new AbstractDependencyVisitor(
        ImmutableList.<BuildRule>copyOf(inputs)) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        graph.addNode(rule);
        for (BuildRule dep : rule.getDeps()) {
          graph.addEdge(rule, dep);
        }
        return rule.getDeps();
      }
    };
    visitor.start();

    // Topologically sort the graph and return as a list.
    return FluentIterable
        .from(TopologicalSort.sort(graph, Predicates.<BuildRule>alwaysTrue()))
        .toList();
  }

  /**
   * Construct a {@link CxxLink} rule that builds a native linkable from top-level input objects
   * and a dependency tree of {@link NativeLinkable} dependencies.
   */
  public static CxxLink createCxxLinkableBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      Path linker,
      ImmutableList<String> cxxLdFlags,
      ImmutableList<String> ldFlags,
      BuildTarget target,
      Path output,
      Iterable<SourcePath> objects,
      Iterable<BuildRule> nativeLinkableDeps) {

    // Collect and topologically sort our deps that contribute to the link.
    NativeLinkableInput linkableInput = NativeLinkableInput.concat(
        FluentIterable
            .from(topoSort(nativeLinkableDeps).reverse())
            .filter(NativeLinkable.class)
            .transform(NativeLinkable.GET_NATIVE_LINKABLE_INPUT));

    // Construct our link build rule params.  The important part here is combining the build rules
    // that construct our object file inputs and also the deps that build our dependencies.
    BuildRuleParams linkParams = params.copyWithChanges(
        NativeLinkable.NATIVE_LINKABLE_TYPE,
        target,
        ImmutableSortedSet.copyOf(
            Iterables.concat(
                // Add dependencies for build rules generating the object files.
                SourcePaths.filterBuildRuleInputs(objects),
                // Add dependencies for the target-node-level dependencies that
                // contribute to the link.
                BuildRules.toBuildRulesFor(target, resolver, linkableInput.getTargets(), false))),
        ImmutableSortedSet.<BuildRule>of());

    // Build up the arguments to pass to the linker.
    ImmutableList<String> args = ImmutableList.<String>builder()
        .addAll(cxxLdFlags)
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-Xlinker"),
                ldFlags))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-Xlinker"),
                Iterables.concat(
                    FluentIterable.from(objects)
                        .transform(SourcePaths.TO_PATH)
                        .transform(Functions.toStringFunction()),
                    linkableInput.getArgs())))
        .build();

    // Build the C/C++ link step.
    return new CxxLink(
        linkParams,
        linker,
        output,
        ImmutableList.<SourcePath>builder()
            .addAll(objects)
            .addAll(SourcePaths.toSourcePathsSortedByNaturalOrder(linkableInput.getInputs()))
            .build(),
        args);
  }

}
