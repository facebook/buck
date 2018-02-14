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

import com.facebook.buck.distributed.thrift.WorkUnit;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.BuildResult;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** * Keeps track of everything that is being built by the local minion */
public class MinionLocalBuildStateTracker {
  private static final Logger LOG = Logger.get(MinionLocalBuildStateTracker.class);

  // I.e. the number of CPU cores that are currently free at this minion.
  // Each work unit takes up one core.
  private int availableWorkUnitCapacity;

  // All targets that have finished build (and been uploaded) that need to be signalled
  // back to the coordinator.
  private final Set<String> uploadedTargetsToSignal = new HashSet<>();

  // These are the targets at the end of work unit, when complete the corresponding core is free.
  private final Set<String> workUnitTerminalTargets = new HashSet<>();

  // These are some extra targets which were supposed to be cache hits, but were built locally.
  private final Set<String> locallyBuiltDirectDeps = new HashSet<>();

  // All targets that were actually scheduled at this minion by a coordinator
  private final Set<String> knownTargets = new HashSet<>();

  // All targets that have finished building
  private final Set<String> finishedTargets = new HashSet<>();

  // All targets that have finished uploading
  private final Set<String> uploadedTargets = new HashSet<>();

  // Targets for which build hasn't started yet
  private final List<WorkUnit> workUnitsToBuild = new ArrayList<>();

  private final MinionBuildProgressTracker minionBuildProgressTracker;

  public MinionLocalBuildStateTracker(
      int maxWorkUnitBuildCapacity, MinionBuildProgressTracker minionBuildProgressTracker) {
    availableWorkUnitCapacity = maxWorkUnitBuildCapacity;
    this.minionBuildProgressTracker = minionBuildProgressTracker;
  }

  /** @return True if this minion has free capacity to build more targets */
  public synchronized boolean capacityAvailable() {
    return availableWorkUnitCapacity != 0;
  }

  /** @return Number of additional work units this minion can build */
  public synchronized int getAvailableCapacity() {
    return availableWorkUnitCapacity;
  }

  /**
   * @return Targets that have finished building at minion that need to be signalled to coordinator.
   */
  public synchronized List<String> getTargetsToSignal() {
    // Make a copy of the finished targets set
    List<String> targets = Lists.newArrayList(uploadedTargetsToSignal);

    for (String target : targets) {
      LOG.info(String.format("Local minion is signalling: [%s]", target));
    }

    uploadedTargetsToSignal.clear();
    return targets;
  }

  /** @param newWorkUnits Work Units that have just been fetched from the coordinator */
  public synchronized void enqueueWorkUnitsForBuilding(List<WorkUnit> newWorkUnits) {
    if (newWorkUnits.size() == 0) {
      return;
    }
    for (WorkUnit workUnit : newWorkUnits) {
      List<String> buildTargetsInWorkUnit = workUnit.getBuildTargets();
      Preconditions.checkArgument(buildTargetsInWorkUnit.size() > 0);
      recordTerminalTarget(buildTargetsInWorkUnit);
      workUnitsToBuild.add(workUnit);
    }

    // Each fetched work unit is going to occupy one core, mark the core as busy until the
    // work unit has finished.
    // Note: we should do this immediately so that the next GetWork call doesn't attempt
    // to use the old availableWorkUnitCapacity value.
    availableWorkUnitCapacity -= newWorkUnits.size();

    LOG.info(
        String.format(
            "Queued [%d] work units for building. New available capacity [%d]",
            newWorkUnits.size(), availableWorkUnitCapacity));
  }

  /** @return True if there are queued work units that haven't been build yet */
  public synchronized boolean outstandingWorkUnitsToBuild() {
    return workUnitsToBuild.size() > 0;
  }

  /** @return Targets that minion should build, extracted from queued work units. */
  public synchronized List<String> getTargetsToBuild() {
    // Each work unit consists of one of more build targets. Aggregate them all together
    // and feed them to the build engine as a batch.
    List<String> targetsToBuild = Lists.newArrayList();
    for (WorkUnit workUnit : workUnitsToBuild) {
      targetsToBuild.addAll(workUnit.getBuildTargets());
    }

    knownTargets.addAll(targetsToBuild);
    minionBuildProgressTracker.updateTotalRuleCount(knownTargets.size());

    LOG.debug(
        String.format(
            "Returning [%d] targets from [%d] work units for building",
            targetsToBuild.size(), workUnitsToBuild.size()));

    workUnitsToBuild.clear();

    return targetsToBuild;
  }

  /** Update book-keeping to record target as finished. */
  public synchronized void recordFinishedTarget(BuildResult buildResult) {
    Preconditions.checkArgument(buildResult.isSuccess());

    String target = buildResult.getRule().getFullyQualifiedName();
    Preconditions.checkArgument(knownTargets.contains(target));
    Preconditions.checkArgument(!finishedTargets.contains(target));

    finishedTargets.add(target);
    minionBuildProgressTracker.updateFinishedRuleCount(finishedTargets.size());
    increaseCapacityIfTerminalNode(target);
    detectUnexpectedCacheMisses(buildResult);
  }

  /** Increases available capacity if the given target was at the end of a work unit. */
  private synchronized void increaseCapacityIfTerminalNode(String target) {
    // If a target that just finished was the terminal node in a work unit, then that core
    // is now available for further work.
    if (!workUnitTerminalTargets.contains(target)) {
      return;
    }

    availableWorkUnitCapacity++;
    workUnitTerminalTargets.remove(target);
  }

  /** Publishes an event for unexpected cache misses. */
  private synchronized void detectUnexpectedCacheMisses(BuildResult buildResult) {
    Preconditions.checkArgument(buildResult.isSuccess());
    String target = buildResult.getRule().getFullyQualifiedName();

    if (buildResult.getDepsWithCacheMisses().isPresent()) {
      Set<String> depsWithCacheMisses = new HashSet<>(buildResult.getDepsWithCacheMisses().get());
      depsWithCacheMisses.removeAll(knownTargets); // These are supposed to be built locally.
      depsWithCacheMisses.removeAll(locallyBuiltDirectDeps); // These have been recorded previously.

      // Anything left is an unexpected cache miss.
      if (depsWithCacheMisses.size() > 0) {
        LOG.warn(
            "Got [%d] cache misses for direct dependencies of target [%s], built them locally.",
            depsWithCacheMisses.size(), target);
        minionBuildProgressTracker.onUnexpectedCacheMiss(depsWithCacheMisses.size());

        // Make sure we don't record these targets again.
        locallyBuiltDirectDeps.addAll(depsWithCacheMisses);
      }
    }
  }

  /**
   * Once a target has been built and uploaded to the cache, it is now safe to signal to the
   * coordinator that the target is finished.
   *
   * @param target
   */
  public synchronized void recordUploadedTarget(String target) {
    Preconditions.checkArgument(knownTargets.contains(target));
    Preconditions.checkArgument(!uploadedTargets.contains(target));
    uploadedTargets.add(target);

    uploadedTargetsToSignal.add(target);
  }

  // Keep a record of the last target in a work unit, as we need to wait for this to finish
  // before the corresponding core can be freed.
  private void recordTerminalTarget(List<String> buildTargetsInWorkUnit) {
    workUnitTerminalTargets.add(buildTargetsInWorkUnit.get(buildTargetsInWorkUnit.size() - 1));
  }
}
