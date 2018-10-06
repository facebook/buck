/*
 * Copyright 2017-present Facebook, Inc.
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
package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.core.build.engine.RuleDepsCache;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.distributed.ArtifactCacheByBuildRule;
import com.facebook.buck.distributed.ClientStatsTracker;
import com.facebook.buck.distributed.build_slave.DistributableBuildGraph.DistributableNode;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Queues;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Helper class for creating a {@link BuildTargetsQueue} optimized by using the remote and local
 * artifact caches, and to upload critical, missing artifacts from the local cache to the remote
 * cache.
 */
public class CacheOptimizedBuildTargetsQueueFactory {
  private static final Logger LOG = Logger.get(CacheOptimizedBuildTargetsQueueFactory.class);

  private final BuildRuleResolver resolver;
  private final ArtifactCacheByBuildRule artifactCache;
  private final RuleDepsCache ruleDepsCache;
  private final boolean isDeepBuild;
  private final boolean shouldBuildSelectedTargetsLocally;

  private class GraphTraversalData {
    Map<String, Set<String>> allReverseDeps = new HashMap<>();
    Map<String, Set<String>> allForwardDeps = new HashMap<>();
    Set<BuildRule> visitedRules = new HashSet<>();
    Set<BuildRule> prunedRules = new HashSet<>();
    Set<String> uncachableTargets = new HashSet<>();
    Set<String> buildLocallyTargets = new HashSet<>();
  }

  public CacheOptimizedBuildTargetsQueueFactory(
      BuildRuleResolver resolver,
      ArtifactCacheByBuildRule artifactCache,
      boolean isDeepRemoteBuild,
      RuleDepsCache ruleDepsCache,
      boolean shouldBuildSelectedTargetsLocally) {
    this.resolver = resolver;
    this.artifactCache = artifactCache;
    this.isDeepBuild = isDeepRemoteBuild;
    this.ruleDepsCache = ruleDepsCache;
    this.shouldBuildSelectedTargetsLocally = shouldBuildSelectedTargetsLocally;

    if (isDeepBuild) {
      LOG.info("Deep build requested. Will not prune BuildTargetsQueue using the remote cache.");
    }
  }

  private boolean doesRuleNeedToBeScheduled(BuildRule rule) {
    if (isDeepBuild || !rule.isCacheable()) {
      // We need to schedule all uncachables in order to parse the nodes below them.
      return true;
    }

    boolean canBeSkipped = artifactCache.localContains(rule) || artifactCache.remoteContains(rule);
    if (canBeSkipped && hasMissingCachableRuntimeDeps(rule)) {
      canBeSkipped = false;
      LOG.verbose(
          "Target [%s] is present in the cache, but still needs to be scheduled, "
              + "because some of its cachable runtime dependencies are missing.",
          ruleToTarget(rule));
      // TODO (alisdair, shivanker): Ideally we should not be scheduling `rule`, but only the
      // missing cachable runtime deps themselves, as direct dependencies of the parent rule.
      // Because otherwise, we might have a scenario where the some uncachable runtime deps get
      // built twice on different minions because of scheduling `rule` on its own.
    } else if (canBeSkipped) {
      LOG.verbose(
          "Target [%s] can be skipped because it is already present in the cache.",
          ruleToTarget(rule));
    }
    return !canBeSkipped;
  }

  private Queue<BuildRule> processTopLevelTargets(Iterable<BuildTarget> targetsToBuild) {
    return RichStream.from(targetsToBuild)
        .map(resolver::getRule)
        .collect(Collectors.toCollection(LinkedList::new));
  }

  private void prewarmRemoteCache(Set<BuildRule> rules) {
    if (!isDeepBuild) {
      artifactCache.prewarmRemoteContains(
          rules.stream().filter(BuildRule::isCacheable).collect(ImmutableSet.toImmutableSet()));
    }
  }

