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

package com.facebook.buck.cxx;

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.linkgroup.CxxLinkGroupMapping;
import com.facebook.buck.core.linkgroup.CxxLinkGroupMappingTarget;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.LinkableListFilter;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.shell.GenruleDescriptionArg;
import com.google.common.base.Predicates;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;

/**
 * Factory for the creation of {@link LinkableListFilter} which can be used to filter the libraries
 * which an executable (e.g., binary, shared library, Mach-O bundle) links against.
 */
public class LinkableListFilterFactory {
  private LinkableListFilterFactory() {}

  private static final LoadingCache<
          TargetGraph, LoadingCache<ImmutableList<CxxLinkGroupMapping>, Map<BuildTarget, String>>>
      graphCache =
          CacheBuilder.newBuilder()
              // Do _not_ hold onto TargetGraph, those can change in-between invocations and we do
              // not want to be holding onto old immutable graphs. NB: Weak keys means that the
              // cache will use _reference equality_, by definition, hence why we _deliberately_
              // set up a two-level cache, as we want weak TargetGraph and strong mapping semantics.
              .weakKeys()
              // Once a graph has changed, we do not want to cache different versions of the graph
              // in the off-chance that a future graph will match perfectly.
              .maximumSize(1)
              .build(
                  CacheLoader.from(
                      graph -> {
                        return CacheBuilder.newBuilder()
                            // Cache all mapping for a particular graph. Usually, there would be
                            // a single mapping re-used across all targets, so the perf savings
                            // are significant.
                            .build(
                                CacheLoader.from(
                                    mapping -> {
                                      return makeBuildTargetToLinkGroupMap(mapping, graph);
                                    }));
                      }));
  private static String MATCH_ALL_LINK_GROUP_NAME = "MATCH_ALL";

  /** Convenience method that unpacks a {@link LinkableCxxConstructorArg} and forwards the call. */
  public static Optional<LinkableListFilter> from(
      CxxBuckConfig cxxBuckConfig, LinkableCxxConstructorArg linkableArg, TargetGraph targetGraph) {
    if (!linkableArg.getLinkGroupMap().isPresent()) {
      return Optional.empty();
    }

    return from(
        cxxBuckConfig,
        linkableArg.getLinkGroup(),
        linkableArg.getLinkGroupMap().get(),
        targetGraph);
  }

  /**
   * Creates a {@link LinkableListFilter} based on a link group map and a target graph. Shared
   * libraries will get linked as normal and no filtering would occur. Static libraries will be
   * linked according to the link group membership.
   *
   * @param cxxBuckConfig If link groups are not enabled in the config, an empty {@link Optional}
   *     would be returned.
   * @param linkGroup Defines the link group of the executable being linked. By definition, it will
   *     be linked against libraries which belong to the same link group. If the link group is
   *     empty, the executable would linked against libraries which do not belong to any link
   *     groups.
   * @param mapping Defines the mapping which determines which link group specific targets belong
   *     to.
   * @param targetGraph The target graph which is used by the mapping to compute link group
   *     membership.
   */
  public static Optional<LinkableListFilter> from(
      CxxBuckConfig cxxBuckConfig,
      Optional<String> linkGroup,
      ImmutableList<CxxLinkGroupMapping> mapping,
      TargetGraph targetGraph) {
    if (!cxxBuckConfig.getLinkGroupsEnabled()) {
      return Optional.empty();
    }

    Map<BuildTarget, String> buildTargetToLinkGroupMap =
        getCachedBuildTargetToLinkGroupMap(mapping, targetGraph);

    LinkableListFilter filter =
        (ImmutableList<? extends NativeLinkable> allLinkables,
            Linker.LinkableDepType linkStyle) -> {
          return FluentIterable.from(allLinkables)
              .filter(
                  linkable -> {
                    Linker.LinkableDepType linkableType =
                        NativeLinkables.getLinkStyle(linkable.getPreferredLinkage(), linkStyle);
                    switch (linkableType) {
                      case STATIC:
                      case STATIC_PIC:
                        BuildTarget linkableBuildTarget = linkable.getBuildTarget();
                        if (!buildTargetToLinkGroupMap.containsKey(linkableBuildTarget)) {
                          // Ungrouped linkables belong to the unlabelled executable (by
                          // definition).
                          return !linkGroup.isPresent();
                        }

                        String linkableLinkGroup =
                            buildTargetToLinkGroupMap.get(linkableBuildTarget);
                        if (linkableLinkGroup.equals(MATCH_ALL_LINK_GROUP_NAME)) {
                          return true;
                        }

                        return (linkGroup
                                .map(group -> group.equals(linkableLinkGroup))
                                .orElse(false))
                            .booleanValue();

                      case SHARED:
                        // Shared libraries always get linked, by definition.
                        return true;
                    }

                    return false;
                  })
              .toList();
        };

    return Optional.of(filter);
  }

