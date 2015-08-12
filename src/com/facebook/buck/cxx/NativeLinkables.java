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

import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.util.List;
import java.util.Map;

public class NativeLinkables {

  private NativeLinkables() {}

  /**
   * A helper function object that grabs the {@link NativeLinkableInput} object from a
   * {@link NativeLinkable}.
   */
  public static Function<NativeLinkable, NativeLinkableInput> getNativeLinkableInput(
      final TargetGraph targetGraph,
      final CxxPlatform cxxPlatform,
      final Linker.LinkableDepType type) {
    return new Function<NativeLinkable, NativeLinkableInput>() {
      @Override
      public NativeLinkableInput apply(NativeLinkable input) {
        return input.getNativeLinkableInput(targetGraph, cxxPlatform, type);
      }
    };
  }

  private static NativeLinkableNode processBuildRule(
      CxxPlatform cxxPlatform,
      Map<BuildTarget, Linker.LinkableDepType> wanted,
      BuildRule rule,
      Linker.LinkableDepType type,
      NativeLinkableNode.Pass pass) {

    if (!(rule instanceof NativeLinkable)) {
      return NativeLinkableNode.of(rule, pass);
    }

    NativeLinkable linkable = (NativeLinkable) rule;
    NativeLinkable.Linkage depLinkage = linkable.getPreferredLinkage(cxxPlatform);
    Linker.LinkableDepType depType;
    switch (depLinkage) {
      case STATIC: {
        depType =
            type == Linker.LinkableDepType.STATIC ?
                Linker.LinkableDepType.STATIC :
                Linker.LinkableDepType.STATIC_PIC;
        break;
      }
      case ANY: {
        depType = type;
        break;
      }
      default: {
        throw new IllegalStateException();
      }
    }

    // We want to get linkable info for this dep if we're linking statically, or if
    // this dep is linked dynamically.  More to the point: we want to avoid pulling
    // in linkable info for a library which is statically linked into a shared dep.
    if (pass == AbstractNativeLinkableNode.Pass.ANY || depType == Linker.LinkableDepType.SHARED) {
      Linker.LinkableDepType oldType = wanted.put(rule.getBuildTarget(), depType);
      Preconditions.checkState(oldType == null || oldType == depType);
    }

    // If we're linking in a shared dep, then switch
    if (depType == Linker.LinkableDepType.SHARED) {
      pass = AbstractNativeLinkableNode.Pass.SHARED_ONLY;
    }

    return NativeLinkableNode.of(rule, pass);
  }

  /**
   * Collect up and merge all {@link com.facebook.buck.cxx.NativeLinkableInput} objects from
   * transitively traversing all unbroken dependency chains of
   * {@link com.facebook.buck.cxx.NativeLinkable} objects found via the passed in
   * {@link com.facebook.buck.rules.BuildRule} roots.
   */
  public static Pair<MutableDirectedGraph<BuildRule>, Map<BuildTarget, Linker.LinkableDepType>>
      getTransitiveNativeLinkableInput(
          final CxxPlatform cxxPlatform,
          Iterable<? extends BuildRule> inputs,
          final Linker.LinkableDepType depType,
          final Predicate<Object> traverse) {

    // Keep track of the rules for which we want to grab linkable information from, and the
    // style with which to link them.
    final Map<BuildTarget, Linker.LinkableDepType> wanted = Maps.newHashMap();

    List<NativeLinkableNode> initial = Lists.newArrayList();
    for (BuildRule rule : inputs) {
      initial.add(
          processBuildRule(
              cxxPlatform,
              wanted,
              rule,
              depType,
              AbstractNativeLinkableNode.Pass.ANY));
    }

    final MutableDirectedGraph<BuildRule> graph = new MutableDirectedGraph<>();
    final MutableDirectedGraph<NativeLinkableNode> linkGraph = new MutableDirectedGraph<>();
    AbstractBreadthFirstTraversal<NativeLinkableNode> visitor =
        new AbstractBreadthFirstTraversal<NativeLinkableNode>(initial) {
          @Override
          public ImmutableSet<NativeLinkableNode> visit(NativeLinkableNode node) {

            linkGraph.addNode(node);
            graph.addNode(node.getBuildRule());

            if (!traverse.apply(node.getBuildRule())) {
              return ImmutableSet.of();
            }

            ImmutableSet.Builder<NativeLinkableNode> deps = ImmutableSet.builder();
            for (BuildRule dep : node.getBuildRule().getDeps()) {
              if (traverse.apply(dep)) {
                NativeLinkableNode nodeDep =
                    processBuildRule(
                        cxxPlatform,
                        wanted,
                        dep,
                        depType,
                        node.getPass());
                linkGraph.addEdge(node, nodeDep);
                graph.addEdge(node.getBuildRule(), dep);
                deps.add(nodeDep);
              }
            }

            return deps.build();
          }
        };
    visitor.start();

    return new Pair<>(graph, wanted);
  }

