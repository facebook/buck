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
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import com.facebook.buck.core.build.engine.impl.DefaultRuleDepsCache;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.distributed.NoopArtifactCacheByBuildRule;
import com.facebook.buck.distributed.build_slave.MinionWorkloadAllocator.WorkloadAllocationResult;
import com.facebook.buck.distributed.testutil.CustomActiongGraphBuilderFactory;
import com.facebook.buck.distributed.thrift.MinionType;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.distributed.thrift.WorkUnit;
import com.facebook.buck.event.listener.NoOpCoordinatorBuildRuleEventsPublisher;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class MinionWorkloadAllocatorTest {

  private static final String MINION_ONE = "Super minion 1";
  private static final String MINION_TWO = "Super minion 2";
  private static final String MINION_THREE = "Super minion 3";
  private static final String MINION_FOUR = "Super minion 4";
  private static final int MAX_WORK_UNITS_TO_FETCH = 10;
  private static final int FEW_REMAINING_UNITS = 1;
  private static final int MANY_REMAINING_UNITS = 100;
  private static final int MOST_BUILD_RULES_FINISHED_PERCENTAGE = 100;
  private static final StampedeId STAMPEDE_ID = new StampedeId().setId("DUMMY_ID");

  private DistBuildTraceTracker tracker;

  @Before
  public void setUp() {
    this.tracker = new DistBuildTraceTracker(STAMPEDE_ID);
  }

  private BuildTargetsQueue createQueueUsingResolver(BuildRuleResolver resolver) {
    BuildTarget target =
        BuildTargetFactory.newInstance(CustomActiongGraphBuilderFactory.ROOT_TARGET);
    BuildTargetsQueue queue =
        new CacheOptimizedBuildTargetsQueueFactory(
                resolver,
                new NoopArtifactCacheByBuildRule(),
                false,
                new DefaultRuleDepsCache(resolver),
                false)
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
                CustomActiongGraphBuilderFactory.createDiamondDependencyBuilderWithChainFromLeaf()),
            tracker,
            Optional.of(MINION_THREE),
            true);

    // Allocate work unit with 2 targets to low-speced minion one
    allocateWorkWithNoReleaseAndAssert(
        allocator,
        MINION_ONE,
        LOW_SPEC,
        ImmutableList.of(),
        ImmutableList.of(
            ImmutableList.of(
                CustomActiongGraphBuilderFactory.LEAF_TARGET,
                CustomActiongGraphBuilderFactory.CHAIN_TOP_TARGET)));

    // Simulate failure of minion one
    simulateAndAssertMinionFailure(allocator, MINION_ONE);

    // Try to re-allocate to low-speced minion two, which should be skipped as failed nodes
    // should always go to the most powerful hardware.
    allocateWorkWithNoReleaseAndAssert(
        allocator, MINION_TWO, LOW_SPEC, ImmutableList.of(), ImmutableList.of());

    // Try to re-allocate to standard-speced minion one, which should pickup the work.
    allocateWorkWithNoReleaseAndAssert(
        allocator,
        MINION_THREE,
        STANDARD_SPEC,
        ImmutableList.of(),
        ImmutableList.of(
            ImmutableList.of(
                CustomActiongGraphBuilderFactory.LEAF_TARGET,
                CustomActiongGraphBuilderFactory.CHAIN_TOP_TARGET)));

    // Minion 3 completes nodes and gets more work
    List<WorkUnit> minionTwoWorkFromRequestTwo =
        allocator.updateMinionWorkloadAllocation(
                MINION_THREE,
                STANDARD_SPEC,
                ImmutableList.of(
                    CustomActiongGraphBuilderFactory.LEAF_TARGET,
                    CustomActiongGraphBuilderFactory.CHAIN_TOP_TARGET),
                MAX_WORK_UNITS_TO_FETCH)
            .newWorkUnitsForMinion;
    Assert.assertEquals(2, minionTwoWorkFromRequestTwo.size());
  }

  @Test
  public void testMultipleMinionFailuresRecoveredFrom() {
    MinionWorkloadAllocator allocator =
        new MinionWorkloadAllocator(
            createQueueUsingResolver(
                CustomActiongGraphBuilderFactory.createDiamondDependencyBuilderWithChainFromLeaf()),
            tracker,
            Optional.of(MINION_FOUR),
            true);

    // Allocate work unit with 2 targets to minion one
    allocateWorkWithNoReleaseAndAssert(
        allocator,
        MINION_ONE,
        STANDARD_SPEC,
        ImmutableList.of(),
        ImmutableList.of(
            ImmutableList.of(
                CustomActiongGraphBuilderFactory.LEAF_TARGET,
                CustomActiongGraphBuilderFactory.CHAIN_TOP_TARGET)));

    // Simulate failure of minion one
    simulateAndAssertMinionFailure(allocator, MINION_ONE);

    // Re-allocate work unit from failed minion one to minion two
    allocateWorkWithNoReleaseAndAssert(
        allocator,
        MINION_TWO,
        STANDARD_SPEC,
        ImmutableList.of(),
        ImmutableList.of(
            ImmutableList.of(
                CustomActiongGraphBuilderFactory.LEAF_TARGET,
                CustomActiongGraphBuilderFactory.CHAIN_TOP_TARGET)));

    // Minion 2 completes the first item in the work unit it was given. Doesn't get anything new
    allocateWorkWithNoReleaseAndAssert(
        allocator,
        MINION_TWO,
        STANDARD_SPEC,
        ImmutableList.of(CustomActiongGraphBuilderFactory.LEAF_TARGET),
        ImmutableList.of());

    // Simulate failure of minion two.
    simulateAndAssertMinionFailure(allocator, MINION_TWO);

    // Re-allocate *remaining work in* work unit from failed minion two to minion three
    allocateWorkWithNoReleaseAndAssert(
        allocator,
        MINION_THREE,
        STANDARD_SPEC,
        ImmutableList.of(),
        ImmutableList.of(ImmutableList.of(CustomActiongGraphBuilderFactory.CHAIN_TOP_TARGET)));

    // Minion three completes node at top of chain, and now gets two more work units, left and right
    List<WorkUnit> minionThreeWorkFromRequestTwo =
        allocator.updateMinionWorkloadAllocation(
                MINION_THREE,
                STANDARD_SPEC,
                ImmutableList.of(CustomActiongGraphBuilderFactory.CHAIN_TOP_TARGET),
                MAX_WORK_UNITS_TO_FETCH)
            .newWorkUnitsForMinion;
    Assert.assertEquals(2, minionThreeWorkFromRequestTwo.size());

    // Minion three completes left node. right node is still remaining. no new work.
    allocateWorkWithNoReleaseAndAssert(
        allocator,
        MINION_THREE,
        STANDARD_SPEC,
        ImmutableList.of(CustomActiongGraphBuilderFactory.LEFT_TARGET),
        ImmutableList.of());

    // Minion three fails.
    simulateAndAssertMinionFailure(allocator, MINION_THREE);

    // Minion four picks up remaining work unit from minion three. gets work unit with right node
    allocateWorkWithNoReleaseAndAssert(
        allocator,
        MINION_FOUR,
        STANDARD_SPEC,
        ImmutableList.of(),
        ImmutableList.of(ImmutableList.of(CustomActiongGraphBuilderFactory.RIGHT_TARGET)));

    // Minion four completes right node. gets final root node back
    allocateWorkWithNoReleaseAndAssert(
        allocator,
        MINION_FOUR,
        STANDARD_SPEC,
        ImmutableList.of(CustomActiongGraphBuilderFactory.RIGHT_TARGET),
        ImmutableList.of(ImmutableList.of(CustomActiongGraphBuilderFactory.ROOT_TARGET)));

    // Minion four completes the build
    List<WorkUnit> minionFourWorkFromRequestThree =
        allocator.updateMinionWorkloadAllocation(
                MINION_FOUR,
                STANDARD_SPEC,
                ImmutableList.of(CustomActiongGraphBuilderFactory.ROOT_TARGET),
                MAX_WORK_UNITS_TO_FETCH)
            .newWorkUnitsForMinion;
    Assert.assertEquals(0, minionFourWorkFromRequestThree.size());
    Assert.assertTrue(allocator.isBuildFinished());
  }

  @Test
  public void testNormalBuildFlow() {
    MinionWorkloadAllocator allocator =
        new MinionWorkloadAllocator(
            createQueueUsingResolver(
                CustomActiongGraphBuilderFactory.createDiamondDependencyGraph()),
            tracker,
            Optional.of(MINION_ONE),
            true);
    Assert.assertFalse(allocator.isBuildFinished());

    List<WorkUnit> firstTargets =
        allocator.updateMinionWorkloadAllocation(
                MINION_ONE, STANDARD_SPEC, ImmutableList.of(), MAX_WORK_UNITS_TO_FETCH)
            .newWorkUnitsForMinion;
    Assert.assertEquals(1, firstTargets.size());
    Assert.assertFalse(allocator.isBuildFinished());

    List<WorkUnit> secondTargets =
        allocator.updateMinionWorkloadAllocation(
                MINION_ONE,
                STANDARD_SPEC,
                ImmutableList.of(CustomActiongGraphBuilderFactory.LEAF_TARGET),
                MAX_WORK_UNITS_TO_FETCH)
            .newWorkUnitsForMinion;
    Assert.assertEquals(2, secondTargets.size());
    Assert.assertFalse(allocator.isBuildFinished());

    List<WorkUnit> thirdTargets =
        allocator.updateMinionWorkloadAllocation(
                MINION_ONE,
                STANDARD_SPEC,
                ImmutableList.of(
                    CustomActiongGraphBuilderFactory.LEFT_TARGET,
                    CustomActiongGraphBuilderFactory.RIGHT_TARGET),
                MAX_WORK_UNITS_TO_FETCH)
            .newWorkUnitsForMinion;
    Assert.assertEquals(1, thirdTargets.size());
    Assert.assertFalse(allocator.isBuildFinished());

    List<WorkUnit> fourthTargets =
        allocator.updateMinionWorkloadAllocation(
                MINION_ONE,
                STANDARD_SPEC,
                ImmutableList.of(CustomActiongGraphBuilderFactory.ROOT_TARGET),
                MAX_WORK_UNITS_TO_FETCH)
            .newWorkUnitsForMinion;
    Assert.assertEquals(0, fourthTargets.size());
    Assert.assertTrue(allocator.isBuildFinished());
  }

  @Test
  public void testInstantReleaseApartFromCoordinatorMinion() {
    BuildTargetsQueue queue = EasyMock.createNiceMock(BuildTargetsQueue.class);
    MinionWorkloadAllocator allocator =
        new MinionWorkloadAllocator(queue, tracker, Optional.of(MINION_ONE), true);

    // Always say "no work left" if asked (force "this minion's capacity is redundant" decisions).
    expect(queue.getSafeApproxOfRemainingWorkUnitsCount())
        .andReturn(FEW_REMAINING_UNITS)
        .anyTimes();
    List<WorkUnit> noUnits = new LinkedList<>();
    List<String> noTargets = new LinkedList<>();
    expect(queue.dequeueZeroDependencyNodes(noTargets, MAX_WORK_UNITS_TO_FETCH))
        .andReturn(noUnits)
        .anyTimes();
    replay(queue);

    // Coordinator's minion does not get released, other minions released instantly.
    allocateWorkAndCheckRelease(allocator, MINION_ONE, ImmutableList.of(), false, noUnits);
    allocateWorkAndCheckRelease(allocator, MINION_TWO, ImmutableList.of(), true, noUnits);
    allocateWorkAndCheckRelease(allocator, MINION_ONE, ImmutableList.of(), false, noUnits);
    allocateWorkAndCheckRelease(allocator, MINION_THREE, ImmutableList.of(), true, noUnits);
    verify(queue);
  }

  @Test
  public void testRedundantMinionNotReleased() {
    BuildTargetsQueue queue = EasyMock.createNiceMock(BuildTargetsQueue.class);
    // Allocator with releasing disabled.
    MinionWorkloadAllocator allocator =
        new MinionWorkloadAllocator(queue, tracker, Optional.of(MINION_ONE), false);

    // Do not assign work.
    List<WorkUnit> noUnits = new LinkedList<>();
    expect(queue.dequeueZeroDependencyNodes(new LinkedList<>(), MAX_WORK_UNITS_TO_FETCH))
        .andReturn(noUnits)
        .anyTimes();
    // Always say "no work left" if asked (force "this minion's capacity is redundant" decisions).
    expect(queue.getSafeApproxOfRemainingWorkUnitsCount())
        .andReturn(FEW_REMAINING_UNITS)
        .anyTimes();
    replay(queue);

    // Coordinator's minion does not get released.
    allocateWorkAndCheckRelease(allocator, MINION_ONE, ImmutableList.of(), false, noUnits);
    // Redundant minion (work fits on coordinator's minion) not released.
    allocateWorkAndCheckRelease(allocator, MINION_TWO, ImmutableList.of(), false, noUnits);
    verify(queue);
  }

  @Test
  public void testUseMinionAndThenReleaseOncePossible() {
    BuildTargetsQueue queue = EasyMock.createNiceMock(BuildTargetsQueue.class);
    MinionWorkloadAllocator allocator =
        new MinionWorkloadAllocator(queue, tracker, Optional.of(MINION_ONE), true);

    int unitsCount = 2;
    List<WorkUnit> units = createWorkUnits(0, unitsCount);
    List<WorkUnit> units2 = createWorkUnits(units.size(), unitsCount);
    List<WorkUnit> noUnits = new LinkedList<>();
    ImmutableList<String> targets = ImmutableList.copyOf(getAllTargets(units));
    List<String> noTargets = new LinkedList<>();
    // Provide units on 1st and 2nd call, then do not.
    expect(queue.dequeueZeroDependencyNodes(noTargets, MAX_WORK_UNITS_TO_FETCH))
        .andReturn(units)
        .once();
    expect(queue.dequeueZeroDependencyNodes(noTargets, MAX_WORK_UNITS_TO_FETCH))
        .andReturn(units2)
        .once();
    expect(queue.dequeueZeroDependencyNodes(noTargets, MAX_WORK_UNITS_TO_FETCH))
        .andReturn(noUnits)
        .once();
    expect(queue.dequeueZeroDependencyNodes(targets, MAX_WORK_UNITS_TO_FETCH))
        .andReturn(noUnits)
        .once();
    expect(queue.dequeueZeroDependencyNodes(noTargets, MAX_WORK_UNITS_TO_FETCH))
        .andReturn(noUnits)
        .once();
    // Notify "many units left to do" on 1st call and "no units" on 2nd.
    expect(queue.getSafeApproxOfRemainingWorkUnitsCount()).andReturn(MANY_REMAINING_UNITS).once();
    expect(queue.getSafeApproxOfRemainingWorkUnitsCount()).andReturn(FEW_REMAINING_UNITS).once();
    replay(queue);

    // Minion gets work - not released.
    allocateWorkAndCheckRelease(allocator, MINION_TWO, ImmutableList.of(), false, units);
    // Coordinator minion gets some work.
    allocateWorkAndCheckRelease(allocator, MINION_ONE, ImmutableList.of(), false, units2);
    // Minion completed nothing - not released as still working.
    allocateWorkAndCheckRelease(allocator, MINION_TWO, ImmutableList.of(), false, noUnits);
    // Minion completes work, many units left (but none possible to assign now) - not released.
    allocateWorkAndCheckRelease(allocator, MINION_TWO, targets, false, noUnits);
    // Minion is not working and remaining units will fit on other minions - released.
    allocateWorkAndCheckRelease(allocator, MINION_TWO, ImmutableList.of(), true, noUnits);
    verify(queue);
  }

  private static void allocateWorkAndCheckRelease(
      MinionWorkloadAllocator allocator,
      String minionId,
      ImmutableList<String> finishedNodes,
      boolean release,
      List<WorkUnit> units) {
    ImmutableList<ImmutableList<String>> expectedWorkUnits =
        units
            .stream()
            .map(unit -> ImmutableList.copyOf(unit.getBuildTargets()))
            .collect(ImmutableList.toImmutableList());
    allocateWorkAndAssert(
        allocator, minionId, STANDARD_SPEC, finishedNodes, expectedWorkUnits, release);
  }

  private static List<WorkUnit> createWorkUnits(int firstUnitNumber, int unitsCount) {
    List<WorkUnit> units = new ArrayList<>();
    int lastUnit = firstUnitNumber + unitsCount;
    while (firstUnitNumber < lastUnit) {
      WorkUnit unit = new WorkUnit();
      unit.setBuildTargets(new ArrayList<>(Arrays.asList(Integer.toString(firstUnitNumber))));
      units.add(unit);
      firstUnitNumber++;
    }
    return units;
  }

  private static List<String> getAllTargets(List<WorkUnit> units) {
    return units
        .stream()
        .flatMap(unit -> unit.getBuildTargets().stream())
        .collect(Collectors.toList());
  }

  private static void simulateAndAssertMinionFailure(
      MinionWorkloadAllocator allocator, String minionId) {
    allocator.handleMinionFailure(minionId);
    Assert.assertFalse(allocator.isBuildFinished());
    Assert.assertTrue(allocator.hasMinionFailed(minionId));
  }

  private static void allocateWorkWithNoReleaseAndAssert(
      MinionWorkloadAllocator allocator,
      String minionId,
      MinionType minionType,
      ImmutableList<String> finishedNodes,
      ImmutableList<ImmutableList<String>> expectedWorkUnits) {
    allocateWorkAndAssert(allocator, minionId, minionType, finishedNodes, expectedWorkUnits, false);
  }

  private static void allocateWorkAndAssert(
      MinionWorkloadAllocator allocator,
      String minionId,
      MinionType minionType,
      ImmutableList<String> finishedNodes,
      ImmutableList<ImmutableList<String>> expectedWorkUnits,
      boolean release) {

    Assert.assertFalse(allocator.hasMinionFailed(minionId));
    WorkloadAllocationResult result =
        allocator.updateMinionWorkloadAllocation(
            minionId, minionType, finishedNodes, MAX_WORK_UNITS_TO_FETCH);
    Assert.assertEquals(expectedWorkUnits.size(), result.newWorkUnitsForMinion.size());
    Assert.assertEquals(release, result.shouldReleaseMinion);

    for (int workUnitIndex = 0; workUnitIndex < expectedWorkUnits.size(); workUnitIndex++) {
      ImmutableList<String> expectedWorkUnit = expectedWorkUnits.get(workUnitIndex);
      List<String> actualWorkUnit =
          result.newWorkUnitsForMinion.get(workUnitIndex).getBuildTargets();

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
