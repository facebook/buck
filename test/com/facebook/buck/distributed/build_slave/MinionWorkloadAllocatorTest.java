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

import com.facebook.buck.distributed.NoopArtifactCacheByBuildRule;
import com.facebook.buck.distributed.testutil.CustomBuildRuleResolverFactory;
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
  public void testMultipleMinionFailuresRecoveredFrom() {
    MinionWorkloadAllocator allocator =
        new MinionWorkloadAllocator(
            createQueueUsingResolver(
                CustomBuildRuleResolverFactory.createDiamondDependencyResolverWithChainFromLeaf()),
            new DistBuildTraceTracker(STAMPEDE_ID));

    // Allocate work unit with 2 targets to minion one
    Assert.assertFalse(allocator.hasMinionFailed(MINION_ONE));
    List<WorkUnit> minionOneWorkUnits =
        allocator.dequeueZeroDependencyNodes(MINION_ONE, ImmutableList.of(), MAX_WORK_UNITS);
    Assert.assertEquals(1, minionOneWorkUnits.size());
    List<String> buildTargetsAssignedToMinionOne = minionOneWorkUnits.get(0).getBuildTargets();
    Assert.assertEquals(2, buildTargetsAssignedToMinionOne.size());
    Assert.assertEquals(
        CustomBuildRuleResolverFactory.LEAF_TARGET, buildTargetsAssignedToMinionOne.get(0));
    Assert.assertEquals(
        CustomBuildRuleResolverFactory.CHAIN_TOP_TARGET, buildTargetsAssignedToMinionOne.get(1));

    // Simulate failure of minion one
    allocator.handleMinionFailure(MINION_ONE);
    Assert.assertFalse(allocator.isBuildFinished());
    Assert.assertTrue(allocator.hasMinionFailed(MINION_ONE));

    // Re-allocate work unit from failed minion one to minion two
    Assert.assertFalse(allocator.hasMinionFailed(MINION_TWO));
    List<WorkUnit> minionTwoWorkFromRequestOne =
        allocator.dequeueZeroDependencyNodes(MINION_TWO, ImmutableList.of(), MAX_WORK_UNITS);
    Assert.assertEquals(1, minionTwoWorkFromRequestOne.size());
    List<String> buildTargetsAssignedToMinionTwo =
        minionTwoWorkFromRequestOne.get(0).getBuildTargets();
    Assert.assertEquals(2, buildTargetsAssignedToMinionTwo.size());
    Assert.assertEquals(
        CustomBuildRuleResolverFactory.LEAF_TARGET, buildTargetsAssignedToMinionTwo.get(0));
    Assert.assertEquals(
        CustomBuildRuleResolverFactory.CHAIN_TOP_TARGET, buildTargetsAssignedToMinionTwo.get(1));

    // Minion 2 completes the first item in the work unit it was given. Doesn't get anything new
    List<WorkUnit> minionTwoWorkFromRequestTwo =
        allocator.dequeueZeroDependencyNodes(
            MINION_TWO,
            ImmutableList.of(CustomBuildRuleResolverFactory.LEAF_TARGET),
            MAX_WORK_UNITS);
    Assert.assertEquals(0, minionTwoWorkFromRequestTwo.size());

    // Simulate failure of minion two.
    allocator.handleMinionFailure(MINION_TWO);
    Assert.assertFalse(allocator.isBuildFinished());
    Assert.assertTrue(allocator.hasMinionFailed(MINION_TWO));

    // Re-allocate *remaining work in* work unit from failed minion two to minion three
    Assert.assertFalse(allocator.hasMinionFailed(MINION_THREE));
    List<WorkUnit> minionThreeWorkFromRequestOne =
        allocator.dequeueZeroDependencyNodes(MINION_THREE, ImmutableList.of(), MAX_WORK_UNITS);
    Assert.assertEquals(1, minionThreeWorkFromRequestOne.size());
    List<String> buildTargetsAssignedToMinionThree =
        minionThreeWorkFromRequestOne.get(0).getBuildTargets();
    Assert.assertEquals(1, buildTargetsAssignedToMinionThree.size());
    Assert.assertEquals(
        CustomBuildRuleResolverFactory.CHAIN_TOP_TARGET, buildTargetsAssignedToMinionThree.get(0));

    // Minion three completes node at top of chain, and now gets two more work units, left and right
    List<WorkUnit> minionThreeWorkFromRequestTwo =
        allocator.dequeueZeroDependencyNodes(
            MINION_THREE,
            ImmutableList.of(CustomBuildRuleResolverFactory.CHAIN_TOP_TARGET),
            MAX_WORK_UNITS);
    Assert.assertEquals(2, minionThreeWorkFromRequestTwo.size());

    // Minion three completes left node. right node is still remaining. no new work.
    List<WorkUnit> minionThreeWorkFromRequestThree =
        allocator.dequeueZeroDependencyNodes(
            MINION_THREE,
            ImmutableList.of(CustomBuildRuleResolverFactory.LEFT_TARGET),
            MAX_WORK_UNITS);
    Assert.assertEquals(0, minionThreeWorkFromRequestThree.size());

    // Minion three fails.
    allocator.handleMinionFailure(MINION_THREE);
    Assert.assertFalse(allocator.isBuildFinished());

    // Minion four picks up remaining work unit from minion three. gets work unit with right node
    Assert.assertFalse(allocator.hasMinionFailed(MINION_FOUR));
    List<WorkUnit> minionFourWorkFromRequestOne =
        allocator.dequeueZeroDependencyNodes(MINION_FOUR, ImmutableList.of(), MAX_WORK_UNITS);
    Assert.assertEquals(1, minionFourWorkFromRequestOne.size());
    List<String> buildTargetsAssignedToMinionFour =
        minionFourWorkFromRequestOne.get(0).getBuildTargets();
    Assert.assertEquals(1, buildTargetsAssignedToMinionFour.size());
    Assert.assertEquals(
        CustomBuildRuleResolverFactory.RIGHT_TARGET, buildTargetsAssignedToMinionFour.get(0));

    // Minion four completes right node. gets final root node back
    List<WorkUnit> minionFourWorkFromRequestTwo =
        allocator.dequeueZeroDependencyNodes(
            MINION_FOUR,
            ImmutableList.of(CustomBuildRuleResolverFactory.RIGHT_TARGET),
            MAX_WORK_UNITS);
    Assert.assertEquals(1, minionFourWorkFromRequestTwo.size());
    Assert.assertFalse(allocator.isBuildFinished());

    // Minion four completes the build
    List<WorkUnit> minionFourWorkFromRequestThree =
        allocator.dequeueZeroDependencyNodes(
            MINION_FOUR,
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
        allocator.dequeueZeroDependencyNodes(MINION_ONE, ImmutableList.of(), MAX_WORK_UNITS);
    Assert.assertEquals(1, firstTargets.size());
    Assert.assertFalse(allocator.isBuildFinished());

    List<WorkUnit> secondTargets =
        allocator.dequeueZeroDependencyNodes(
            MINION_ONE,
            ImmutableList.of(CustomBuildRuleResolverFactory.LEAF_TARGET),
            MAX_WORK_UNITS);
    Assert.assertEquals(2, secondTargets.size());
    Assert.assertFalse(allocator.isBuildFinished());

    List<WorkUnit> thirdTargets =
        allocator.dequeueZeroDependencyNodes(
            MINION_ONE,
            ImmutableList.of(
                CustomBuildRuleResolverFactory.LEFT_TARGET,
                CustomBuildRuleResolverFactory.RIGHT_TARGET),
            MAX_WORK_UNITS);
    Assert.assertEquals(1, thirdTargets.size());
    Assert.assertFalse(allocator.isBuildFinished());

    List<WorkUnit> fourthTargets =
        allocator.dequeueZeroDependencyNodes(
            MINION_ONE,
            ImmutableList.of(CustomBuildRuleResolverFactory.ROOT_TARGET),
            MAX_WORK_UNITS);
    Assert.assertEquals(0, fourthTargets.size());
    Assert.assertTrue(allocator.isBuildFinished());
  }
}