  /**
   * Collect up and merge all {@link com.facebook.buck.cxx.NativeLinkableInput} objects from
   * transitively traversing all unbroken dependency chains of
   * {@link com.facebook.buck.cxx.NativeLinkable} objects found via the passed in
   * {@link com.facebook.buck.rules.BuildRule} roots.
   */
  public static NativeLinkableInput getTransitiveNativeLinkableInput(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> inputs,
      Linker.LinkableDepType depType,
      Predicate<Object> traverse,
      ImmutableSet<BuildRule> blacklist,
      boolean reverse) {

    // Build up the graph and bookkeeping tracking how we link each dep.
    Pair<MutableDirectedGraph<BuildRule>, Map<BuildTarget, Linker.LinkableDepType>> result =
        getTransitiveNativeLinkableInput(cxxPlatform, inputs, depType, traverse);

    // Collect and topologically sort our deps that contribute to the link.
    ImmutableList<BuildRule> sorted = TopologicalSort.sort(
        result.getFirst(),
        Predicates.<BuildRule>alwaysTrue());

    List<NativeLinkableInput> nativeLinkableInputs = Lists.newArrayList();

    for (BuildRule buildRule : reverse ? sorted.reverse() : sorted) {
      if (buildRule instanceof NativeLinkable) {
        Linker.LinkableDepType type = result.getSecond().get(buildRule.getBuildTarget());
        if (type != null && !blacklist.contains(buildRule)) {
          NativeLinkable linkable = (NativeLinkable) buildRule;
          nativeLinkableInputs.add(linkable.getNativeLinkableInput(targetGraph, cxxPlatform, type));
        }
      }
    }

    return NativeLinkableInput.concat(nativeLinkableInputs);
  }

  public static NativeLinkableInput getTransitiveNativeLinkableInput(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> inputs,
      Linker.LinkableDepType depType,
      ImmutableSet<BuildRule> blacklist,
      boolean reverse) {
    return getTransitiveNativeLinkableInput(
        targetGraph,
        cxxPlatform,
        inputs,
        depType,
        Predicates.instanceOf(NativeLinkable.class),
        blacklist,
        reverse);
  }

  /**
   * Collect all the shared libraries generated by {@link NativeLinkable}s found by transitively
   * traversing all unbroken dependency chains of {@link com.facebook.buck.cxx.NativeLinkable}
   * objects found via the passed in {@link com.facebook.buck.rules.BuildRule} roots.
   *
   * @return a mapping of library name to the library {@link SourcePath}.
   */
  public static ImmutableSortedMap<String, SourcePath> getTransitiveSharedLibraries(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> inputs,
      Linker.LinkableDepType depType,
      Predicate<Object> traverse) {

    // Build up the graph and bookkeeping tracking how we link each dep.
    Pair<MutableDirectedGraph<BuildRule>, Map<BuildTarget, Linker.LinkableDepType>> result =
        getTransitiveNativeLinkableInput(
            cxxPlatform,
            inputs,
            depType,
            traverse);

    ImmutableSortedMap.Builder<String, SourcePath> libraries = ImmutableSortedMap.naturalOrder();

    for (BuildRule buildRule : result.getFirst().getNodes()) {
      if (buildRule instanceof NativeLinkable) {
        Linker.LinkableDepType type = result.getSecond().get(buildRule.getBuildTarget());
        if (type != null && type == Linker.LinkableDepType.SHARED) {
          NativeLinkable linkable = (NativeLinkable) buildRule;
          libraries.putAll(linkable.getSharedLibraries(targetGraph, cxxPlatform));
        }
      }
    }

    return libraries.build();
  }

}