  /** Creates a predicate to filter resources that will be included in an apple_bundle. */
  @Nonnull
  public static Predicate<BuildTarget> resourcePredicateFrom(
      CxxBuckConfig cxxBuckConfig,
      Optional<String> resourceGroup,
      Optional<ImmutableList<CxxLinkGroupMapping>> mapping,
      TargetGraph graph) {
    if (!cxxBuckConfig.getLinkGroupsEnabled() || !mapping.isPresent()) {
      return Predicates.alwaysTrue();
    }

    Map<BuildTarget, String> targetToGroupMap =
        getCachedBuildTargetToLinkGroupMap(mapping.get(), graph);

    return (BuildTarget target) -> {
      if (!targetToGroupMap.containsKey(target)) {
        // Ungrouped targets belong to the unlabelled bundle (by def)
        return !resourceGroup.isPresent();
      }

      String targetGroup = targetToGroupMap.get(target);
      if (targetGroup.equals(MATCH_ALL_LINK_GROUP_NAME)) {
        return true;
      }

      return (resourceGroup.map(group -> group.equals(targetGroup)).orElse(false)).booleanValue();
    };
  }

  @Nonnull
  private static Map<BuildTarget, String> getCachedBuildTargetToLinkGroupMap(
      ImmutableList<CxxLinkGroupMapping> mapping, TargetGraph targetGraph) {
    LoadingCache<ImmutableList<CxxLinkGroupMapping>, Map<BuildTarget, String>> groupingCache =
        graphCache.getUnchecked(targetGraph);
    return groupingCache.getUnchecked(mapping);
  }

  /**
   * Precomputes link group membership based on the target graph, so that we can quickly check
   * whether a build target belongs to a link group.
   */
  @Nonnull
  private static Map<BuildTarget, String> makeBuildTargetToLinkGroupMap(
      ImmutableList<CxxLinkGroupMapping> mapping, TargetGraph targetGraph) {
    Map<BuildTarget, String> buildTargetToLinkGroupMap = new HashMap<>();
    for (CxxLinkGroupMapping groupMapping : mapping) {
      String currentLinkGroup = groupMapping.getLinkGroup();
      for (CxxLinkGroupMappingTarget mappingTarget : groupMapping.getMappingTargets()) {
        final ImmutableList<BuildTarget> buildTargets =
            getBuildTargetsForMapping(targetGraph, mappingTarget);

        for (BuildTarget buildTarget : buildTargets) {
          addGroupMappingForBuildTarget(
              targetGraph,
              buildTargetToLinkGroupMap,
              currentLinkGroup,
              mappingTarget.getTraversal(),
              buildTarget);
        }
      }
    }
    return buildTargetToLinkGroupMap;
  }

