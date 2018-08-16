/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.distributed.thrift.CoordinatorBuildProgress;
import com.facebook.buck.distributed.thrift.MinionType;
import com.facebook.buck.distributed.thrift.WorkUnit;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Allocates and keeps track of what BuildTargetPaths are allocated to which Minions. This class is
 * thread safe.
 */
public class MinionWorkloadAllocator {
  private static final Logger LOG = Logger.get(MinionWorkloadAllocator.class);

  private final BuildTargetsQueue queue;
  private final Set<String> nodesAssignedToMinions = new HashSet<>();

  private final Map<String, Set<WorkUnit>> workUnitsAssignedToMinions = new HashMap<>();

  // Maps each target to the work unit that contains it.
  private final Map<String, WorkUnit> workUnitsByTarget = new HashMap<>();

  // These should be immediately re-assigned when capacity becomes available on other minions
  private Queue<WorkUnit> workUnitsFromFailedMinions = new LinkedList<>();

  private final Set<String> failedMinions = new HashSet<>();
  private final Set<String> seenMinions = new HashSet<>();
  private final Map<String, MinionType> minionTypesByMinionId = new HashMap<>();

  private final Set<String> minionsAvailableForAllocation = new HashSet<>();
  private final Map<String, Integer> minionFreeCapacities = new HashMap<>();
  private final Optional<String> coordinatorMinionId;
  private final boolean releasingMinionsEarlyEnabled;

  private final DistBuildTraceTracker chromeTraceTracker;

  /**
   * Result of updating allocation - indicates if minion should be now released (capacity no longer
   * needed) and contains a list of new work units for the minion to process.
   */
  public static class WorkloadAllocationResult {
    public final boolean shouldReleaseMinion;
    public final List<WorkUnit> newWorkUnitsForMinion;

    public WorkloadAllocationResult(
        boolean shouldReleaseMinion, List<WorkUnit> newWorkUnitsForMinion) {
      if (shouldReleaseMinion) {
        Preconditions.checkArgument(newWorkUnitsForMinion.isEmpty());
      }
      this.shouldReleaseMinion = shouldReleaseMinion;
      this.newWorkUnitsForMinion = newWorkUnitsForMinion;
    }
  }

  public MinionWorkloadAllocator(
      BuildTargetsQueue queue,
      DistBuildTraceTracker chromeTraceTracker,
      Optional<String> coordinatorMinionId,
      boolean releasingMinionsEarlyEnabled) {
    this.queue = queue;
    this.chromeTraceTracker = chromeTraceTracker;
    this.coordinatorMinionId = coordinatorMinionId;
    this.releasingMinionsEarlyEnabled = releasingMinionsEarlyEnabled;
  }

  public synchronized boolean isBuildFinished() {
    return nodesAssignedToMinions.size() == 0
        && workUnitsFromFailedMinions.size() == 0
        && !queue.hasReadyZeroDependencyNodes();
  }

  private void trySetupMinion(String minionId, MinionType minionType, int maxWorkUnitsToFetch) {
    if (seenMinions.contains(minionId)) {
      return;
    }

    if (!workUnitsAssignedToMinions.containsKey(minionId)) {
      workUnitsAssignedToMinions.put(minionId, new HashSet<>());
    }

    minionTypesByMinionId.put(minionId, minionType);

    minionsAvailableForAllocation.add(minionId);
    minionFreeCapacities.put(minionId, maxWorkUnitsToFetch);

    seenMinions.add(minionId);
  }