  private boolean hasMissingCachableRuntimeDeps(BuildRule rule) {
    prewarmRemoteCache(ruleDepsCache.getRuntimeDeps(rule));
    Stream<BuildRule> missingCachableRuntimeDeps =
        ruleDepsCache
            .getRuntimeDeps(rule)
            .stream()
            .filter(
                dependency ->
                    dependency.isCacheable()
                        && !artifactCache.remoteContains(dependency)
                        && !artifactCache.localContains(dependency));
    return missingCachableRuntimeDeps.count() > 0;
  }

  private void uploadRuleIfRequired(BuildRule rule) {
    String targetName = ruleToTarget(rule);
    if (rule.isCacheable()
        && !artifactCache.remoteContains(rule)
        && artifactCache.localContains(rule)) {
      // Let's upload this rule.
      LOG.debug(
          "Uploading target [%s] because it is present in the local cache, but not in the "
              + "remote cache.",
          targetName);
      try {
        artifactCache.uploadFromLocal(rule);
      } catch (IOException e) {
        LOG.error(e, "Failed to upload target [%s]", targetName);
        throw new RuntimeException(e);
      }
    }
  }

  private void uploadRuleAndRuntimeDeps(BuildRule rule) {
    uploadRuleIfRequired(rule);
    ruleDepsCache.getRuntimeDeps(rule).forEach(this::uploadRuleIfRequired);
  }

  private GraphTraversalData traverseActionGraph(Queue<BuildRule> buildRulesToProcess) {
    GraphTraversalData results = new GraphTraversalData();
    results.visitedRules.addAll(buildRulesToProcess);

    while (!buildRulesToProcess.isEmpty()) {
      BuildRule rule = buildRulesToProcess.remove();
      String target = ruleToTarget(rule);

      if (!rule.isCacheable()) {
        results.uncachableTargets.add(target);
      }

      if (shouldBuildSelectedTargetsLocally && rule.shouldBuildLocally()) {
        results.buildLocallyTargets.add(target);
      }

      results.allForwardDeps.put(target, new HashSet<>());

      // Get all build dependencies (regular and runtime)
      ImmutableSet<BuildRule> allDeps = ImmutableSet.copyOf(ruleDepsCache.get(rule));

      for (BuildRule dependencyRule : allDeps) {
        // Uploads need to happen regardless of something needs to be scheduled or not.
        // If it is not scheduled, distributed build must be planning to use it.
        // If it is scheduled and we have it locally, distributed build is going to benefit from it.
        // But to avoid the possible additional cost of rule-key calculation, we should check if we
        // even have any local cache or not.
        if (artifactCache.isLocalCachePresent()) {
          uploadRuleAndRuntimeDeps(dependencyRule);
        }

        if (!doesRuleNeedToBeScheduled(dependencyRule)) {
          // TODO(shivanker): Re-distribute new found sub-tree of work if contains returned true,
          // but actual fetch failed. Since multi-contains is only best-effort, it might turn out
          // that when we finally need to fetch this target, it's missing. Then the slave who is
          // supposed to build it will end up building the whole sub-tree locally. Ideally, we
          // should be able to re-distribute this new-found chunk of work.
          continue;
        }

        String dependencyTarget = ruleToTarget(dependencyRule);
        if (!results.allReverseDeps.containsKey(dependencyTarget)) {
          results.allReverseDeps.put(dependencyTarget, new HashSet<>());
        }
        Objects.requireNonNull(results.allReverseDeps.get(dependencyTarget)).add(target);
        Objects.requireNonNull(results.allForwardDeps.get(target)).add(dependencyTarget);

        if (!results.visitedRules.contains(dependencyRule)) {
          results.visitedRules.add(dependencyRule);
          buildRulesToProcess.add(dependencyRule);
        }
      }
    }

    return results;
  }

