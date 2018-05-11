/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.model.actiongraph.computation;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Preconditions;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Provides a way to incrementally construct a new {@link BuildRuleResolver} from a previous one.
 *
 * <p>This works by grabbing all the build rules from the previous {@link BuildRuleResolver} with
 * unflavored targets that were not invalidated when doing a target graph walk to check for changes.
 */
public class IncrementalActionGraphGenerator {
  private static final Logger LOG = Logger.get(IncrementalActionGraphGenerator.class);

  @Nullable private BuildRuleResolver lastRuleResolver;
  @Nullable private TargetGraph lastTargetGraph;

  /**
   * Populates the given {@link BuildRuleResolver} with the rules from the previously used {@link
   * BuildRuleResolver} that are deemed usable after checking for invalidations with a target graph
   * walk.
   */
  public void populateRuleResolverWithCachedRules(
      TargetGraph targetGraph, BuildRuleResolver ruleResolver) {
    if (lastRuleResolver != null) {
      // We figure out which build rules we can reuse from the last action graph by performing an
      // invalidation walk over the new target graph.
      Map<BuildTarget, Boolean> explored = new HashMap<>();
      Set<UnflavoredBuildTarget> validTargets = new HashSet<>();
      for (TargetNode<?, ?> root : targetGraph.getNodesWithNoIncomingEdges()) {
        invalidateChangedTargets(root, targetGraph, explored, validTargets);
      }

      // Now we can load in all build rules whose unflavored targets weren't invalidated for
      // incremental action graph generation.
      addValidRulesToRuleResolver(ruleResolver, validTargets);

      // Invalidate the previous {@see BuildRuleResolver}, which we no longer need, to make sure
      // nobody unexpectedly accesses it after this point.
      lastRuleResolver.invalidate();
    }

    lastTargetGraph = targetGraph;
    lastRuleResolver = ruleResolver;
  }

  private void addValidRulesToRuleResolver(
      BuildRuleResolver ruleResolver, Set<UnflavoredBuildTarget> validTargets) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    int totalRuleCount = 0;
    int loadedRuleCount = 0;
    for (BuildRule buildRule : lastRuleResolver.getBuildRules()) {
      if (validTargets.contains(buildRule.getBuildTarget().getUnflavoredBuildTarget())) {
        ruleResolver.addToIndex(buildRule);

        // Update build rule resolvers for all reused rules. Build rules may use build rule
        // resolvers to locate and construct other build rules during construction. Furthermore, if
        // we didn't update them, we'd leak previous action graphs.
        buildRule.updateBuildRuleResolver(ruleResolver, ruleFinder, pathResolver);

        loadedRuleCount++;
      }
      totalRuleCount++;
    }
    LOG.debug("loaded %d out of %d build rules incrementally", loadedRuleCount, totalRuleCount);
  }

  private boolean invalidateChangedTargets(
      TargetNode<?, ?> node,
      TargetGraph targetGraph,
      Map<BuildTarget, Boolean> explored,
      Set<UnflavoredBuildTarget> validTargets) {
    if (explored.containsKey(node.getBuildTarget())) {
      return explored.get(node.getBuildTarget());
    }

    // Recursively check if any node in a child subgraph causes invalidation of its parent chain.
    boolean ancestorInvalidated = false;
    for (TargetNode<?, ?> child : targetGraph.getOutgoingNodesFor(node)) {
      // Note: We can't short circuit here since we need to also make sure things inside child
      // subgraphs get properly invalidated in turn.
      ancestorInvalidated |= invalidateChangedTargets(child, targetGraph, explored, validTargets);
    }

    boolean invalidateParent = false;
    if (ancestorInvalidated || shouldInvalidateParentChain(node)) {
      if (LOG.isVerboseEnabled()) {
        LOG.verbose("invalidating target %s", node.getBuildTarget().toString());
      }
      invalidateParent = true;
    } else {
      // This node is valid. We can load all the flavors of its targets from cache.
      validTargets.add(node.getBuildTarget().getUnflavoredBuildTarget());
    }

    explored.put(node.getBuildTarget(), invalidateParent);
    return invalidateParent;
  }

  private boolean shouldInvalidateParentChain(TargetNode<?, ?> targetNode) {
    if (lastTargetGraph != null) {
      Optional<TargetNode<?, ?>> previousTargetNode =
          lastTargetGraph.getExactOptional(targetNode.getBuildTarget());
      if (previousTargetNode.isPresent()) {
        Preconditions.checkState(
            lastRuleResolver.getRuleOptional(targetNode.getBuildTarget()).isPresent());
        // If the target node has changed, then invalidate parent chains, as ancestors might
        // generate their subgraphs differently given the change.
        if (!targetNode.equals(previousTargetNode.get())) {
          if (LOG.isVerboseEnabled()) {
            LOG.verbose(
                "target %s caused invalidation due to target node change",
                targetNode.getBuildTarget().toString());
          }
          return true;
        }
      }
    }
    // Incremental caching is only supported for {@link Description}s known to be safe. This is
    // because we cannot generally guarantee that descriptions won't do crazy things that violate
    // our assumptions during their construction.
    if (!targetNode.getDescription().producesCacheableSubgraph()) {
      if (LOG.isVerboseEnabled()) {
        LOG.verbose(
            "target %s caused invalidation due to not being cacheable",
            targetNode.getBuildTarget().toString());
      }
      return true;
    }
    return false;
  }
}
