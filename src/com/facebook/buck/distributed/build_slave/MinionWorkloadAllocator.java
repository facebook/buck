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

import com.facebook.buck.distributed.thrift.CoordinatorBuildProgress;
import com.facebook.buck.distributed.thrift.WorkUnit;
import com.facebook.buck.log.Logger;
import java.util.ArrayList;
import java.util.List;

/**
 * Allocates and keeps track of what BuildTargets are allocated to which Minions. NOTE: Not thread
 * safe. Caller needs to synchronize access if using multiple threads.
 */
public class MinionWorkloadAllocator {
  private static final Logger LOG = Logger.get(MinionWorkloadAllocator.class);

  private final BuildTargetsQueue queue;
  private final List<String> nodesAssignedToMinions = new ArrayList<>();

  public MinionWorkloadAllocator(BuildTargetsQueue queue) {
    this.queue = queue;
  }

  public boolean isBuildFinished() {
    return nodesAssignedToMinions.size() == 0 && !queue.hasReadyZeroDependencyNodes();
  }

  /** Returns nodes that have all their dependencies satisfied. */
  public List<WorkUnit> dequeueZeroDependencyNodes(
      String minionId, List<String> finishedNodes, int maxWorkUnits) {
    nodesAssignedToMinions.removeAll(finishedNodes);

    List<WorkUnit> workUnits = queue.dequeueZeroDependencyNodes(finishedNodes, maxWorkUnits);

    List<String> nodesForMinions = new ArrayList<>();
    for (WorkUnit workUnit : workUnits) {
      nodesForMinions.addAll(workUnit.buildTargets);
    }
    nodesAssignedToMinions.addAll(nodesForMinions);

    LOG.info(
        String.format(
            "Minion [%s] finished [%s] nodes, and fetched [%s] new nodes. "
                + "Total nodes assigned to minions [%s]. Unscheduled zero dependency nodes? [%s]",
            minionId,
            finishedNodes.size(),
            nodesForMinions.size(),
            nodesAssignedToMinions.size(),
            queue.hasReadyZeroDependencyNodes()));

    return workUnits;
  }

  public boolean haveMostBuildRulesCompleted() {
    return queue.haveMostBuildRulesFinished();
  }

  public CoordinatorBuildProgress getBuildProgress() {
    return queue.getBuildProgress();
  }
}
