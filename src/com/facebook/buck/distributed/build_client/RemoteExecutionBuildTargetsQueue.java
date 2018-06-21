/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.distributed.build_client;

import com.facebook.buck.distributed.build_slave.BuildTargetsQueue;
import com.facebook.buck.distributed.build_slave.DistributableBuildGraph;
import com.facebook.buck.distributed.thrift.CoordinatorBuildProgress;
import com.facebook.buck.distributed.thrift.WorkUnit;
import com.facebook.buck.event.BuckEventBus;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import javax.annotation.concurrent.GuardedBy;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

/** BuildTargetsQueue implementation used to run in the Remote Execution model. */
public class RemoteExecutionBuildTargetsQueue implements BuildTargetsQueue {
  @GuardedBy("this")
  private final Queue<TargetToBuild> targetsWaitingToBeBuilt;

  @GuardedBy("this")
  private final Map<String, TargetToBuild> targetsBuilding;

  private final BuckEventBus eventBus;

  private volatile boolean haveRemoteMachinesConnected;
  private volatile int totalTargetsEnqueued;
  private volatile int totalTargetsBuilt;

  private static class TargetToBuild {
    private final String targetName;
    private final SettableFuture<Void> completionFuture;

    private TargetToBuild(String targetName) {
      this.targetName = targetName;
      this.completionFuture = SettableFuture.create();
    }

    public String getTargetName() {
      return targetName;
    }

    public SettableFuture<Void> getCompletionFuture() {
      return completionFuture;
    }

    @Override
    public int hashCode() {
      return targetName.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof TargetToBuild) {
        return targetName.equals(((TargetToBuild) obj).targetName);
      }

      return false;
    }
  }

  public RemoteExecutionBuildTargetsQueue(BuckEventBus eventBus) {
    this.eventBus = eventBus;
    this.targetsWaitingToBeBuilt = Queues.newArrayDeque();
    this.targetsBuilding = Maps.newConcurrentMap();
    this.haveRemoteMachinesConnected = false;
    this.totalTargetsEnqueued = 0;
    this.totalTargetsBuilt = 0;
  }

  /** Async enqueues a build target to be executed remotely asap. */
  public ListenableFuture<?> enqueueForRemoteBuild(String buildTarget) {
    eventBus.post(
        new RemoteExecutionEvent(
            RemoteExecutionInfo.builder()
                .setState(RemoteExecutionState.ENQUEUED)
                .setBuildTarget(buildTarget)
                .build()));
    TargetToBuild target = new TargetToBuild(buildTarget);
    synchronized (this) {
      targetsWaitingToBeBuilt.add(target);
    }

    return target.getCompletionFuture();
  }

  public boolean haveRemoteMachinesConnected() {
    return this.haveRemoteMachinesConnected;
  }

  @Override
  public boolean hasReadyZeroDependencyNodes() {
    synchronized (this) {
      return !targetsWaitingToBeBuilt.isEmpty();
    }
  }

  @Override
  public List<WorkUnit> dequeueZeroDependencyNodes(List<String> finishedNodes, int maxUnitsOfWork) {
    this.haveRemoteMachinesConnected = true;
    List<WorkUnit> newWorkload = Lists.newArrayList();

    synchronized (this) {
      for (String finishedTarget : finishedNodes) {
        TargetToBuild target = Preconditions.checkNotNull(targetsBuilding.remove(finishedTarget));
        target.getCompletionFuture().set(null);
        eventBus.post(
            new RemoteExecutionEvent(
                RemoteExecutionInfo.builder()
                    .setState(RemoteExecutionState.REMOTE_BUILD_FINISHED)
                    .setBuildTarget(target.getTargetName())
                    .build()));
      }

      int newWorkCount = Math.min(targetsWaitingToBeBuilt.size(), maxUnitsOfWork);
      while (newWorkCount-- > 0) {
        TargetToBuild target = targetsWaitingToBeBuilt.remove();
        targetsBuilding.put(target.getTargetName(), target);
        WorkUnit unit = new WorkUnit().setBuildTargets(Lists.newArrayList(target.getTargetName()));
        newWorkload.add(unit);

        eventBus.post(
            new RemoteExecutionEvent(
                RemoteExecutionInfo.builder()
                    .setState(RemoteExecutionState.REMOTE_BUILD_STARTED)
                    .setBuildTarget(target.getTargetName())
                    .build()));
      }
    }

    return newWorkload;
  }

  @Override
  public boolean haveMostBuildRulesFinished() {
    return false;
  }

  @Override
  public CoordinatorBuildProgress getBuildProgress() {
    CoordinatorBuildProgress progress =
        new CoordinatorBuildProgress()
            .setBuiltRulesCount(totalTargetsBuilt)
            .setTotalRulesCount(totalTargetsEnqueued);
    return progress;
  }

  @Override
  public int getSafeApproxOfRemainingWorkUnitsCount() {
    // TODO: investigate if this can be somehow improved/calculated.
    return Integer.MAX_VALUE;
  }

  @Override
  public DistributableBuildGraph getDistributableBuildGraph() {
    // This class has no knowledge of the build graph.
    throw new NotImplementedException();
  }
}