  private boolean isMinionCapacityRedundant(String candidateMinionId) {
    if (!minionsAvailableForAllocation.contains(candidateMinionId)) {
      return true;
    }

    // Never release the coordinator's minion - we gain nothing doing that (machine is still
    // occupied) and we may get lucky and schedule work there later and release more minions.
    if (coordinatorMinionId.isPresent() && coordinatorMinionId.get().equals(candidateMinionId)) {
      return false;
    }

    int maxParallelWorkUnitsLeft = queue.getSafeApproxOfRemainingWorkUnitsCount();
    int capacityAvailableOnOtherMinions =
        minionsAvailableForAllocation
            .stream()
            .filter(minionId -> !minionId.equals(candidateMinionId))
            .mapToInt(minionId -> Preconditions.checkNotNull(minionFreeCapacities.get(minionId)))
            .sum();

    return maxParallelWorkUnitsLeft <= capacityAvailableOnOtherMinions;
  }

  /**
   * Processes nodes finished by minion and, if minion's capacity is still needed, allocates it new
   * work units (out of units from failed minions and newly available units).
   */
  public synchronized WorkloadAllocationResult updateMinionWorkloadAllocation(
      String minionId, MinionType minionType, List<String> finishedNodes, int maxWorkUnitsToFetch) {
    Preconditions.checkArgument(!failedMinions.contains(minionId));
    trySetupMinion(minionId, minionType, maxWorkUnitsToFetch);

    Set<WorkUnit> workUnitsAllocatedToMinion =
        Preconditions.checkNotNull(workUnitsAssignedToMinions.get(minionId));
    deallocateFinishedNodes(workUnitsAllocatedToMinion, finishedNodes);

    // First try and re-allocate work units from any minions that have failed recently
    List<WorkUnit> newWorkUnitsForMinion =
        reallocateWorkUnitsFromFailedMinions(minionId, maxWorkUnitsToFetch);

    // For any remaining capacity on this minion, fetch new work units, if they exist.
    maxWorkUnitsToFetch -= newWorkUnitsForMinion.size();
    newWorkUnitsForMinion.addAll(
        queue.dequeueZeroDependencyNodes(finishedNodes, maxWorkUnitsToFetch));

    List<String> newNodesForMinion =
        allocateNewNodes(workUnitsAllocatedToMinion, newWorkUnitsForMinion);

    LOG.info(
        String.format(
            "Minion [%s] finished [%s] nodes, and fetched [%s] new nodes. "
                + "Total nodes assigned to minions [%s]. Unscheduled zero dependency nodes? [%s]",
            minionId,
            finishedNodes.size(),
            newNodesForMinion.size(),
            nodesAssignedToMinions.size(),
            queue.hasReadyZeroDependencyNodes()));

    WorkloadAllocationResult result;
    // Check if we can release the minion - no work scheduled and capacity not needed anymore.
    if (workUnitsAllocatedToMinion.isEmpty()
        && releasingMinionsEarlyEnabled
        && isMinionCapacityRedundant(minionId)) {
      minionsAvailableForAllocation.remove(minionId);
      LOG.info(
          String.format(
              "Minion [%s] should now be released - capacity unneeded and no work assigned.",
              minionId));
      result = new WorkloadAllocationResult(true, newWorkUnitsForMinion);
    } else {
      result = new WorkloadAllocationResult(false, newWorkUnitsForMinion);
    }

    minionFreeCapacities.put(minionId, maxWorkUnitsToFetch - result.newWorkUnitsForMinion.size());
    chromeTraceTracker.updateWork(minionId, finishedNodes, result.newWorkUnitsForMinion);
    return result;
  }

  /** @return True if minion has been marked as failed previously */
  public synchronized boolean hasMinionFailed(String minionId) {
    return failedMinions.contains(minionId);
  }

