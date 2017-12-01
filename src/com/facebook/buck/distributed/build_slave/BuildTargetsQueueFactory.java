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

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.distributed.build_slave.BuildTargetsQueue.EnqueuedTarget;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.ParallelRuleKeyCalculator;
import com.facebook.buck.rules.RuleDepsCache;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyConfiguration;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Factory class for {@link BuildTargetsQueue}. */
public class BuildTargetsQueueFactory {
  private static final Logger LOG = Logger.get(BuildTargetsQueueFactory.class);
  // TODO(shivanker): Make this configurable.
  private static final int MAX_RULEKEYS_IN_MULTI_CONTAINS_REQUEST = 5000;

  private final AtomicLong queryCreationTotalTimeMs = new AtomicLong(0);
  private final BuildRuleResolver resolver;
  private final BuckEventBus eventBus;
  private final ListeningExecutorService executorService;
  private final ArtifactCache remoteCache;
  private final SourcePathRuleFinder ruleFinder;
  private final ParallelRuleKeyCalculator<RuleKey> ruleKeyCalculator;
  private final Map<BuildRule, ListenableFuture<Boolean>> remoteCacheContainsFutures;
  private final boolean isDeepBuild;

  public BuildTargetsQueueFactory(
      BuildRuleResolver resolver,
      ListeningExecutorService executorService,
      boolean isDeepRemoteBuild,
      ArtifactCache remoteCache,
      BuckEventBus eventBus,
      FileHashCache fileHashCache,
      RuleKeyConfiguration rkConfigForCache,
      Optional<ParallelRuleKeyCalculator<RuleKey>> optionalRuleKeyCalculator) {
    this.resolver = resolver;
    this.eventBus = eventBus;
    this.isDeepBuild = isDeepRemoteBuild;
    this.executorService = executorService;
    this.remoteCache = remoteCache;

    this.remoteCacheContainsFutures = new ConcurrentHashMap<>();

    this.ruleFinder = new SourcePathRuleFinder(resolver);
    this.ruleKeyCalculator =
        optionalRuleKeyCalculator.orElseGet(
            () ->
                new ParallelRuleKeyCalculator<>(
                    executorService,
                    new DefaultRuleKeyFactory(
                        new RuleKeyFieldLoader(rkConfigForCache),
                        fileHashCache,
                        DefaultSourcePathResolver.from(ruleFinder),
                        ruleFinder),
                    new RuleDepsCache(resolver),
                    (buckEventBus, rule) -> () -> {}));
    if (isDeepBuild) {
      LOG.info("Deep build requested. Will not prune BuildTargetsQueue using the remote cache.");
    }
  }

  private ListenableFuture<Map<RuleKey, CacheResult>> multiContainsAsync(List<RuleKey> ruleKeys) {
    List<ListenableFuture<ImmutableMap<RuleKey, CacheResult>>> requestResultsFutures =
        new ArrayList<>(ruleKeys.size() / MAX_RULEKEYS_IN_MULTI_CONTAINS_REQUEST + 1);

    while (ruleKeys.size() > 0) {
      int numKeysInCurrentRequest =
          Math.min(MAX_RULEKEYS_IN_MULTI_CONTAINS_REQUEST, ruleKeys.size());
      LOG.verbose("Making multi-contains request for [%d] rulekeys.", numKeysInCurrentRequest);
      requestResultsFutures.add(
          remoteCache.multiContainsAsync(
              ImmutableSet.copyOf(ruleKeys.subList(0, numKeysInCurrentRequest))));
      ruleKeys = ruleKeys.subList(numKeysInCurrentRequest, ruleKeys.size());
    }

    return Futures.transform(
        Futures.allAsList(requestResultsFutures),
        (List<Map<RuleKey, CacheResult>> requestResults) -> {
          Map<RuleKey, CacheResult> allResults = new HashMap<>();
          for (Map<RuleKey, CacheResult> partResults : requestResults) {
            allResults.putAll(partResults);
          }
          return allResults;
        },
        MoreExecutors.directExecutor());
  }