  @Nonnull
  private static ImmutableList<BuildTarget> getBuildTargetsForMapping(
      TargetGraph targetGraph, CxxLinkGroupMappingTarget mappingTarget) {

    Optional<Pattern> labelPattern = mappingTarget.getLabelPattern();
    if (!labelPattern.isPresent()) {
      return ImmutableList.of(mappingTarget.getBuildTarget());
    }

    return findBuildTargetsMatchingLabelPattern(targetGraph, mappingTarget, labelPattern.get());
  }

  @Nonnull
  private static ImmutableList<BuildTarget> findBuildTargetsMatchingLabelPattern(
      TargetGraph targetGraph, CxxLinkGroupMappingTarget mappingTarget, Pattern regex) {
    ImmutableList.Builder<BuildTarget> allTargets = ImmutableList.builder();
    TargetNode<?> initialTargetNode = targetGraph.get(mappingTarget.getBuildTarget());

    AbstractBreadthFirstTraversal<TargetNode<?>> treeTraversal =
        new AbstractBreadthFirstTraversal<TargetNode<?>>(initialTargetNode) {
          @Override
          public Iterable<TargetNode<?>> visit(TargetNode<?> node) {
            if (shouldSkipTraversingNode(node)) {
              return Collections.emptySet();
            }

            boolean matchesRegex = false;
            if (node.getConstructorArg() instanceof BuildRuleArg) {
              BuildRuleArg buildRuleArg = (BuildRuleArg) node.getConstructorArg();
              for (String label : buildRuleArg.getLabels()) {
                matchesRegex = regex.matcher(label).matches();
                if (matchesRegex) {
                  break;
                }
              }
            }

            if (matchesRegex) {
              allTargets.add(node.getBuildTarget());
              if (mappingTarget.getTraversal() == CxxLinkGroupMappingTarget.Traversal.TREE) {
                // We can stop traversing the tree at this point because we've added the
                // build target to the set of all targets that will be traversed by the
                // algorithm that applies the link groups.
                return Collections.emptySet();
              }
            }

            return targetGraph.getOutgoingNodesFor(node);
          }
        };
    treeTraversal.start();

    return allTargets.build();
  }

  /**
   * Given a {@param buildTarget} and a {@param currentLinkGroup}, applies the group to the {@param
   * targetGraph} as specified by the {@param traversal}.
   */
  private static void addGroupMappingForBuildTarget(
      TargetGraph targetGraph,
      Map<BuildTarget, String> buildTargetToLinkGroupMap,
      String currentLinkGroup,
      CxxLinkGroupMappingTarget.Traversal traversal,
      BuildTarget buildTarget) {
    switch (traversal) {
      case TREE:
        TargetNode<?> initialTargetNode = targetGraph.get(buildTarget);
        AbstractBreadthFirstTraversal<TargetNode<?>> treeTraversal =
            new AbstractBreadthFirstTraversal<TargetNode<?>>(initialTargetNode) {

              @Override
              public Iterable<TargetNode<?>> visit(TargetNode<?> node) {
                addBuildTargetToLinkGroup(
                    node.getBuildTarget(), currentLinkGroup, buildTargetToLinkGroupMap);
                if (shouldSkipTraversingNode(node)) {
                  return Collections.emptySet();
                } else {
                  return targetGraph.getOutgoingNodesFor(node);
                }
              }
            };
        treeTraversal.start();
        break;

      case NODE:
        addBuildTargetToLinkGroup(buildTarget, currentLinkGroup, buildTargetToLinkGroupMap);
        break;
    }
  }

  private static boolean shouldSkipTraversingNode(TargetNode<?> node) {
    // cut the branch if the node type is genrule
    return node.getDescription().getConstructorArgType().equals(GenruleDescriptionArg.class);
  }

  private static void addBuildTargetToLinkGroup(
      BuildTarget buildTarget,
      String linkGroup,
      Map<BuildTarget, String> buildTargetToLinkGroupMap) {
    if (!buildTargetToLinkGroupMap.containsKey(buildTarget)) {
      buildTargetToLinkGroupMap.put(buildTarget, linkGroup);
    }
  }
}