  private GraphTraversalData traverseGraphFromTopLevelUsingAvailableCaches(
      Iterable<BuildTarget> topLevelTargets) {
    // Start with a set of every node in the graph
    LOG.debug("Recording all rules.");
    Set<BuildRule> allRules = findAllRulesInGraph(topLevelTargets);

    LOG.debug("Processing top-level targets.");
    Queue<BuildRule> buildRulesToProcess = processTopLevelTargets(topLevelTargets);

    if (!buildRulesToProcess.isEmpty() && !isDeepBuild) {
      // Check the cache for everything we are going to need upfront.
      LOG.debug("Pre-warming remote cache contains for all known rules.");
      prewarmRemoteCache(allRules);
    }
    LOG.debug("Traversing %d top-level targets now.", buildRulesToProcess.size());
    GraphTraversalData graphTraversalData = traverseActionGraph(buildRulesToProcess);

    // Now remove the nodes that will be scheduled from set of all nodes, to find the pruned ones.
    graphTraversalData.prunedRules.addAll(allRules);
    graphTraversalData.prunedRules.removeAll(graphTraversalData.visitedRules);

    return graphTraversalData;
  }

  private Set<BuildRule> findAllRulesInGraph(Iterable<BuildTarget> topLevelTargets) {
    Set<BuildRule> allRules = new HashSet<>();

    Queue<BuildRule> rulesToProcess =
        RichStream.from(topLevelTargets)
            .map(resolver::getRule)
            .collect(Collectors.toCollection(LinkedList::new));

    while (!rulesToProcess.isEmpty()) {
      BuildRule buildRule = rulesToProcess.remove();

      if (allRules.contains(buildRule)) {
        continue;
      }
      allRules.add(buildRule);

      rulesToProcess.addAll(ruleDepsCache.get(buildRule));
    }

    return allRules;
  }

  private Set<String> findTransitiveBuildLocallyTargets(GraphTraversalData graphData) {
    Set<String> transitiveBuildLocallyTargets = new HashSet<>(graphData.buildLocallyTargets);
    Queue<String> targetsToProcess = Queues.newArrayDeque(graphData.buildLocallyTargets);
    while (!targetsToProcess.isEmpty()) {
      String target = targetsToProcess.remove();
      if (graphData.allReverseDeps.containsKey(target)) {
        for (String revDep : graphData.allReverseDeps.get(target)) {
          if (transitiveBuildLocallyTargets.add(revDep)) {
            targetsToProcess.add(revDep);
          }
        }
      }
    }

    return transitiveBuildLocallyTargets;
  }

  /**
   * Upload the smallest set of cachable {@link BuildRule}s from the dir-cache, which can help the
   * remote servers in finishing the build faster.
   *
   * @param targetsToBuild Top-level targets which this build needs to optimize for.
   * @param clientStatsTracker For tracking some timing/perf metrics for the Stampede client.
   * @return Future to track the progress of the uploads.
   */
  public ListenableFuture<?> uploadCriticalNodesFromLocalCache(
      Iterable<BuildTarget> targetsToBuild, ClientStatsTracker clientStatsTracker) {
    clientStatsTracker.startTimer(
        ClientStatsTracker.DistBuildClientStat.LOCAL_UPLOAD_FROM_DIR_CACHE);

    traverseGraphFromTopLevelUsingAvailableCaches(targetsToBuild);
    return Futures.transform(
        Futures.allAsList(artifactCache.getAllUploadRuleFutures()),
        results -> {
          clientStatsTracker.stopTimer(
              ClientStatsTracker.DistBuildClientStat.LOCAL_UPLOAD_FROM_DIR_CACHE);
          clientStatsTracker.setMissingRulesUploadedFromDirCacheCount(results.size());
          return null;
        },
        MoreExecutors.directExecutor());
  }