  /**
   * Queues up all work that was allocated to given minion for re-allocation to other minions
   *
   * @param minionId
   */
  public synchronized void handleMinionFailure(String minionId) {
    if (failedMinions.contains(minionId)) {
      return; // Already handled
    }

    failedMinions.add(minionId);
    minionsAvailableForAllocation.remove(minionId);

    if (!workUnitsAssignedToMinions.containsKey(minionId)) {
      LOG.warn(String.format("Failed minion [%s] never had work assigned to it", minionId));
      return;
    }

    Set<WorkUnit> workUnitsAllocatedToMinion = workUnitsAssignedToMinions.get(minionId);

    Set<String> allocatedTargets =
        workUnitsAllocatedToMinion
            .stream()
            .map(workUnit -> workUnit.getBuildTargets())
            .flatMap(Collection::stream)
            .collect(Collectors.toSet());

    LOG.warn(
        String.format(
            "Failed minion [%s] had [%d] active work units containing [%d] targets. Queueing for re-allocation.",
            minionId, workUnitsAllocatedToMinion.size(), allocatedTargets.size()));

    workUnitsAssignedToMinions.remove(minionId);
    workUnitsFromFailedMinions.addAll(workUnitsAllocatedToMinion);
    nodesAssignedToMinions.removeAll(allocatedTargets);
  }

  public synchronized boolean haveMostBuildRulesCompleted() {
    return queue.haveMostBuildRulesFinished();
  }

  public synchronized CoordinatorBuildProgress getBuildProgress() {
    return queue.getBuildProgress();
  }

  private List<WorkUnit> reallocateWorkUnitsFromFailedMinions(String minionId, int maxWorkUnits) {
    List<WorkUnit> reallocatedWorkUnits = new ArrayList<>();

    MinionType minionType = minionTypesByMinionId.get(minionId);

    // Re-allocated work should always go to a more powerful hardware.
    if (minionType == MinionType.LOW_SPEC) {
      return reallocatedWorkUnits;
    }

    while (workUnitsFromFailedMinions.size() > 0 && reallocatedWorkUnits.size() < maxWorkUnits) {
      WorkUnit workUnitToReAssign = workUnitsFromFailedMinions.remove();
      Preconditions.checkArgument(workUnitToReAssign.getBuildTargets().size() > 0);
      reallocatedWorkUnits.add(workUnitToReAssign);
    }

    if (reallocatedWorkUnits.size() > 0) {
      LOG.info(
          "Re-allocated [%d] work units from failed minion to [%s]",
          reallocatedWorkUnits.size(), minionId);
    }

    return reallocatedWorkUnits;
  }

  private List<String> allocateNewNodes(
      Set<WorkUnit> workUnitsForMinion, List<WorkUnit> newWorkUnitsForMinion) {
    List<String> nodesForMinion = new ArrayList<>();
    for (WorkUnit workUnit : newWorkUnitsForMinion) {
      nodesForMinion.addAll(workUnit.getBuildTargets());

      for (String node : workUnit.getBuildTargets()) {
        workUnitsByTarget.put(node, workUnit);
      }
    }

    workUnitsForMinion.addAll(newWorkUnitsForMinion);
    nodesAssignedToMinions.addAll(nodesForMinion);
    return nodesForMinion;
  }

  private void deallocateFinishedNodes(
      Set<WorkUnit> workUnitsForMinion, List<String> finishedNodes) {
    nodesAssignedToMinions.removeAll(finishedNodes);

    for (String finishedNode : finishedNodes) {
      if (!workUnitsByTarget.containsKey(finishedNode)) {
        LOG.error(String.format("No work unit could be found for target [%s]", finishedNode));
        continue;
      }
      WorkUnit workUnitForNode = workUnitsByTarget.get(finishedNode);

      // Important: workUnitForNode must be removed from workUnitsForMinion Set before we modify
      // workUnitForNode, as after modification its hashCode/equals properties will have changed.
      Preconditions.checkArgument(workUnitsForMinion.remove(workUnitForNode));
      Preconditions.checkArgument(workUnitForNode.getBuildTargets().remove(finishedNode));

      if (workUnitForNode.getBuildTargets().size() > 0) {
        // Work unit still has items remaining, so re-add it to Set (using new hashCode)
        workUnitsForMinion.add(workUnitForNode);
      }
    }
  }
}
