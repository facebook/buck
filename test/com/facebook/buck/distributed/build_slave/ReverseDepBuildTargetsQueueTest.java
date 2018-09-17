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

import static com.facebook.buck.distributed.testutil.CustomActionGraphBuilderFactory.CACHABLE_A;
import static com.facebook.buck.distributed.testutil.CustomActionGraphBuilderFactory.CACHABLE_B;
import static com.facebook.buck.distributed.testutil.CustomActionGraphBuilderFactory.CACHABLE_C;
import static com.facebook.buck.distributed.testutil.CustomActionGraphBuilderFactory.CHAIN_TOP_TARGET;
import static com.facebook.buck.distributed.testutil.CustomActionGraphBuilderFactory.LEAF_TARGET;
import static com.facebook.buck.distributed.testutil.CustomActionGraphBuilderFactory.LEFT_TARGET;
import static com.facebook.buck.distributed.testutil.CustomActionGraphBuilderFactory.RIGHT_TARGET;
import static com.facebook.buck.distributed.testutil.CustomActionGraphBuilderFactory.ROOT_TARGET;
import static com.facebook.buck.distributed.testutil.CustomActionGraphBuilderFactory.UNCACHABLE_ROOT;
import static com.facebook.buck.distributed.testutil.CustomActionGraphBuilderFactory.createBuildGraphWithInterleavedUncacheables;
import static com.facebook.buck.distributed.testutil.CustomActionGraphBuilderFactory.createBuildGraphWithUncachableLeaf;