  /**
   * Create {@link BuildTargetsQueue} with the given parameters.
   *
   * @param targetsToBuild top-level targets that need to be built.
   * @return an instance of {@link BuildTargetsQueue} with the top-level targets at the root.
   */
  public ReverseDepBuildTargetsQueue createBuildTargetsQueue(
      Iterable<BuildTarget> targetsToBuild,
      CoordinatorBuildRuleEventsPublisher coordinatorBuildRuleEventsPublisher,
      int mostBuildRulesFinishedPercentageThreshold) {
    LOG.info("Starting to create the %s.", BuildTargetsQueue.class.getName());
    GraphTraversalData results = traverseGraphFromTopLevelUsingAvailableCaches(targetsToBuild);

    // Notify distributed build clients that they should not wait for any of the nodes that were
    // pruned (as they will never be built remotely)
    ImmutableList<String> prunedTargets =
        ImmutableList.copyOf(
            results
                .prunedRules
                .stream()
                .filter(BuildRule::isCacheable) // Client always skips uncacheables
                .map(BuildRule::getFullyQualifiedName)
                .collect(Collectors.toList()));

    int numTotalCachableRules =
        results.visitedRules.size() - results.uncachableTargets.size() + prunedTargets.size();
    LOG.info(
        String.format(
            "[%d/%d] cacheable build rules were pruned from graph.",
            prunedTargets.size(), numTotalCachableRules));
    coordinatorBuildRuleEventsPublisher.createBuildRuleStartedEvents(prunedTargets);
    coordinatorBuildRuleEventsPublisher.createBuildRuleCompletionEvents(prunedTargets);

    if (shouldBuildSelectedTargetsLocally) {
      // Consider all (transitively) 'buildLocally' rules as uncachable for DistBuild purposes - we
      // cannot build them remotely and, hence, we cannot put them in cache for local client to
      // consume.
      // NOTE: this needs to be after uncacheability property is used for graph nodes visiting (and,
      // hence, pruning and scheduling) - we want caches to be checked for these rules while doing
      // the visiting (local build could have uploaded artifacts for these rules).
      ImmutableList<String> transitiveBuildLocallyTargets =
          ImmutableList.copyOf(findTransitiveBuildLocallyTargets(results));
      results.uncachableTargets.addAll(transitiveBuildLocallyTargets);
      // Unlock all rules which will not be built remotely so that local client does not get stuck
      // waiting for them (some of them may be cachable from client point of view). DO NOT use
      // completed/finished events as we are building deps of these rules remotely.
      coordinatorBuildRuleEventsPublisher.createBuildRuleUnlockedEvents(
          transitiveBuildLocallyTargets);
    }

    // Do the reference counting and create the EnqueuedTargets.
    ImmutableSet.Builder<DistributableNode> zeroDependencyNodes = ImmutableSet.builder();
    ImmutableMap.Builder<String, DistributableNode> allNodes = ImmutableMap.builder();
    for (BuildRule buildRule : results.visitedRules) {
      String target = buildRule.getFullyQualifiedName();
      Iterable<String> currentRevDeps;
      if (results.allReverseDeps.containsKey(target)) {
        currentRevDeps = results.allReverseDeps.get(target);
      } else {
        currentRevDeps = new ArrayList<>();
      }

      DistributableNode distributableNode =
          new DistributableNode(
              target,
              ImmutableSet.copyOf(currentRevDeps),
              ImmutableSet.copyOf(Objects.requireNonNull(results.allForwardDeps.get(target))),
              results.uncachableTargets.contains(target));
      allNodes.put(target, distributableNode);

      if (distributableNode.areAllDependenciesResolved()) {
        zeroDependencyNodes.add(distributableNode);
      }
    }

    // Wait for local uploads (in case of local coordinator) to finish.
    try {
      LOG.info("Waiting for cache uploads to finish.");
      Futures.allAsList(artifactCache.getAllUploadRuleFutures()).get();
    } catch (InterruptedException | ExecutionException e) {
      LOG.error(e, "Failed to upload artifacts from the local cache.");
    }

    return new ReverseDepBuildTargetsQueue(
        new DistributableBuildGraph(allNodes.build(), zeroDependencyNodes.build()),
        mostBuildRulesFinishedPercentageThreshold);
  }

  private static String ruleToTarget(BuildRule rule) {
    return rule.getFullyQualifiedName();
  }
}