  private void makeCacheMultiContainsQueryIfNecessary(Set<BuildRule> rulesToBeChecked) {
    Stopwatch stopwatch = Stopwatch.createStarted();
    Set<BuildRule> unseenRules =
        rulesToBeChecked
            .stream()
            .filter(rule -> !remoteCacheContainsFutures.containsKey(rule))
            .collect(Collectors.toSet());

    if (isDeepBuild) {
      // We want to build everything - so we can avoid the rule key calculation and cache queries,
      // and treat everything as a miss.
      remoteCacheContainsFutures.putAll(
          Maps.asMap(unseenRules, rule -> Futures.immediateFuture(false)));
    } else {
      LOG.debug(
          "Checking remote cache for [%d] new rules out of [%d] total rules.",
          unseenRules.size(), rulesToBeChecked.size());
      Map<BuildRule, ListenableFuture<RuleKey>> rulesToKeys =
          Maps.asMap(unseenRules, rule -> ruleKeyCalculator.calculate(eventBus, rule));

      ListenableFuture<Map<RuleKey, CacheResult>> keysToCacheResultFuture =
          Futures.transformAsync(
              Futures.allAsList(rulesToKeys.values()), this::multiContainsAsync, executorService);

      Map<BuildRule, ListenableFuture<Boolean>> containsResultsForUnseenRules =
          Maps.asMap(
              unseenRules,
              rule ->
                  Futures.transform(
                      keysToCacheResultFuture,
                      keysToCacheResult ->
                          Preconditions.checkNotNull(
                                  keysToCacheResult.get(
                                      Futures.getUnchecked(rulesToKeys.get(rule))))
                              .getType()
                              .isSuccess(),
                      MoreExecutors.directExecutor()));

      remoteCacheContainsFutures.putAll(containsResultsForUnseenRules);
    }

    queryCreationTotalTimeMs.addAndGet(stopwatch.elapsed(TimeUnit.MILLISECONDS));
  }

  private boolean isBuildRuleInRemoteCache(BuildRule buildRule) {
    if (!remoteCacheContainsFutures.containsKey(buildRule)) {
      makeCacheMultiContainsQueryIfNecessary(ImmutableSet.of(buildRule));
    }
    return Futures.getUnchecked(remoteCacheContainsFutures.get(buildRule));
  }

  public static BuildTargetsQueue newEmptyQueue() {
    return new BuildTargetsQueue(new ArrayList<>(), new HashMap<>());
  }

  private Queue<BuildRule> processTopLevelTargets(
      Iterable<BuildTarget> targetsToBuild, Set<String> visitedTargets) {
    makeCacheMultiContainsQueryIfNecessary(
        RichStream.from(targetsToBuild).map(resolver::getRule).collect(Collectors.toSet()));

    return RichStream.from(targetsToBuild)
        .map(resolver::getRule)
        .filter(
            rule -> {
              boolean needToBuild = !isBuildRuleInRemoteCache(rule);
              if (needToBuild) {
                visitedTargets.add(ruleToTarget(rule));
              } else {
                LOG.debug(
                    "Skipping top-level target [%s] because it is already present in the cache."
                        + " Will create an empty BuildTargetsQueue.",
                    ruleToTarget(rule));
              }
              return needToBuild;
            })
        .collect(Collectors.toCollection(LinkedList::new));
  }

  private void traverseActionGraph(
      Queue<BuildRule> buildRulesToProcess,
      Map<String, Set<String>> allReverseDeps,
      Map<String, Set<String>> allForwardDeps,
      Set<String> visitedTargets) {
    while (!buildRulesToProcess.isEmpty()) {
      BuildRule rule = buildRulesToProcess.remove();

      String target = ruleToTarget(rule);
      allForwardDeps.put(target, new HashSet<>());

      ImmutableSortedSet.Builder<BuildRule> allDependencies = ImmutableSortedSet.naturalOrder();

      // Get all standard build dependencies
      allDependencies.addAll(rule.getBuildDeps());

      // Optionally add in any run-time deps
      if (rule instanceof HasRuntimeDeps) {
        LOG.debug(String.format("[%s] has runtime deps", ruleToTarget(rule)));

        Stream<BuildTarget> runtimeDepPaths = ((HasRuntimeDeps) rule).getRuntimeDeps(ruleFinder);
        ImmutableSet<BuildRule> runtimeDeps =
            resolver.getAllRules(runtimeDepPaths.collect(ImmutableSet.toImmutableSet()));

        allDependencies.addAll(runtimeDeps);
      }

      ImmutableSet<BuildRule> allDeps = allDependencies.build();
      for (BuildRule dependencyRule : allDeps) {
        String dependencyTarget = ruleToTarget(dependencyRule);
        if (isBuildRuleInRemoteCache(dependencyRule)) {
          // TODO(shivanker): Re-distribute new found sub-tree of work if contains returned true,
          // but actual fetch failed. Since multi-contains is only best-effort, it might turn out
          // that when we finally need to fetch this target, it's missing. Then the slave who is
          // supposed to build it will end up building the whole sub-tree locally. Ideally, we
          // should be able to re-distribute this new-found chunk of work.
          LOG.verbose(
              "Skipping target [%s] because it is already present in the remote cache.",
              dependencyTarget);
          continue;
        }

        if (!allReverseDeps.containsKey(dependencyTarget)) {
          allReverseDeps.put(dependencyTarget, new HashSet<>());
        }
        Preconditions.checkNotNull(allReverseDeps.get(dependencyTarget)).add(target);
        Preconditions.checkNotNull(allForwardDeps.get(target)).add(dependencyTarget);

        if (!visitedTargets.contains(dependencyTarget)) {
          visitedTargets.add(dependencyTarget);
          buildRulesToProcess.add(dependencyRule);
        }
      }
    }
  }

