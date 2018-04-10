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

import static com.facebook.buck.distributed.thrift.MinionType.LOW_SPEC;
import static com.facebook.buck.distributed.thrift.MinionType.STANDARD_SPEC;

import com.facebook.buck.distributed.NoopArtifactCacheByBuildRule;
import com.facebook.buck.distributed.testutil.CustomBuildRuleResolverFactory;
import com.facebook.buck.distributed.thrift.MinionType;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.distributed.thrift.WorkUnit;
import com.facebook.buck.event.listener.NoOpCoordinatorBuildRuleEventsPublisher;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.RuleDepsCache;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class MinionWorkloadAllocatorTest {

  private static final String MINION_ONE = "Super minion 1";
  private static final String MINION_TWO = "Super minion 2";
  private static final String MINION_THREE = "Super minion 3";
  private static final String MINION_FOUR = "Super minion 4";
  private static final int MAX_WORK_UNITS = 10;
  private static final int MOST_BUILD_RULES_FINISHED_PERCENTAGE = 100;
  private static final StampedeId STAMPEDE_ID = new StampedeId().setId("DUMMY_ID");

  private BuildTargetsQueue createQueueUsingResolver(BuildRuleResolver resolver) {
    BuildTarget target = BuildTargetFactory.newInstance(CustomBuildRuleResolverFactory.ROOT_TARGET);
    BuildTargetsQueue queue =
        new CacheOptimizedBuildTargetsQueueFactory(
                resolver, new NoopArtifactCacheByBuildRule(), false, new RuleDepsCache(resolver))
            .createBuildTargetsQueue(
                ImmutableList.of(target),
                new NoOpCoordinatorBuildRuleEventsPublisher(),
                MOST_BUILD_RULES_FINISHED_PERCENTAGE);

    return queue;
  }

  @Test
  public void testMultipleMinionFailuresRecoveredFromInMixedEnvironment() {
    MinionWorkloadAllocator allocator =
        new MinionWorkloadAllocator(
            createQueueUsingResolver(
                CustomBuildRuleResolverFactory.createDiamondDependencyResolverWithChainFromLeaf()),
            new DistBuildTraceTracker(STAMPEDE_ID));

    // Allocate work unit with 2 targets to low-speced minion one
    allocateWorkAndAssert(
        allocator,
        MINION_ONE,
        LOW_SPEC,
        ImmutableList.of(),
        ImmutableList.of(
            ImmutableList.of(
                CustomBuildRuleResolverFactory.LEAF_TARGET,
                CustomBuildRuleResolverFactory.CHAIN_TOP_TARGET)));

    // Simulate failure of minion one
    simulateAndAssertMinionFailure(allocator, MINION_ONE);

    // Try to re-allocate to low-speced minion two, which should be skipped as failed nodes
    // should always go to the most powerful hardware.
    allocateWorkAndAssert(allocator, MINION_TWO, LOW_SPEC, ImmutableList.of(), ImmutableList.of());

    // Try to re-allocate to standard-speced minion one, which should pickup the work.
    allocateWorkAndAssert(
        allocator,
        MINION_THREE,
        STANDARD_SPEC,
        ImmutableList.of(),
        ImmutableList.of(
            ImmutableList.of(
                CustomBuildRuleResolverFactory.LEAF_TARGET,
                CustomBuildRuleResolverFactory.CHAIN_TOP_TARGET)));

    // Minion 3 completes nodes and gets more work
    List<WorkUnit> minionTwoWorkFromRequestTwo =
        allocator.dequeueZeroDependencyNodes(
            MINION_THREE,
            STANDARD_SPEC,
            ImmutableList.of(
                CustomBuildRuleResolverFactory.LEAF_TARGET,
                CustomBuildRuleResolverFactory.CHAIN_TOP_TARGET),
            MAX_WORK_UNITS);
    Assert.assertEquals(2, minionTwoWorkFromRequestTwo.size());
  }

  @Test
  public void testMultipleMinionFailuresRecoveredFrom() {
    MinionWorkloadAllocator allocator =
        new MinionWorkloadAllocator(
            createQueueUsingResolver(
                CustomBuildRuleResolverFactory.createDiamondDependencyResolverWithChainFromLeaf()),
            new DistBuildTraceTracker(STAMPEDE_ID));

    // Allocate work unit with 2 targets to minion one
    allocateWorkAndAssert(
        allocator,
        MINION_ONE,
        STANDARD_SPEC,
        ImmutableList.of(),
        ImmutableList.of(
            ImmutableList.of(
                CustomBuildRuleResolverFactory.LEAF_TARGET,
                CustomBuildRuleResolverFactory.CHAIN_TOP_TARGET)));

    // Simulate failure of minion one
    simulateAndAssertMinionFailure(allocator, MINION_ONE);

    // Re-allocate work unit from failed minion one to minion two
    allocateWorkAndAssert(
        allocator,
        MINION_TWO,
        STANDARD_SPEC,
        ImmutableList.of(),
        ImmutableList.of(
            ImmutableList.of(
                CustomBuildRuleResolverFactory.LEAF_TARGET,
                CustomBuildRuleResolverFactory.CHAIN_TOP_TARGET)));

    // Minion 2 completes the first item in the work unit it was given. Doesn't get anything new
    allocateWorkAndAssert(
        allocator,
        MINION_TWO,
        STANDARD_SPEC,
        ImmutableList.of(CustomBuildRuleResolverFactory.LEAF_TARGET),
        ImmutableList.of());

    // Simulate failure of minion two.
    simulateAndAssertMinionFailure(allocator, MINION_TWO);

    // Re-allocate *remaining work in* work unit from failed minion two to minion three
    allocateWorkAndAssert(
        allocator,
        MINION_THREE,
        STANDARD_SPEC,
        ImmutableList.of(),
        ImmutableList.of(ImmutableList.of(CustomBuildRuleResolverFactory.CHAIN_TOP_TARGET)));

    // Minion three completes node at top of chain, and now gets two more work units, left and right
    List<WorkUnit> minionThreeWorkFromRequestTwo =
        allocator.dequeueZeroDependencyNodes(
            MINION_THREE,
            STANDARD_SPEC,
            ImmutableList.of(CustomBuildRuleResolverFactory.CHAIN_TOP_TARGET),
            MAX_WORK_UNITS);
    Assert.assertEquals(2, minionThreeWorkFromRequestTwo.size());

    // Minion three completes left node. right node is still remaining. no new work.
    allocateWorkAndAssert(
        allocator,
        MINION_THREE,
        STANDARD_SPEC,
        ImmutableList.of(CustomBuildRuleResolverFactory.LEFT_TARGET),
        ImmutableList.of());

    // Minion three fails.
    simulateAndAssertMinionFailure(allocator, MINION_THREE);

    // Minion four picks up remaining work unit from minion three. gets work unit with right node
    allocateWorkAndAssert(
        allocator,
        MINION_FOUR,
        STANDARD_SPEC,
        ImmutableList.of(),
        ImmutableList.of(ImmutableList.of(CustomBuildRuleResolverFactory.RIGHT_TARGET)));

    // Minion four completes right node. gets final root node back
    allocateWorkAndAssert(
        allocator,
        MINION_FOUR,
        STANDARD_SPEC,
        ImmutableList.of(CustomBuildRuleResolverFactory.RIGHT_TARGET),
        ImmutableList.of(ImmutableList.of(CustomBuildRuleResolverFactory.ROOT_TARGET)));

    // Minion four completes the build
    List<WorkUnit> minionFourWorkFromRequestThree =
        allocator.dequeueZeroDependencyNodes(
            MINION_FOUR,
            STANDARD_SPEC,
            ImmutableList.of(CustomBuildRuleResolverFactory.ROOT_TARGET),
            MAX_WORK_UNITS);
    Assert.assertEquals(0, minionFourWorkFromRequestThree.size());
    Assert.assertTrue(allocator.isBuildFinished());
  }

  @Test
  public void testNormalBuildFlow() {
    MinionWorkloadAllocator allocator =
        new MinionWorkloadAllocator(
            createQueueUsingResolver(
                CustomBuildRuleResolverFactory.createDiamondDependencyResolver()),
            new DistBuildTraceTracker(STAMPEDE_ID));
    Assert.assertFalse(allocator.isBuildFinished());

    List<WorkUnit> firstTargets =
        allocator.dequeueZeroDependencyNodes(
            MINION_ONE, STANDARD_SPEC, ImmutableList.of(), MAX_WORK_UNITS);
    Assert.assertEquals(1, firstTargets.size());
    Assert.assertFalse(allocator.isBuildFinished());

    List<WorkUnit> secondTargets =
        allocator.dequeueZeroDependencyNodes(
            MINION_ONE,
            STANDARD_SPEC,
            ImmutableList.of(CustomBuildRuleResolverFactory.LEAF_TARGET),
            MAX_WORK_UNITS);
    Assert.assertEquals(2, secondTargets.size());
    Assert.assertFalse(allocator.isBuildFinished());

    List<WorkUnit> thirdTargets =
        allocator.dequeueZeroDependencyNodes(
            MINION_ONE,
            STANDARD_SPEC,
            ImmutableList.of(
                CustomBuildRuleResolverFactory.LEFT_TARGET,
                CustomBuildRuleResolverFactory.RIGHT_TARGET),
            MAX_WORK_UNITS);
    Assert.assertEquals(1, thirdTargets.size());
    Assert.assertFalse(allocator.isBuildFinished());

    List<WorkUnit> fourthTargets =
        allocator.dequeueZeroDependencyNodes(
            MINION_ONE,
            STANDARD_SPEC,
            ImmutableList.of(CustomBuildRuleResolverFactory.ROOT_TARGET),
            MAX_WORK_UNITS);
    Assert.assertEquals(0, fourthTargets.size());
    Assert.assertTrue(allocator.isBuildFinished());
  }

  private static void simulateAndAssertMinionFailure(
      MinionWorkloadAllocator allocator, String minionId) {
    allocator.handleMinionFailure(minionId);
    Assert.assertFalse(allocator.isBuildFinished());
    Assert.assertTrue(allocator.hasMinionFailed(minionId));
  }

  private static void allocateWorkAndAssert(
      MinionWorkloadAllocator allocator,
      String minionId,
      MinionType minionType,
      ImmutableList<String> finishedNodes,
      ImmutableList<ImmutableList<String>> expectedWorkUnits) {

    Assert.assertFalse(allocator.hasMinionFailed(minionId));
    List<WorkUnit> minionOneWorkUnits =
        allocator.dequeueZeroDependencyNodes(minionId, minionType, finishedNodes, MAX_WORK_UNITS);
    Assert.assertEquals(expectedWorkUnits.size(), minionOneWorkUnits.size());

    for (int workUnitIndex = 0; workUnitIndex < expectedWorkUnits.size(); workUnitIndex++) {
      ImmutableList<String> expectedWorkUnit = expectedWorkUnits.get(workUnitIndex);
      List<String> actualWorkUnit = minionOneWorkUnits.get(workUnitIndex).getBuildTargets();

      Assert.assertEquals(
          String.format(
              "Expected work unit of length [%d] at index [%d]",
              expectedWorkUnit.size(), workUnitIndex),
          expectedWorkUnit.size(),
          actualWorkUnit.size());

      for (int workUnitNodeIndex = 0;
          workUnitNodeIndex < actualWorkUnit.size();
          workUnitNodeIndex++) {
        String expectedNode = expectedWorkUnit.get(0);
        String actualNode = actualWorkUnit.get(0);

        Assert.assertEquals(expectedNode, actualNode);
      }
    }
  }
}
