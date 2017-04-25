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

package com.facebook.buck.distributed;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Allocates and keeps track of what BuildTargets are allocated to which Minions. NOTE: Not thread
 * safe.
 */
public class MinionWorkloadAllocator {

  private final BuildTargetsQueue queue;
  private final int maxTargetsPerMinion;
  private final Map<String, MinionWorkload> minionAllocations;
  private final List<String> targetsNotAssignedYet;

  public MinionWorkloadAllocator(BuildTargetsQueue queue, int maxTargetsPerMinion) {
    this.queue = queue;
    this.minionAllocations = new HashMap<>();
    this.targetsNotAssignedYet =
        Lists.newArrayList(queue.dequeueZeroDependencyNodes(ImmutableList.of()));
    this.maxTargetsPerMinion = maxTargetsPerMinion;
  }

  public ImmutableList<String> getTargetsToBuild(String minionId) {
    // Return existing one if already allocated.
    if (minionAllocations.containsKey(minionId)) {
      return minionAllocations.get(minionId).getTargetsBeingBuilt();
    }

    // Make sure we keep the list of targets ready to build stocked up.
    if (targetsNotAssignedYet.size() < maxTargetsPerMinion) {
      targetsNotAssignedYet.addAll(queue.dequeueZeroDependencyNodes(ImmutableList.of()));
    }

    if (targetsNotAssignedYet.isEmpty()) {
      return ImmutableList.of();
    }

    // Assign new minionWorkload to the worker.

    // NOTE: This is just a view into the original collection. It's not a clone.
    int lastIndex = Math.min(targetsNotAssignedYet.size(), maxTargetsPerMinion);
    List<String> viewIntoTargetsToBuild = targetsNotAssignedYet.subList(0, lastIndex);
    ImmutableList<String> targetsToBuild = ImmutableList.copyOf(viewIntoTargetsToBuild);

    // Because this is a view over the original List, the .clear() method will remove the
    // items from the original list.
    viewIntoTargetsToBuild.clear();

    MinionWorkload minionWorkload = new MinionWorkload(targetsToBuild);
    minionAllocations.put(minionId, minionWorkload);
    return targetsToBuild;
  }

  public void finishedBuildingTargets(String minionId) {
    MinionWorkload minionWorkload = Preconditions.checkNotNull(minionAllocations.remove(minionId));
    targetsNotAssignedYet.addAll(
        queue.dequeueZeroDependencyNodes(minionWorkload.getTargetsBeingBuilt()));
  }

  public boolean isBuildFinished() {
    return minionAllocations.size() == 0 && targetsNotAssignedYet.size() == 0;
  }

  private static class MinionWorkload {
    private final ImmutableList<String> targetsBeingBuilt;

    public MinionWorkload(ImmutableList<String> targetsBeingBuilt) {
      this.targetsBeingBuilt = targetsBeingBuilt;
    }

    public ImmutableList<String> getTargetsBeingBuilt() {
      return targetsBeingBuilt;
    }
  }
}