  /**
   * Create {@link BuildTargetsQueue} with the given parameters.
   *
   * @param targetsToBuild top-level targets that need to be built.
   * @return an instance of {@link BuildTargetsQueue} with the top-level targets at the root.
   */
  public BuildTargetsQueue newQueue(Iterable<BuildTarget> targetsToBuild) {
    // Build the reverse dependency graph by traversing the action graph Top-Down.
    Map<String, Set<String>> allReverseDeps = new HashMap<>();
    Map<String, Set<String>> allForwardDeps = new HashMap<>();
    Set<String> visitedTargets = new HashSet<>();

    Queue<BuildRule> buildRulesToProcess = processTopLevelTargets(targetsToBuild, visitedTargets);

    LOG.debug("Traversing %d top-level targets now.", buildRulesToProcess.size());

    if (!buildRulesToProcess.isEmpty()) {
      // Check the cache for everything we are going to need upfront.
      makeCacheMultiContainsQueryIfNecessary(
          ruleKeyCalculator
              .getAllKnownTargets()
              .stream()
              .map(resolver::getRule)
              .collect(Collectors.toSet()));
    }

    traverseActionGraph(buildRulesToProcess, allReverseDeps, allForwardDeps, visitedTargets);
    logCacheContainsStats();

    // Do the reference counting and create the EnqueuedTargets.
    List<EnqueuedTarget> zeroDependencyTargets = new ArrayList<>();
    Map<String, EnqueuedTarget> allEnqueuedTargets = new HashMap<>();
    for (String target : visitedTargets) {
      Iterable<String> currentRevDeps = null;
      if (allReverseDeps.containsKey(target)) {
        currentRevDeps = allReverseDeps.get(target);
      } else {
        currentRevDeps = new ArrayList<>();
      }

      EnqueuedTarget enqueuedTarget =
          new EnqueuedTarget(
              target,
              ImmutableList.copyOf(currentRevDeps),
              Preconditions.checkNotNull(allForwardDeps.get(target)).size(),
              ImmutableSet.copyOf(allForwardDeps.get(target)));
      allEnqueuedTargets.put(target, enqueuedTarget);

      if (enqueuedTarget.areAllDependenciesResolved()) {
        zeroDependencyTargets.add(enqueuedTarget);
      }
    }

    return new BuildTargetsQueue(zeroDependencyTargets, allEnqueuedTargets);
  }

  private void logCacheContainsStats() {
    ListenableFuture<List<Boolean>> allResultsFuture =
        Futures.allAsList(remoteCacheContainsFutures.values());
    allResultsFuture.addListener(
        () -> {
          List<Boolean> allResults = Futures.getUnchecked(allResultsFuture);
          LOG.debug(
              "Hit [%d out of %d] targets checked in the remote cache. "
                  + "[%d ms] was spent on generating the futures for multi-contains requests.",
              allResults.stream().filter(r -> r).count(),
              allResults.stream().count(),
              queryCreationTotalTimeMs.get());
        },
        MoreExecutors.directExecutor());
  }

  private static String ruleToTarget(BuildRule rule) {
    return rule.getFullyQualifiedName();
  }
}
