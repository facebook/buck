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

import com.facebook.buck.distributed.ArtifactCacheByBuildRule;
import com.facebook.buck.distributed.ClientStatsTracker;
import com.facebook.buck.distributed.build_slave.BuildTargetsQueue.EnqueuedTarget;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
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
  private final SourcePathRuleFinder ruleFinder;
  private final ArtifactCacheByBuildRule artifactCache;
  private final boolean isDeepBuild;

  private class GraphTraversalData {
    Map<String, Set<String>> allReverseDeps = new HashMap<>();
    Map<String, Set<String>> allForwardDeps = new HashMap<>();
    Set<BuildRule> visitedRules = new HashSet<>();
    Set<BuildRule> prunedRules = new HashSet<>();
    Set<String> uncachableTargets = new HashSet<>();
    Set<EnqueuedTarget> unachableZeroDependencyTargets = new HashSet<>();
  }

  public CacheOptimizedBuildTargetsQueueFactory(
      BuildRuleResolver resolver,
      ArtifactCacheByBuildRule artifactCache,
      boolean isDeepRemoteBuild) {
    this.resolver = resolver;
    this.artifactCache = artifactCache;
    this.isDeepBuild = isDeepRemoteBuild;

    this.ruleFinder = new SourcePathRuleFinder(resolver);
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
    if (!isDeepBuild) {
      artifactCache.prewarmRemoteContains(
          RichStream.from(targetsToBuild)
              .map(resolver::getRule)
              .collect(ImmutableSet.toImmutableSet()));
    }

    return RichStream.from(targetsToBuild)
        .map(resolver::getRule)
        .filter(this::doesRuleNeedToBeScheduled)
        .collect(Collectors.toCollection(LinkedList::new));
  }

  private Stream<BuildRule> getRuntimeDeps(BuildRule rule) {
    if (!(rule instanceof HasRuntimeDeps)) {
      return Stream.<BuildRule>builder().build();
    }

    Stream<BuildTarget> runtimeDepPaths = ((HasRuntimeDeps) rule).getRuntimeDeps(ruleFinder);
    return resolver.getAllRulesStream(runtimeDepPaths.collect(ImmutableSet.toImmutableSet()));
  }

  private boolean hasMissingCachableRuntimeDeps(BuildRule rule) {
    Stream<BuildRule> missingCachableRuntimeDeps =
        getRuntimeDeps(rule)
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
    getRuntimeDeps(rule).forEach(this::uploadRuleIfRequired);
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

      results.allForwardDeps.put(target, new HashSet<>());

      ImmutableSortedSet.Builder<BuildRule> allDependencies = ImmutableSortedSet.naturalOrder();

      // Get all standard build dependencies
      allDependencies.addAll(rule.getBuildDeps());

      // Optionally add in any run-time deps
      if (rule instanceof HasRuntimeDeps) {
        LOG.verbose(String.format("[%s] has runtime deps", ruleToTarget(rule)));
        allDependencies.addAll(getRuntimeDeps(rule).collect(Collectors.toList()));
      }

      ImmutableSet<BuildRule> allDeps = allDependencies.build();
      for (BuildRule dependencyRule : allDeps) {
        String dependencyTarget = ruleToTarget(dependencyRule);

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

        if (!results.allReverseDeps.containsKey(dependencyTarget)) {
          results.allReverseDeps.put(dependencyTarget, new HashSet<>());
        }
        Preconditions.checkNotNull(results.allReverseDeps.get(dependencyTarget)).add(target);
        Preconditions.checkNotNull(results.allForwardDeps.get(target)).add(dependencyTarget);

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
    Set<BuildRule> prunedRules = findAllRulesInGraph(topLevelTargets);

    Queue<BuildRule> buildRulesToProcess = processTopLevelTargets(topLevelTargets);

    if (!buildRulesToProcess.isEmpty() && !isDeepBuild) {
      // Check the cache for everything we are going to need upfront.
      artifactCache.prewarmRemoteContainsForAllKnownRules();
    }
    LOG.debug("Traversing %d top-level targets now.", buildRulesToProcess.size());
    GraphTraversalData graphTraversalData = traverseActionGraph(buildRulesToProcess);

    // Now remove the nodes that will be scheduled from set of all nodes, to find the pruned ones.
    prunedRules.removeAll(graphTraversalData.visitedRules);
    graphTraversalData.prunedRules.addAll(prunedRules);

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

      rulesToProcess.addAll(buildRule.getBuildDeps());
      rulesToProcess.addAll(getRuntimeDeps(buildRule).collect(Collectors.toList()));
    }

    return allRules;
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
  public BuildTargetsQueue createBuildTargetsQueue(
      Iterable<BuildTarget> targetsToBuild,
      CoordinatorBuildRuleEventsPublisher coordinatorBuildRuleEventsPublisher,
      int mostBuildRulesFinishedPercentageThreshold) {
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

    LOG.info(
        String.format("[%d] cacheable build rules were pruned from graph.", prunedTargets.size()));
    coordinatorBuildRuleEventsPublisher.createBuildRuleStartedEvents(prunedTargets);
    coordinatorBuildRuleEventsPublisher.createBuildRuleCompletionEvents(prunedTargets);

    // Do the reference counting and create the EnqueuedTargets.
    List<EnqueuedTarget> zeroDependencyTargets = new ArrayList<>();
    Map<String, EnqueuedTarget> allEnqueuedTargets = new HashMap<>();
    for (BuildRule buildRule : results.visitedRules) {
      String target = buildRule.getFullyQualifiedName();
      Iterable<String> currentRevDeps;
      if (results.allReverseDeps.containsKey(target)) {
        currentRevDeps = results.allReverseDeps.get(target);
      } else {
        currentRevDeps = new ArrayList<>();
      }

      EnqueuedTarget enqueuedTarget =
          new EnqueuedTarget(
              target,
              ImmutableList.copyOf(currentRevDeps),
              Preconditions.checkNotNull(results.allForwardDeps.get(target)).size(),
              ImmutableSet.copyOf(results.allForwardDeps.get(target)),
              results.uncachableTargets.contains(target));
      allEnqueuedTargets.put(target, enqueuedTarget);

      if (enqueuedTarget.areAllDependenciesResolved()) {
        zeroDependencyTargets.add(enqueuedTarget);
        if (enqueuedTarget.isUncachable()) {
          results.unachableZeroDependencyTargets.add(enqueuedTarget);
        }
      }
    }

    // Wait for local uploads (in case of local coordinator) to finish.
    try {
      Futures.allAsList(artifactCache.getAllUploadRuleFutures()).get();
    } catch (InterruptedException | ExecutionException e) {
      LOG.error(e, "Failed to upload artifacts from the local cache.");
    }

    return new BuildTargetsQueue(
        zeroDependencyTargets,
        allEnqueuedTargets,
        results.unachableZeroDependencyTargets,
        mostBuildRulesFinishedPercentageThreshold);
  }

  private static String ruleToTarget(BuildRule rule) {
    return rule.getFullyQualifiedName();
  }
}
