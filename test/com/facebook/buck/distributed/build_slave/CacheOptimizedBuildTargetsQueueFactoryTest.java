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

import static com.facebook.buck.distributed.testutil.CustomActiongGraphBuilderFactory.CACHABLE_A;
import static com.facebook.buck.distributed.testutil.CustomActiongGraphBuilderFactory.CACHABLE_B;
import static com.facebook.buck.distributed.testutil.CustomActiongGraphBuilderFactory.CACHABLE_BUILD_LOCALLY_A;
import static com.facebook.buck.distributed.testutil.CustomActiongGraphBuilderFactory.CACHABLE_C;
import static com.facebook.buck.distributed.testutil.CustomActiongGraphBuilderFactory.CACHABLE_D;
import static com.facebook.buck.distributed.testutil.CustomActiongGraphBuilderFactory.CHAIN_TOP_TARGET;
import static com.facebook.buck.distributed.testutil.CustomActiongGraphBuilderFactory.LEAF_TARGET;
import static com.facebook.buck.distributed.testutil.CustomActiongGraphBuilderFactory.LEFT_TARGET;
import static com.facebook.buck.distributed.testutil.CustomActiongGraphBuilderFactory.RIGHT_TARGET;
import static com.facebook.buck.distributed.testutil.CustomActiongGraphBuilderFactory.ROOT_TARGET;
import static com.facebook.buck.distributed.testutil.CustomActiongGraphBuilderFactory.UNCACHABLE_A;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import com.facebook.buck.core.build.engine.impl.DefaultRuleDepsCache;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.distributed.ArtifactCacheByBuildRule;
import com.facebook.buck.distributed.testutil.CustomActiongGraphBuilderFactory;
import com.facebook.buck.distributed.testutil.DummyArtifactCacheByBuildRule;
import com.facebook.buck.distributed.thrift.WorkUnit;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Futures;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class CacheOptimizedBuildTargetsQueueFactoryTest {
  public static final int MOST_BUILD_RULES_FINISHED_PERCENTAGE = 100;

  private ArtifactCacheByBuildRule artifactCache;
  private CoordinatorBuildRuleEventsPublisher ruleFinishedPublisher;

  @Before
  public void setUp() {
    this.artifactCache = null;
    this.ruleFinishedPublisher = EasyMock.createNiceMock(CoordinatorBuildRuleEventsPublisher.class);
  }

  private BuildTargetsQueue createQueueWithLocalCacheHits(
      BuildRuleResolver resolver,
      Iterable<BuildTarget> topLevelTargets,
      List<BuildTarget> localCacheHitTargets) {
    artifactCache =
        new DummyArtifactCacheByBuildRule(
            ImmutableList.of(),
            localCacheHitTargets.stream().map(resolver::getRule).collect(Collectors.toList()));

    return new CacheOptimizedBuildTargetsQueueFactory(
            resolver, artifactCache, false, new DefaultRuleDepsCache(resolver), false)
        .createBuildTargetsQueue(
            topLevelTargets, ruleFinishedPublisher, MOST_BUILD_RULES_FINISHED_PERCENTAGE);
  }

  private BuildTargetsQueue createQueueWithRemoteCacheHits(
      BuildRuleResolver resolver,
      Iterable<BuildTarget> topLevelTargets,
      List<BuildTarget> remoteCacheHitTargets,
      boolean shouldBuildSelectedTargetsLocally) {

    artifactCache =
        new DummyArtifactCacheByBuildRule(
            remoteCacheHitTargets.stream().map(resolver::getRule).collect(Collectors.toList()),
            ImmutableList.of());

    return new CacheOptimizedBuildTargetsQueueFactory(
            resolver,
            artifactCache,
            false,
            new DefaultRuleDepsCache(resolver),
            shouldBuildSelectedTargetsLocally)
        .createBuildTargetsQueue(
            topLevelTargets, ruleFinishedPublisher, MOST_BUILD_RULES_FINISHED_PERCENTAGE);
  }

  @Test
  public void testGraphWithCacheableAndUncachableRuntimeDepsForRemoteHitPruning()
      throws NoSuchBuildTargetException {
    // Graph structure:
    //                        uncacheable_a (runtime)
    //                      /
    //       +- right (hit)-
    //       |              \
    // root -+               leaf (hit)
    //       |              /
    //       +- left (hit) -
    //                      \
    //                        {uncacheable_b (runtime), cacheable_c (runtime miss)}

    Capture<ImmutableList<String>> startedEventsCapture = Capture.newInstance();
    ruleFinishedPublisher.createBuildRuleStartedEvents(capture(startedEventsCapture));
    expectLastCall();

    Capture<ImmutableList<String>> completedEventsCapture = Capture.newInstance();
    ruleFinishedPublisher.createBuildRuleCompletionEvents(capture(completedEventsCapture));
    expectLastCall();

    replay(ruleFinishedPublisher);

    BuildRuleResolver resolver =
        CustomActiongGraphBuilderFactory.createGraphWithUncacheableRuntimeDeps();
    BuildTarget rootTarget = BuildTargetFactory.newInstance(ROOT_TARGET);
    ImmutableList<BuildTarget> hitTargets =
        ImmutableList.of(
            BuildTargetFactory.newInstance(LEFT_TARGET),
            BuildTargetFactory.newInstance(RIGHT_TARGET),
            BuildTargetFactory.newInstance(LEAF_TARGET));
    BuildTargetsQueue queue =
        createQueueWithRemoteCacheHits(resolver, ImmutableList.of(rootTarget), hitTargets, false);

    List<WorkUnit> zeroDepTargets = ReverseDepBuildTargetsQueueTest.dequeueNoFinishedTargets(queue);
    Assert.assertEquals(1, zeroDepTargets.size());
    Assert.assertEquals(3, zeroDepTargets.get(0).getBuildTargets().size());

    // We should get a single chain of root <- left <- cacheable_c.
    Assert.assertEquals(CACHABLE_C, zeroDepTargets.get(0).getBuildTargets().get(0));
    Assert.assertEquals(LEFT_TARGET, zeroDepTargets.get(0).getBuildTargets().get(1));
    Assert.assertEquals(ROOT_TARGET, zeroDepTargets.get(0).getBuildTargets().get(2));

    List<WorkUnit> newZeroDepNodes =
        queue.dequeueZeroDependencyNodes(
            ImmutableList.of(CACHABLE_C, LEFT_TARGET, ROOT_TARGET),
            ReverseDepBuildTargetsQueueTest.MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, newZeroDepNodes.size());

    // LEAF_TARGET and RIGHT_TARGET were pruned, so should have corresponding
    // started and completed events
    verify(ruleFinishedPublisher);
    Assert.assertEquals(1, startedEventsCapture.getValues().size());
    Set<String> startedEvents = Sets.newHashSet(startedEventsCapture.getValues().get(0));
    Assert.assertTrue(startedEvents.contains(LEAF_TARGET));
    Assert.assertTrue(startedEvents.contains(RIGHT_TARGET));

    Assert.assertEquals(1, completedEventsCapture.getValues().size());
    Set<String> completedEvents = Sets.newHashSet(completedEventsCapture.getValues().get(0));
    Assert.assertTrue(completedEvents.contains(LEAF_TARGET));
    Assert.assertTrue(completedEvents.contains(RIGHT_TARGET));
  }

  @Test
  public void testTopLevelTargetWithCacheableRuntimeDepsIsNotSkipped()
      throws NoSuchBuildTargetException {
    BuildRuleResolver resolver = CustomActiongGraphBuilderFactory.createSimpleRuntimeDepsResolver();
    BuildTarget target =
        BuildTargetFactory.newInstance(CustomActiongGraphBuilderFactory.HAS_RUNTIME_DEP_RULE);
    BuildTargetsQueue queue =
        createQueueWithRemoteCacheHits(
            resolver, ImmutableList.of(target), ImmutableList.of(target), false);
    List<WorkUnit> zeroDepTargets = ReverseDepBuildTargetsQueueTest.dequeueNoFinishedTargets(queue);
    Assert.assertEquals(1, zeroDepTargets.size());
    Assert.assertEquals(2, zeroDepTargets.get(0).getBuildTargets().size());

    // has_runtime_dep -> transitive_dep both form a chain, so should be returned as a work unit.
    Assert.assertEquals(
        CustomActiongGraphBuilderFactory.TRANSITIVE_DEP_RULE,
        zeroDepTargets.get(0).getBuildTargets().get(0));
    Assert.assertEquals(
        CustomActiongGraphBuilderFactory.HAS_RUNTIME_DEP_RULE,
        zeroDepTargets.get(0).getBuildTargets().get(1));
  }

  @Test
  public void testDiamondDependencyGraphWithRemoteCacheHits() throws NoSuchBuildTargetException {
    // Graph structure:
    //               / right (hit) \
    // root (miss) -                 - chain top (miss) - chain bottom (hit)
    //              \ left (miss) /

    BuildRuleResolver resolver =
        CustomActiongGraphBuilderFactory.createDiamondDependencyBuilderWithChainFromLeaf();
    BuildTarget rootTarget = BuildTargetFactory.newInstance(ROOT_TARGET);
    BuildTarget rightTarget = BuildTargetFactory.newInstance(RIGHT_TARGET);
    BuildTarget leafTarget = BuildTargetFactory.newInstance(LEAF_TARGET);

    BuildTargetsQueue queue =
        createQueueWithRemoteCacheHits(
            resolver,
            ImmutableList.of(rootTarget),
            ImmutableList.of(rightTarget, leafTarget),
            false);

    List<WorkUnit> zeroDepWorkUnits =
        ReverseDepBuildTargetsQueueTest.dequeueNoFinishedTargets(queue);
    Assert.assertEquals(1, zeroDepWorkUnits.size());
    WorkUnit workUnit = zeroDepWorkUnits.get(0);
    List<String> targets = workUnit.getBuildTargets();
    Assert.assertEquals(3, targets.size());
    Assert.assertEquals(CHAIN_TOP_TARGET, targets.get(0));
    Assert.assertEquals(LEFT_TARGET, targets.get(1));
    Assert.assertEquals(ROOT_TARGET, targets.get(2));

    zeroDepWorkUnits =
        queue.dequeueZeroDependencyNodes(
            targets, ReverseDepBuildTargetsQueueTest.MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, zeroDepWorkUnits.size());
  }

  @Test
  public void testGraphWithTopLevelCacheHit() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver = CustomActiongGraphBuilderFactory.createDiamondDependencyGraph();
    BuildTarget root = BuildTargetFactory.newInstance(ROOT_TARGET);
    BuildTarget left = BuildTargetFactory.newInstance(LEFT_TARGET);
    BuildTarget right = BuildTargetFactory.newInstance(RIGHT_TARGET);
    BuildTarget leaf = BuildTargetFactory.newInstance(LEAF_TARGET);
    BuildTargetsQueue queue =
        createQueueWithRemoteCacheHits(
            resolver, ImmutableList.of(root), ImmutableList.of(root, left, right, leaf), false);
    List<WorkUnit> zeroDepTargets = ReverseDepBuildTargetsQueueTest.dequeueNoFinishedTargets(queue);
    Assert.assertEquals(1, zeroDepTargets.size());
  }

  @Test
  public void testUploadCriticalNodesFromLocalCache() {
    // Graph structure:
    //               / right (hit) \
    // root (miss) -                 - chain top (miss) - chain bottom (hit)
    //              \ left (miss) /

    BuildRuleResolver resolver =
        CustomActiongGraphBuilderFactory.createDiamondDependencyBuilderWithChainFromLeaf();
    BuildTarget rootTarget = BuildTargetFactory.newInstance(ROOT_TARGET);
    BuildTarget rightTarget = BuildTargetFactory.newInstance(RIGHT_TARGET);
    BuildTarget leafTarget = BuildTargetFactory.newInstance(LEAF_TARGET);

    ImmutableList<BuildTarget> localHitTargets = ImmutableList.of(rightTarget, leafTarget);
    BuildTargetsQueue queue =
        createQueueWithLocalCacheHits(resolver, ImmutableList.of(rootTarget), localHitTargets);

    List<WorkUnit> zeroDepWorkUnits =
        ReverseDepBuildTargetsQueueTest.dequeueNoFinishedTargets(queue);
    Assert.assertEquals(1, zeroDepWorkUnits.size());
    WorkUnit workUnit = zeroDepWorkUnits.get(0);
    List<String> targets = workUnit.getBuildTargets();
    Assert.assertEquals(3, targets.size());
    Assert.assertEquals(CHAIN_TOP_TARGET, targets.get(0));
    Assert.assertEquals(LEFT_TARGET, targets.get(1));
    Assert.assertEquals(ROOT_TARGET, targets.get(2));

    zeroDepWorkUnits =
        queue.dequeueZeroDependencyNodes(
            targets, ReverseDepBuildTargetsQueueTest.MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, zeroDepWorkUnits.size());

    Assert.assertEquals(
        ImmutableSet.copyOf(localHitTargets),
        artifactCache
            .getAllUploadRuleFutures()
            .stream()
            .map(Futures::getUnchecked)
            .map(BuildRule::getBuildTarget)
            .collect(ImmutableSet.toImmutableSet()));
  }

  @Test
  @Ignore // TODO(shivanker): make this test pass.
  public void testGraphWithMissingRuntimeDepsForLocalHitUploads()
      throws NoSuchBuildTargetException {
    // Graph structure:
    //                        uncacheable_a (runtime)
    //                      /
    //       +- right (hit)-
    //       |              \
    // root -+               leaf (hit)
    //       |              /
    //       +- left (hit) -
    //                      \
    //                        {uncacheable_b (runtime), cacheable_c (runtime)}
    BuildRuleResolver resolver =
        CustomActiongGraphBuilderFactory.createGraphWithUncacheableRuntimeDeps();
    BuildTarget rootTarget = BuildTargetFactory.newInstance(ROOT_TARGET);
    BuildTarget leftTarget = BuildTargetFactory.newInstance(LEFT_TARGET);
    BuildTarget rightTarget = BuildTargetFactory.newInstance(RIGHT_TARGET);
    BuildTarget leafTarget = BuildTargetFactory.newInstance(LEAF_TARGET);

    ImmutableList<BuildTarget> hitTargets = ImmutableList.of(leftTarget, rightTarget, leafTarget);
    BuildTargetsQueue queue =
        createQueueWithLocalCacheHits(resolver, ImmutableList.of(rootTarget), hitTargets);

    List<WorkUnit> zeroDepTargets = ReverseDepBuildTargetsQueueTest.dequeueNoFinishedTargets(queue);
    Assert.assertEquals(1, zeroDepTargets.size());
    Assert.assertEquals(3, zeroDepTargets.get(0).getBuildTargets().size());

    // We should get a single chain of root <- left <- cacheable_c.
    Assert.assertEquals(CACHABLE_C, zeroDepTargets.get(0).getBuildTargets().get(0));
    Assert.assertEquals(LEFT_TARGET, zeroDepTargets.get(0).getBuildTargets().get(1));
    Assert.assertEquals(ROOT_TARGET, zeroDepTargets.get(0).getBuildTargets().get(2));

    List<WorkUnit> newZeroDepNodes =
        queue.dequeueZeroDependencyNodes(
            ImmutableList.of(CACHABLE_C, LEFT_TARGET, ROOT_TARGET),
            ReverseDepBuildTargetsQueueTest.MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, newZeroDepNodes.size());

    Assert.assertEquals(
        ImmutableSet.of(leftTarget, rightTarget),
        artifactCache
            .getAllUploadRuleFutures()
            .stream()
            .map(Futures::getUnchecked)
            .map(BuildRule::getBuildTarget)
            .collect(ImmutableSet.toImmutableSet()));
  }

  @Test
  public void testGraphWithLocallyCachedRuntimeDepsForLocalHitUploads()
      throws NoSuchBuildTargetException {
    // Graph structure:
    //                        uncacheable_a (runtime)
    //                      /
    //       +- right (hit)-
    //       |              \
    // root -+               leaf (hit)
    //       |              /
    //       +- left (hit) -
    //                      \
    //                        {uncacheable_b (runtime), cacheable_c (runtime, hit)}
    BuildRuleResolver resolver =
        CustomActiongGraphBuilderFactory.createGraphWithUncacheableRuntimeDeps();
    BuildTarget rootTarget = BuildTargetFactory.newInstance(ROOT_TARGET);
    BuildTarget leftTarget = BuildTargetFactory.newInstance(LEFT_TARGET);
    BuildTarget rightTarget = BuildTargetFactory.newInstance(RIGHT_TARGET);
    BuildTarget leafTarget = BuildTargetFactory.newInstance(LEAF_TARGET);
    BuildTarget cachableTargetC = BuildTargetFactory.newInstance(CACHABLE_C);

    ImmutableList<BuildTarget> hitTargets =
        ImmutableList.of(leftTarget, rightTarget, leafTarget, cachableTargetC);
    BuildTargetsQueue queue =
        createQueueWithLocalCacheHits(resolver, ImmutableList.of(rootTarget), hitTargets);

    List<WorkUnit> zeroDepTargets = ReverseDepBuildTargetsQueueTest.dequeueNoFinishedTargets(queue);
    Assert.assertEquals(1, zeroDepTargets.size());
    Assert.assertEquals(1, zeroDepTargets.get(0).getBuildTargets().size());

    // We should just build the root remotely.
    Assert.assertEquals(ROOT_TARGET, zeroDepTargets.get(0).getBuildTargets().get(0));

    List<WorkUnit> newZeroDepNodes =
        queue.dequeueZeroDependencyNodes(
            ImmutableList.of(ROOT_TARGET), ReverseDepBuildTargetsQueueTest.MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, newZeroDepNodes.size());

    // But we should still be uploading cachable c, because without that, fetching left would
    // trigger a build of c.
    Assert.assertEquals(
        ImmutableSet.of(leftTarget, rightTarget, cachableTargetC),
        artifactCache
            .getAllUploadRuleFutures()
            .stream()
            .map(Futures::getUnchecked)
            .map(BuildRule::getBuildTarget)
            .collect(ImmutableSet.toImmutableSet()));
  }

  @Test
  public void testGraphWithBuildLocallyDep() throws NoSuchBuildTargetException {
    // Graph structure:
    // cacheable_a - cacheable_b - - - - - - - - - build_locally_a - cacheable_d
    //                           \               /
    //                            uncachaeable_a
    //                           /
    //               cacheable_c
    BuildRuleResolver resolver = CustomActiongGraphBuilderFactory.createGraphWithBuildLocallyDep();

    BuildTarget cacheableB = BuildTargetFactory.newInstance(CACHABLE_B);
    BuildTarget cacheableC = BuildTargetFactory.newInstance(CACHABLE_C);

    // Make sure count of cacheable/uncacheable with building locally disabled is as expected.
    BuildTargetsQueue queue =
        createQueueWithRemoteCacheHits(
            resolver, ImmutableList.of(cacheableB, cacheableC), ImmutableList.of(), false);
    Assert.assertEquals(4, queue.getDistributableBuildGraph().getNumberOfCacheableNodes());

    // Check enabling of building locally marks correct rules as uncacheable and sends "unlocked"
    // events for them.
    Capture<ImmutableList<String>> unlockedEventsCapture = Capture.newInstance();
    ruleFinishedPublisher.createBuildRuleUnlockedEvents(capture(unlockedEventsCapture));
    expectLastCall();
    replay(ruleFinishedPublisher);

    queue =
        createQueueWithRemoteCacheHits(
            resolver, ImmutableList.of(cacheableB, cacheableC), ImmutableList.of(), true);
    Assert.assertEquals(1, queue.getDistributableBuildGraph().getNumberOfCacheableNodes());
    Assert.assertFalse(queue.getDistributableBuildGraph().getNode(CACHABLE_D).isUncacheable());
    verify(ruleFinishedPublisher);
    Set<String> unlockedEvents = Sets.newHashSet(unlockedEventsCapture.getValues().get(0));
    Assert.assertEquals(4, unlockedEvents.size());
    Assert.assertTrue(
        unlockedEvents.containsAll(
            Lists.newArrayList(CACHABLE_B, CACHABLE_C, UNCACHABLE_A, CACHABLE_BUILD_LOCALLY_A)));

    BuildTarget cacheableA = BuildTargetFactory.newInstance(CACHABLE_A);

    // Make sure it does not interfere with checking caches for artifacts (marking the (transitive)
    // "buildLocally" rules as uncacheable should happen only after visiting the graph and checking
    // caches).
    queue =
        createQueueWithRemoteCacheHits(
            resolver, ImmutableList.of(cacheableA), ImmutableList.of(cacheableA, cacheableB), true);
    Assert.assertEquals(1, queue.getDistributableBuildGraph().size());
  }
}