import com.facebook.buck.core.build.engine.impl.DefaultRuleDepsCache;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.distributed.ArtifactCacheByBuildRule;
import com.facebook.buck.distributed.NoopArtifactCacheByBuildRule;
import com.facebook.buck.distributed.testutil.CustomActionGraphBuilderFactory;
import com.facebook.buck.distributed.thrift.CoordinatorBuildProgress;
import com.facebook.buck.distributed.thrift.WorkUnit;
import com.facebook.buck.event.listener.NoOpCoordinatorBuildRuleEventsPublisher;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ReverseDepBuildTargetsQueueTest {

  public static final int MAX_UNITS_OF_WORK = 10;
  public static final int MOST_BUILD_RULES_FINISHED_PERCENTAGE = 50;
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private static ReverseDepBuildTargetsQueue createQueueWithoutRemoteCache(
      BuildRuleResolver resolver, Iterable<BuildTarget> topLevelTargets) {
    return new CacheOptimizedBuildTargetsQueueFactory(
            resolver,
            new NoopArtifactCacheByBuildRule(),
            false,
            new DefaultRuleDepsCache(resolver),
            false)
        .createBuildTargetsQueue(
            topLevelTargets,
            new NoOpCoordinatorBuildRuleEventsPublisher(),
            MOST_BUILD_RULES_FINISHED_PERCENTAGE);
  }

  @Test
  public void testEmptyQueue() {
    ReverseDepBuildTargetsQueue queue = ReverseDepBuildTargetsQueue.newEmptyQueue();
    List<WorkUnit> zeroDepTargets =
        queue.dequeueZeroDependencyNodes(ImmutableList.of(), MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, zeroDepTargets.size());
    Assert.assertEquals(
        new CoordinatorBuildProgress()
            .setTotalRulesCount(0)
            .setBuiltRulesCount(0)
            .setSkippedRulesCount(0),
        queue.getBuildProgress());
  }

  public static List<WorkUnit> dequeueNoFinishedTargets(BuildTargetsQueue queue) {
    return queue.dequeueZeroDependencyNodes(ImmutableList.of(), MAX_UNITS_OF_WORK);
  }

  @Test
  public void testResolverWithoutAnyTargets() {
    BuildRuleResolver resolver = new TestActionGraphBuilder();
    BuildTargetsQueue queue = createQueueWithoutRemoteCache(resolver, ImmutableList.of());
    List<WorkUnit> zeroDepTargets = dequeueNoFinishedTargets(queue);
    Assert.assertEquals(0, zeroDepTargets.size());
    Assert.assertTrue(queue.haveMostBuildRulesFinished());
  }

  @Test
  public void testResolverWithOnSingleTarget() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver = CustomActionGraphBuilderFactory.createSimpleBuilder();
    BuildTarget target = BuildTargetFactory.newInstance(ROOT_TARGET);
    BuildTargetsQueue queue = createQueueWithoutRemoteCache(resolver, ImmutableList.of(target));
    List<WorkUnit> zeroDepTargets = dequeueNoFinishedTargets(queue);
    Assert.assertEquals(1, zeroDepTargets.size());
    Assert.assertEquals(1, zeroDepTargets.get(0).getBuildTargets().size());
    Assert.assertEquals("//foo:one", zeroDepTargets.get(0).getBuildTargets().get(0));

    Assert.assertEquals(0, dequeueNoFinishedTargets(queue).size());
    Assert.assertFalse(queue.haveMostBuildRulesFinished());
    Assert.assertEquals(
        new CoordinatorBuildProgress()
            .setTotalRulesCount(1)
            .setBuiltRulesCount(0)
            .setSkippedRulesCount(0),
        queue.getBuildProgress());

    Assert.assertEquals(
        0,
        queue
            .dequeueZeroDependencyNodes(
                ImmutableList.of(target.getFullyQualifiedName()), MAX_UNITS_OF_WORK)
            .size());
    Assert.assertTrue(queue.haveMostBuildRulesFinished());
    Assert.assertEquals(
        new CoordinatorBuildProgress()
            .setTotalRulesCount(1)
            .setBuiltRulesCount(1)
            .setSkippedRulesCount(0),
        queue.getBuildProgress());
  }

  @Test
  public void testResolverWithTargetThatHasRuntimeDep() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver = CustomActionGraphBuilderFactory.createSimpleRuntimeDepsResolver();
    BuildTarget target =
        BuildTargetFactory.newInstance(CustomActionGraphBuilderFactory.HAS_RUNTIME_DEP_RULE);
    BuildTargetsQueue queue = createQueueWithoutRemoteCache(resolver, ImmutableList.of(target));
    List<WorkUnit> zeroDepTargets = dequeueNoFinishedTargets(queue);
    Assert.assertEquals(1, zeroDepTargets.size());
    Assert.assertEquals(2, zeroDepTargets.get(0).getBuildTargets().size());

    // has_runtime_dep -> transitive_dep both form a chain, so should be returned as a work unit.
    Assert.assertEquals(
        CustomActionGraphBuilderFactory.TRANSITIVE_DEP_RULE,
        zeroDepTargets.get(0).getBuildTargets().get(0));
    Assert.assertEquals(
        CustomActionGraphBuilderFactory.HAS_RUNTIME_DEP_RULE,
        zeroDepTargets.get(0).getBuildTargets().get(1));

    List<WorkUnit> newZeroDepNodes =
        queue.dequeueZeroDependencyNodes(
            ImmutableList.of(CustomActionGraphBuilderFactory.TRANSITIVE_DEP_RULE),
            MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, newZeroDepNodes.size());

    List<WorkUnit> newZeroDepNodesTwo =
        queue.dequeueZeroDependencyNodes(
            ImmutableList.of(CustomActionGraphBuilderFactory.HAS_RUNTIME_DEP_RULE),
            MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, newZeroDepNodesTwo.size());
  }

  @Test
  public void testResolverWithDiamondDependencyTarget() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver = CustomActionGraphBuilderFactory.createDiamondDependencyGraph();
    BuildTarget target = BuildTargetFactory.newInstance(ROOT_TARGET);
    BuildTargetsQueue queue = createQueueWithoutRemoteCache(resolver, ImmutableList.of(target));

    List<WorkUnit> zeroDepWorkUnits = dequeueNoFinishedTargets(queue);
    Assert.assertEquals(1, zeroDepWorkUnits.size());
    WorkUnit leafNodeWorkUnit = zeroDepWorkUnits.get(0);
    List<String> leafNodeTargets = leafNodeWorkUnit.getBuildTargets();
    Assert.assertEquals(1, leafNodeTargets.size());
    Assert.assertEquals(LEAF_TARGET, leafNodeTargets.get(0));

    zeroDepWorkUnits = queue.dequeueZeroDependencyNodes(leafNodeTargets, MAX_UNITS_OF_WORK);
    Assert.assertEquals(2, zeroDepWorkUnits.size());

    WorkUnit leftNodeWorkUnit = new WorkUnit();
    leftNodeWorkUnit.setBuildTargets(ImmutableList.of(LEFT_TARGET));

    WorkUnit rightNodeWorkUnit = new WorkUnit();
    rightNodeWorkUnit.setBuildTargets(ImmutableList.of(RIGHT_TARGET));

    Assert.assertTrue(zeroDepWorkUnits.contains(leftNodeWorkUnit));
    Assert.assertTrue(zeroDepWorkUnits.contains(rightNodeWorkUnit));

    List<String> middleNodeTargets = ImmutableList.of(LEFT_TARGET, RIGHT_TARGET);

    zeroDepWorkUnits = queue.dequeueZeroDependencyNodes(middleNodeTargets, MAX_UNITS_OF_WORK);

    Assert.assertEquals(1, zeroDepWorkUnits.size());
    WorkUnit rootWorkUnit = zeroDepWorkUnits.get(0);
    Assert.assertEquals(1, rootWorkUnit.getBuildTargets().size());
    Assert.assertEquals(ROOT_TARGET, rootWorkUnit.getBuildTargets().get(0));

    zeroDepWorkUnits =
        queue.dequeueZeroDependencyNodes(rootWorkUnit.getBuildTargets(), MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, zeroDepWorkUnits.size());
  }

  @Test
  public void testGraphWithUncachableLeaf() throws NoSuchBuildTargetException {
    // Graph structure
    //  cacheable_a -> uncacheable_b
    // Analysis:
    // - uncacheable_b can be skipped, and go straight to building cacheable_a

    BuildRuleResolver resolver = createBuildGraphWithUncachableLeaf();
    BuildTarget target = BuildTargetFactory.newInstance(CACHABLE_A);
    BuildTargetsQueue queue = createQueueWithoutRemoteCache(resolver, ImmutableList.of(target));

    List<WorkUnit> zeroDepWorkUnits = dequeueNoFinishedTargets(queue);
    Assert.assertEquals(1, zeroDepWorkUnits.size());
    WorkUnit workUnit = zeroDepWorkUnits.get(0);
    List<String> workUnitTargets = workUnit.getBuildTargets();
    Assert.assertEquals(1, workUnitTargets.size());
    Assert.assertEquals(CACHABLE_A, workUnitTargets.get(0));

    // Mark CACHABLE_A as completed. There should be nothing else to do
    zeroDepWorkUnits =
        queue.dequeueZeroDependencyNodes(ImmutableList.of(CACHABLE_A), MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, zeroDepWorkUnits.size());
  }

  @Test
  public void testGraphWithInterleavedUncachables() throws NoSuchBuildTargetException {
    // Graph structure
    //                 / uncacheable_a -> uncacheable b \
    // uncacheable_root                                uncacheable_d -> cacheable_b -> uncacheable_e
    // -> cacheable_c
    //                 \ uncacheable_c -> cacheable_a   /
    //
    // Analysis:
    // - first build chain cacheable_b -> cachable_c.
    // -- where uncacheable_e dependency of cacheable_b is implicit
    // - next build cacheable_a
    // Notes:
    // - we never explicitly schedule uncachables.
    // - uncacheable_e will be build implicitly when building cacheable_b
    // - uncacheable_d will be built implicitly when building cacheable_a
    // - the other uncacheables never need to be built remotely => quicker remote step
    // - there are only 3 cacheables, so 'most build rules finished' after 2/3 done, with 50% limit

    BuildRuleResolver resolver = createBuildGraphWithInterleavedUncacheables();
    BuildTarget target = BuildTargetFactory.newInstance(UNCACHABLE_ROOT);
    BuildTargetsQueue queue = createQueueWithoutRemoteCache(resolver, ImmutableList.of(target));

    List<WorkUnit> zeroDepWorkUnits = dequeueNoFinishedTargets(queue);
    Assert.assertEquals(1, zeroDepWorkUnits.size());
    WorkUnit workUnit = zeroDepWorkUnits.get(0);
    List<String> workUnitTargets = workUnit.getBuildTargets();
    Assert.assertEquals(2, workUnitTargets.size());
    Assert.assertEquals(CACHABLE_C, workUnitTargets.get(0));
    Assert.assertEquals(CACHABLE_B, workUnitTargets.get(1));

    // 0/3 cacheables finished
    Assert.assertFalse(queue.haveMostBuildRulesFinished());
    Assert.assertEquals(
        new CoordinatorBuildProgress()
            .setTotalRulesCount(9)
            .setBuiltRulesCount(0)
            .setSkippedRulesCount(0),
        queue.getBuildProgress());

    // Mark CACHABLE_C as completed. Nothing else should become available yet.
    zeroDepWorkUnits =
        queue.dequeueZeroDependencyNodes(ImmutableList.of(CACHABLE_C), MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, zeroDepWorkUnits.size());

    // 1/3 cacheables finished. UNCACHEABLE_E should auto complete.
    Assert.assertFalse(queue.haveMostBuildRulesFinished());
    Assert.assertEquals(
        new CoordinatorBuildProgress()
            .setTotalRulesCount(9)
            .setBuiltRulesCount(1)
            .setSkippedRulesCount(1),
        queue.getBuildProgress());

    // Mark CACHABLE_B as completed. UNCACHEABLE_D,B,A should auto complete,
    // and CACHAEABLE_A should become ready.
    zeroDepWorkUnits =
        queue.dequeueZeroDependencyNodes(ImmutableList.of(CACHABLE_B), MAX_UNITS_OF_WORK);
    Assert.assertEquals(1, zeroDepWorkUnits.size());

    // 2/3 (i.e 66%) cacheables finished
    Assert.assertTrue(queue.haveMostBuildRulesFinished());
    Assert.assertEquals(
        new CoordinatorBuildProgress()
            .setTotalRulesCount(9)
            .setBuiltRulesCount(2)
            .setSkippedRulesCount(4),
        queue.getBuildProgress());

    workUnit = zeroDepWorkUnits.get(0);
    workUnitTargets = workUnit.getBuildTargets();
    Assert.assertEquals(1, workUnitTargets.size());
    Assert.assertEquals(CACHABLE_A, workUnitTargets.get(0));

    // Mark CACHABLE_A as completed. UNCACHEABLE_C should auto complete,
    // and then UNCACHABLE_ROOT should auto complete. There should be no more work
    // and CACHAEABLE_A should become ready.
    zeroDepWorkUnits =
        queue.dequeueZeroDependencyNodes(ImmutableList.of(CACHABLE_A), MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, zeroDepWorkUnits.size());
    Assert.assertEquals(
        new CoordinatorBuildProgress()
            .setTotalRulesCount(9)
            .setBuiltRulesCount(3)
            .setSkippedRulesCount(6),
        queue.getBuildProgress());
  }

  @Test
  public void testDiamondDependencyResolverWithChainFromLeaf() throws NoSuchBuildTargetException {
    // Graph structure:
    //        / right \
    // root -          - chain top - leaf
    //        \ left  /

    BuildRuleResolver resolver =
        CustomActionGraphBuilderFactory.createDiamondDependencyBuilderWithChainFromLeaf();
    BuildTarget target = BuildTargetFactory.newInstance(ROOT_TARGET);
    BuildTargetsQueue queue = createQueueWithoutRemoteCache(resolver, ImmutableList.of(target));

    List<WorkUnit> zeroDepWorkUnits = dequeueNoFinishedTargets(queue);
    Assert.assertEquals(1, zeroDepWorkUnits.size());
    WorkUnit chainWorkUnit = zeroDepWorkUnits.get(0);
    List<String> chainTargets = chainWorkUnit.getBuildTargets();
    Assert.assertEquals(2, chainTargets.size());
    Assert.assertEquals(LEAF_TARGET, chainTargets.get(0));
    Assert.assertEquals(CHAIN_TOP_TARGET, chainTargets.get(1));

    zeroDepWorkUnits = queue.dequeueZeroDependencyNodes(chainTargets, MAX_UNITS_OF_WORK);

    WorkUnit leftNodeWorkUnit = new WorkUnit();
    leftNodeWorkUnit.setBuildTargets(ImmutableList.of(LEFT_TARGET));

    WorkUnit rightNodeWorkUnit = new WorkUnit();
    rightNodeWorkUnit.setBuildTargets(ImmutableList.of(RIGHT_TARGET));

    Assert.assertTrue(zeroDepWorkUnits.contains(leftNodeWorkUnit));
    Assert.assertTrue(zeroDepWorkUnits.contains(rightNodeWorkUnit));

    // Signal the middle nodes separately. The root should only become available once both
    // left and right middle nodes have been signalled.
    zeroDepWorkUnits =
        queue.dequeueZeroDependencyNodes(ImmutableList.of(LEFT_TARGET), MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, zeroDepWorkUnits.size());

    zeroDepWorkUnits =
        queue.dequeueZeroDependencyNodes(ImmutableList.of(RIGHT_TARGET), MAX_UNITS_OF_WORK);

    Assert.assertEquals(1, zeroDepWorkUnits.size());
    WorkUnit rootWorkUnit = zeroDepWorkUnits.get(0);
    Assert.assertEquals(1, rootWorkUnit.getBuildTargets().size());
    Assert.assertEquals(ROOT_TARGET, rootWorkUnit.getBuildTargets().get(0));

    zeroDepWorkUnits =
        queue.dequeueZeroDependencyNodes(rootWorkUnit.getBuildTargets(), MAX_UNITS_OF_WORK);
    Assert.assertEquals(0, zeroDepWorkUnits.size());
  }

  @Test
  public void testDeepBuildDoesNotUseRemoteCacheIfLocalIsNotProvided()
      throws NoSuchBuildTargetException {
    BuildRuleResolver resolver =
        CustomActionGraphBuilderFactory.createDiamondDependencyBuilderWithChainFromLeaf();
    BuildTarget rootTarget = BuildTargetFactory.newInstance(ROOT_TARGET);

    ArtifactCacheByBuildRule artifactCache = EasyMock.createMock(ArtifactCacheByBuildRule.class);
    EasyMock.expect(artifactCache.isLocalCachePresent()).andReturn(false).anyTimes();
    EasyMock.expect(artifactCache.getAllUploadRuleFutures())
        .andReturn(new ArrayList<>())
        .times(0, 1);

    CacheOptimizedBuildTargetsQueueFactory factory =
        new CacheOptimizedBuildTargetsQueueFactory(
            resolver, artifactCache, true, new DefaultRuleDepsCache(resolver), false);

    EasyMock.replay(artifactCache);
    factory.createBuildTargetsQueue(
        ImmutableList.of(rootTarget),
        new NoOpCoordinatorBuildRuleEventsPublisher(),
        MOST_BUILD_RULES_FINISHED_PERCENTAGE);
    EasyMock.verify(artifactCache);
  }

  public static BuildTargetsQueue createDiamondDependencyQueue() throws NoSuchBuildTargetException {
    return createQueueWithoutRemoteCache(
        CustomActionGraphBuilderFactory.createDiamondDependencyGraph(),
        ImmutableList.of(BuildTargetFactory.newInstance(ROOT_TARGET)));
  }

  public static BuildTargetsQueue createDiamondDependencyQueueWithChainFromLeaf()
      throws NoSuchBuildTargetException {
    return createQueueWithoutRemoteCache(
        CustomActionGraphBuilderFactory.createDiamondDependencyBuilderWithChainFromLeaf(),
        ImmutableList.of(BuildTargetFactory.newInstance(ROOT_TARGET)));
  }
}
