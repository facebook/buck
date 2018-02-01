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

import com.facebook.buck.command.BuildExecutorArgs;
import com.facebook.buck.distributed.ClientStatsTracker;
import com.facebook.buck.distributed.DistBuildCellIndexer;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.thrift.BuckVersion;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.ActionAndTargetGraphs;
import com.facebook.buck.rules.CachingBuildEngineDelegate;
import com.facebook.buck.rules.RemoteBuildRuleCompletionNotifier;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.immutables.value.Value;

/** Constructor arguments for DelegateAndGraphsInitializer. */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractDistBuildControllerArgs {
  private static final int DEFAULT_STATUS_POLL_INTERVAL_MILLIS = 500;

  public abstract BuildExecutorArgs getBuilderExecutorArgs();

  public abstract ImmutableSet<BuildTarget> getTopLevelTargets();

  public abstract ActionAndTargetGraphs getBuildGraphs();

  public abstract Optional<CachingBuildEngineDelegate> getCachingBuildEngineDelegate();

  public abstract ListenableFuture<BuildJobState> getAsyncJobState();

  public abstract DistBuildCellIndexer getDistBuildCellIndexer();

  public abstract DistBuildService getDistBuildService();

  public abstract LogStateTracker getDistBuildLogStateTracker();

  public abstract BuckVersion getBuckVersion();

  public abstract ClientStatsTracker getDistBuildClientStats();

  public abstract ScheduledExecutorService getScheduler();

  public abstract long getMaxTimeoutWaitingForLogsMillis();

  public abstract boolean getLogMaterializationEnabled();

  public abstract RemoteBuildRuleCompletionNotifier getRemoteBuildRuleCompletionNotifier();

  public abstract AtomicReference<StampedeId> getStampedeIdReference();

  public abstract String getBuildLabel();

  public abstract BuckEventBus getBuckEventBus();

  @Value.Default
  public int getStatusPollIntervalMillis() {
    return DEFAULT_STATUS_POLL_INTERVAL_MILLIS;
  }
}
