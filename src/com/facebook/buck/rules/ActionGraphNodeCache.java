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

package com.facebook.buck.rules;

import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.collect.SortedSets;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Cache for action graph subgraphs associated with particular target graph nodes, used to enable
 * incremental action graph construction.
 *
 * <p>This class tracks target graph nodes and their associated build rule subgraphs. Whenever we
 * start a new target graph walk, we invalidate parts of the cache that are stale. During the target
 * graph walk {@see ActionGraphCache#createActionGraphSerially}, if we find a target graph node that
 * is in the cache, we grab the build rules that target node generated last time, and splice them
 * into the new action graph, without traversing further down in the target graph. Once we've
 * completed the walk, we make sure to update the {@link BuildRuleResolver}s any of the build rules
 * might be hanging on to to prevent leaking the previous action graph.
 */
public class ActionGraphNodeCache {
  private static class CacheEntry {
    private final TargetNode<?, ?> targetNode;
    private final BuildRule buildRule;

    public CacheEntry(TargetNode<?, ?> targetNode, BuildRule buildRule) {
      this.targetNode = targetNode;
      this.buildRule = buildRule;
    }

    public TargetNode<?, ?> getTargetNode() {
      return targetNode;
    }

    public BuildRule getBuildRule() {
      return buildRule;
    }
  }

  private static final Logger LOG = Logger.get(ActionGraphNodeCache.class);

  private final Cache<BuildTarget, CacheEntry> buildRuleSubgraphCache;
  private Map<BuildRule, SortedSet<BuildRule>> lastDepsCache = new ConcurrentHashMap<>();
  private Map<BuildRule, SortedSet<BuildRule>> depsCache = new ConcurrentHashMap<>();
  @Nullable private BuildRuleResolver lastRuleResolver;
  @Nullable private BuildRuleResolver ruleResolver;
  private boolean isTargetGraphWalkInProgress;

  public ActionGraphNodeCache(int maxEntries) {
    buildRuleSubgraphCache = CacheBuilder.newBuilder().maximumSize(maxEntries).build();
  }

  /** Checks whether a target node equivalent to the given target node is cached. */
  public boolean containsKey(TargetNode<?, ?> targetNode) {
    CacheEntry cacheEntry = buildRuleSubgraphCache.getIfPresent(targetNode.getBuildTarget());
    if (cacheEntry == null) {
      return false;
    }

    // Validate our assumption that the cache has been properly invalidated, leaving only
    // unchanged target nodes cached.
    Preconditions.checkState(
        cacheEntry.getTargetNode().equals(targetNode), "cache wasn't properly invalidated");
    return true;
  }

  /**
   * Adds the build rule for the given target node to the rule resolver. Fetches from cache if
   * available, and caches after creation if supported by the build rule.
   *
   * <p>Assumes we get called for a node's children before we get called for the node itself.
   */
  public BuildRule requireRule(TargetNode<?, ?> targetNode) {
    Preconditions.checkState(isTargetGraphWalkInProgress);
    BuildRule cachedBuildRule = getIfPresent(targetNode);
    if (cachedBuildRule != null) {
      addBuildRuleSubgraphToIndex(cachedBuildRule, ruleResolver);
      return cachedBuildRule;
    }
    BuildRule newBuildRule = ruleResolver.requireRule(targetNode.getBuildTarget());
    offerForCaching(targetNode, newBuildRule);
    return newBuildRule;
  }

  @Nullable
  private BuildRule getIfPresent(TargetNode<?, ?> targetNode) {
    CacheEntry cacheEntry = buildRuleSubgraphCache.getIfPresent(targetNode.getBuildTarget());
    if (cacheEntry == null) {
      if (LOG.isVerboseEnabled()) {
        LOG.verbose("cache miss for target %s", targetNode.getBuildTarget().toString());
      }
      return null;
    }

    // Validate our assumption that the cache has been properly invalidated, leaving only
    // unchanged target nodes cached.
    Preconditions.checkState(
        cacheEntry.getTargetNode().equals(targetNode), "cache wasn't properly invalidated");

    // Target graph node hasn't changed (no new deps, etc.), so return action graph subtree
    // from cache.
    if (LOG.isVerboseEnabled()) {
      LOG.verbose(
          "cache hit for target %s of type %s",
          targetNode.getBuildTarget().toString(), cacheEntry.getBuildRule().getType());
    }
    return cacheEntry.getBuildRule();
  }

  private void offerForCaching(TargetNode<?, ?> targetNode, BuildRule buildRule) {
    // Incremental caching is only supported for build rules known to be safe. This is because we
    // cannot generally guarantee that build rules won't do crazy things that violate our
    // assumptions during their construction. We further require that our children are also
    // cached to disallow caching nodes with uncacheable descendants.
    if (!(buildRule instanceof CacheableBuildRule) || !areDirectTargetGraphDepsCached(targetNode)) {
      if (LOG.isVerboseEnabled()) {
        LOG.verbose(
            "not caching target %s of type %s",
            targetNode.getBuildTarget().toString(), buildRule.getType());
      }

      // Note that we don't need to invalidate parent chains if we attempt to insert a non-allowed
      // build rule here, as a node will never get added to the cache if its children aren't also
      // cached.
      return;
    }

    if (LOG.isVerboseEnabled()) {
      LOG.verbose(
          "caching target %s of type %s",
          targetNode.getBuildTarget().toString(), buildRule.getType());
    }
    buildRuleSubgraphCache.put(targetNode.getBuildTarget(), new CacheEntry(targetNode, buildRule));
  }

  private boolean areDirectTargetGraphDepsCached(TargetNode<?, ?> targetNode) {
    for (BuildTarget dep : targetNode.getParseDeps()) {
      if (buildRuleSubgraphCache.getIfPresent(dep) == null) {
        return false;
      }
    }
    return true;
  }

  private void addBuildRuleSubgraphToIndex(BuildRule buildRule, BuildRuleResolver resolver) {
    if (resolver.getRuleOptional(buildRule.getBuildTarget()).isPresent()) {
      // If a rule has already been added, then its entire subgraph has also been added, whether
      // by this method or a call to {@see BuildRuleResolver#requireRule}.
      return;
    }

    if (LOG.isVerboseEnabled()) {
      LOG.verbose(
          "adding %s of type %s to index", buildRule.getFullyQualifiedName(), buildRule.getType());
    }

    // Use {@see BuildRuleResolver#computeIfAbsent} here instead of {@see
    // BuildRuleResolver#addToIndex} to avoid a race where we might add a duplicate build rule
    // during parallel action graph construction.
    resolver.computeIfAbsent(buildRule.getBuildTarget(), buildTarget -> buildRule);

    for (BuildRule dep : getDeps(buildRule)) {
      addBuildRuleSubgraphToIndex(dep, resolver);
    }
  }

  private SortedSet<BuildRule> getDeps(BuildRule buildRule) {
    // Note: The caching of deps here is not just a performance optimization; it's also needed for
    //       correctness. If a build rule remains in the cache, but doesn't get used for several
    //       action graph constructions, the last {@see BuildRuleResolver} may not have that rule's
    //       runtime deps, so we'd have a problem computing them. We rely on the deps cache to get
    //       the right deps (including runtime deps) in this scenario. Note that every build rule's
    //       deps will be in this cache, as we call {@see #updateRuleResolvers} for every build rule
    //       in the cache.
    return depsCache.computeIfAbsent(buildRule, this::computeDeps);
  }

  private SortedSet<BuildRule> computeDeps(BuildRule buildRule) {
    // If we already computed deps last time, just transfer the result over from the last cache.
    SortedSet<BuildRule> deps = lastDepsCache.get(buildRule);
    if (deps != null) {
      return deps;
    }

    Preconditions.checkState(buildRule instanceof CacheableBuildRule);
    deps =
        SortedSets.union(
            buildRule.getBuildDeps(), ((CacheableBuildRule) buildRule).getImplicitDepsForCaching());

    // Include target graph "only" deps too. There are cases where an uncached build rule may depend
    // on a cached build rule's target graph only deps existing in the {@see BuildRuleResolver}
    // during construction.
    if (buildRule instanceof HasDeclaredAndExtraDeps) {
      deps = SortedSets.union(deps, ((HasDeclaredAndExtraDeps) buildRule).getTargetGraphOnlyDeps());
    }

    // We need to use the rule resolver from the last action graph construction to find runtime
    // deps. These deps won't necessarily exist in the new rule resolver yet.
    if (buildRule instanceof HasRuntimeDeps) {
      Preconditions.checkNotNull(lastRuleResolver);
      deps =
          SortedSets.union(
              deps,
              lastRuleResolver.getAllRules(
                  RichStream.from(
                          ((HasRuntimeDeps) buildRule)
                              .getRuntimeDeps(new SourcePathRuleFinder(lastRuleResolver)))
                      .toOnceIterable()));
    }

    return deps;
  }

  /**
   * Prepares the cache for a target graph walk by incrementally invalidating the cache.
   *
   * <p>Must be called before any {@see #requireRule} calls.
   */
  public void prepareForTargetGraphWalk(TargetGraph targetGraph, BuildRuleResolver ruleResolver) {
    Preconditions.checkState(!isTargetGraphWalkInProgress);
    isTargetGraphWalkInProgress = true;

    // We cache the last build rule resolver so we can grab runtimeDeps for cached rules from it.
    lastRuleResolver = this.ruleResolver;
    this.ruleResolver = ruleResolver;

    lastDepsCache = depsCache;
    depsCache = new ConcurrentHashMap<>();

    LOG.debug("begin cache invalidation for target graph walk");
    Stopwatch stopwatch = Stopwatch.createStarted();

    if (buildRuleSubgraphCache.size() > 0) {
      // Walk the target graph and remove any invalidated entries from the cache.
      Map<BuildTarget, Boolean> explored = new HashMap<>();
      for (TargetNode<?, ?> root : targetGraph.getNodesWithNoIncomingEdges()) {
        invalidateChangedTargets(root, targetGraph, explored);
      }

      // Update build rule resolvers for all cached entries. Build rules may use build rule
      // resolvers to locate and construct other build rules during construction. Furthermore, if we
      // didn't update them, we'd leak previous action graphs.
      updateRuleResolvers(ruleResolver);
    }

    LOG.debug(
        "end cache invalidation for target graph walk, took %s secs",
        String.valueOf(stopwatch.elapsed(TimeUnit.MILLISECONDS) / 1000.0));
  }

  private boolean invalidateChangedTargets(
      TargetNode<?, ?> node, TargetGraph targetGraph, Map<BuildTarget, Boolean> explored) {
    if (explored.containsKey(node.getBuildTarget())) {
      return explored.get(node.getBuildTarget());
    }

    // Recursively check if any node in a child subgraph causes invalidation of its parent chain.
    boolean ancestorInvalidated = false;
    for (TargetNode<?, ?> child : targetGraph.getOutgoingNodesFor(node)) {
      // Note: We can't short circuit here since we need to also make sure things inside child
      // subgraphs get properly invalidated in turn.
      ancestorInvalidated |= invalidateChangedTargets(child, targetGraph, explored);
    }

    boolean invalidateParent = false;
    if (ancestorInvalidated || shouldInvalidateParentChain(node)) {
      // We need to invalidate this target node. Simply remove it from the cache, and trigger
      // an invalidation of our parent chain.
      if (LOG.isVerboseEnabled()) {
        LOG.verbose("invalidating target %s", node.getBuildTarget().toString());
      }
      buildRuleSubgraphCache.invalidate(node.getBuildTarget());
      invalidateParent = true;
    }

    explored.put(node.getBuildTarget(), invalidateParent);
    return invalidateParent;
  }

  private boolean shouldInvalidateParentChain(TargetNode<?, ?> targetNode) {
    CacheEntry cacheEntry = buildRuleSubgraphCache.getIfPresent(targetNode.getBuildTarget());
    if (cacheEntry == null) {
      // If the node isn't cached, we need to invalidate the parent chain. There is an edge case
      // where we run out of room in the cache, and potentially push out a child of a cached node.
      // This child node may have changed, and we may otherwise miss a required invalidation of the
      // cached parent.
      if (LOG.isVerboseEnabled()) {
        LOG.verbose(
            "target %s caused invalidation due to not being cached",
            targetNode.getBuildTarget().toString());
      }
      return true;
    }

    // Otherwise, check if the target node for this build target has changed.
    boolean shouldInvalidate = !cacheEntry.getTargetNode().equals(targetNode);
    if (shouldInvalidate && LOG.isVerboseEnabled()) {
      LOG.verbose(
          "target %s of type %s caused invalidation due to target node change",
          targetNode.getBuildTarget().toString(), cacheEntry.getBuildRule().getType());
    }
    return shouldInvalidate;
  }

  private void updateRuleResolvers(BuildRuleResolver ruleResolver) {
    Stopwatch stopwatch = Stopwatch.createStarted();

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    // Unfortunately, we need to update rule resolvers for every node in the cache, even ones we
    // don't use for the action graph currently under construction, to avoid leaking previous action
    // graphs. A way to avoid this would be to only cache nodes from the last action graph, but that
    // would reduce the utility of the cache for more complicated build scenarios.
    Set<BuildRule> visited = new HashSet<>();
    for (CacheEntry entry : buildRuleSubgraphCache.asMap().values()) {
      updateRuleResolvers(entry.getBuildRule(), ruleResolver, ruleFinder, pathResolver, visited);
    }

    LOG.debug(
        "updating rule resolvers took %s secs",
        String.valueOf(stopwatch.elapsed(TimeUnit.MILLISECONDS) / 1000.0));
  }

  private void updateRuleResolvers(
      BuildRule buildRule,
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver,
      Set<BuildRule> visited) {
    if (visited.contains(buildRule)) {
      return;
    }

    ((CacheableBuildRule) buildRule)
        .updateBuildRuleResolver(ruleResolver, ruleFinder, pathResolver);
    visited.add(buildRule);

    for (BuildRule dep : getDeps(buildRule)) {
      // Take this opportunity to ensure that the transitive deps of any cacheable build rule are
      // themselves cacheable. Note that the situation in which a cacheable build rule has a non
      // cacheable build rule in its subgraph due to a direct target graph dep is not possible here,
      // as we would have removed the entire parent chain from the cache during invalidation.
      Preconditions.checkState(
          dep instanceof CacheableBuildRule,
          "build rule type %s is marked as cacheable, expected dep %s to also be marked",
          buildRule.getType(),
          dep.getType());

      updateRuleResolvers(dep, ruleResolver, ruleFinder, pathResolver, visited);
    }
  }

  /** Informs the cache that the current target graph walk is over. */
  public void finishTargetGraphWalk() {
    Preconditions.checkState(isTargetGraphWalkInProgress);
    isTargetGraphWalkInProgress = false;

    // Invalidate the previous {@see BuildRuleResolver}, which we no longer need, to make sure
    // nobody unexpectedly accesses after this point.
    if (lastRuleResolver != null) {
      lastRuleResolver.invalidate();
      lastRuleResolver = null;
    }
    // TODO(jtorkkola): It'd be better to check that all deps of cacheable rules are themselves
    //                  cacheable here during the first action graph construction, so we could error
    //                  out as early as possible if a developer makes a build rule change without
    //                  proper use of {@see CacheableBuildRule}. Unfortunately, computing all deps
    //                  on the first iteration would slow down the first action graph construction.
  }
}
