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

package com.facebook.buck.remoteexecution.event.listener;

import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.remoteexecution.event.CasBlobDownloadEvent;
import com.facebook.buck.remoteexecution.event.CasBlobUploadEvent.Finished;
import com.facebook.buck.remoteexecution.event.LocalFallbackEvent;
import com.facebook.buck.remoteexecution.event.LocalFallbackEvent.Result;
import com.facebook.buck.remoteexecution.event.LocalFallbackStats;
import com.facebook.buck.remoteexecution.event.RemoteExecutionActionEvent;
import com.facebook.buck.remoteexecution.event.RemoteExecutionActionEvent.State;
import com.facebook.buck.remoteexecution.event.RemoteExecutionStatsProvider;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;
import com.google.protobuf.Duration;
import com.google.protobuf.util.Timestamps;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/** Remote execution events sent to the event bus. */
public class RemoteExecutionEventListener
    implements BuckEventListener, RemoteExecutionStatsProvider {
  private final Map<State, LongAdder> actionStateCount;
  private final LongAdder totalBuildRules;

  private final LongAdder downloads;
  private final LongAdder donwloadBytes;
  private final LongAdder uploads;
  private final LongAdder uploadBytes;

  private final LongAdder remoteCpuTime;
  private final LongAdder remoteQueueTime;
  private final LongAdder totalRemoteTime;

  private final AtomicBoolean hasFirstRemoteActionStarted;

  private final LongAdder localFallbackTotalExecutions;
  private final LongAdder localFallbackLocalExecutions;
  private final LongAdder localFallbackSuccessfulLocalExecutions;

  public RemoteExecutionEventListener() {
    this.downloads = new LongAdder();
    this.donwloadBytes = new LongAdder();
    this.uploads = new LongAdder();
    this.uploadBytes = new LongAdder();
    this.remoteCpuTime = new LongAdder();
    this.remoteQueueTime = new LongAdder();
    this.totalRemoteTime = new LongAdder();
    this.totalBuildRules = new LongAdder();
    this.hasFirstRemoteActionStarted = new AtomicBoolean(false);

    localFallbackTotalExecutions = new LongAdder();
    localFallbackLocalExecutions = new LongAdder();
    localFallbackSuccessfulLocalExecutions = new LongAdder();

    this.actionStateCount = Maps.newConcurrentMap();
    for (State state : RemoteExecutionActionEvent.State.values()) {
      actionStateCount.put(state, new LongAdder());
    }
  }

  /** Event specific subscriber method. */
  @Subscribe
  public void onBuildRuleEvent(@SuppressWarnings("unused") BuildRuleEvent.Finished event) {
    totalBuildRules.increment();
  }

  /** Event specific subscriber method. */
  @Subscribe
  public void onCasUploadEvent(Finished event) {
    hasFirstRemoteActionStarted.set(true);
    uploads.add(event.getStartedEvent().getBlobCount());
    uploadBytes.add(event.getStartedEvent().getSizeBytes());
  }

  /** Event specific subscriber method. */
  @Subscribe
  public void onCasDownloadEvent(CasBlobDownloadEvent.Finished event) {
    hasFirstRemoteActionStarted.set(true);
    downloads.add(event.getStartedEvent().getBlobCount());
    donwloadBytes.add(event.getStartedEvent().getSizeBytes());
  }

  /** Event specific subscriber method. */
  @Subscribe
  public void onActionScheduled(
      @SuppressWarnings("unused") RemoteExecutionActionEvent.Scheduled event) {
    hasFirstRemoteActionStarted.set(true);
    getStateCount(State.WAITING).increment();
  }

  /** Event specific subscriber method. */
  @Subscribe
  public void onActionEventTerminal(RemoteExecutionActionEvent.Terminal event) {
    hasFirstRemoteActionStarted.set(true);
    getStateCount(State.WAITING).decrement();
    getStateCount(event.getState()).increment();
    if (event.getExecutedActionMetadata().isPresent()) {
      Duration duration =
          Timestamps.between(
              event.getExecutedActionMetadata().get().getWorkerStartTimestamp(),
              event.getExecutedActionMetadata().get().getWorkerCompletedTimestamp());
      remoteCpuTime.add(
          TimeUnit.SECONDS.toMillis(duration.getSeconds())
              + TimeUnit.NANOSECONDS.toMillis(duration.getNanos()));

      Duration queueDuration =
          Timestamps.between(
              event.getExecutedActionMetadata().get().getQueuedTimestamp(),
              event.getExecutedActionMetadata().get().getWorkerStartTimestamp());
      remoteQueueTime.add(
          TimeUnit.SECONDS.toMillis(queueDuration.getSeconds())
              + TimeUnit.NANOSECONDS.toMillis(queueDuration.getNanos()));

      Duration totalDuration =
          Timestamps.between(
              event.getExecutedActionMetadata().get().getQueuedTimestamp(),
              event.getExecutedActionMetadata().get().getOutputUploadCompletedTimestamp());
      totalRemoteTime.add(
          TimeUnit.SECONDS.toMillis(totalDuration.getSeconds())
              + TimeUnit.NANOSECONDS.toMillis(totalDuration.getNanos()));
    }
  }

  /** Event specific subscriber method. */
  @Subscribe
  public void onActionEventStarted(RemoteExecutionActionEvent.Started event) {
    hasFirstRemoteActionStarted.set(true);
    getStateCount(State.WAITING).decrement();
    getStateCount(event.getState()).increment();
  }

  public LongAdder getStateCount(State waiting) {
    return Objects.requireNonNull(actionStateCount.get(waiting));
  }

  /** Event specific subscriber method. */
  @Subscribe
  public void onActionEventFinished(RemoteExecutionActionEvent.Finished event) {
    hasFirstRemoteActionStarted.set(true);
    getStateCount(State.WAITING).increment();
    getStateCount(event.getStartedEvent().getState()).decrement();
  }

  /** Events from the LocalFallback stats. */
  @Subscribe
  public void onLocalFallbackEventFinished(LocalFallbackEvent.Finished event) {
    localFallbackTotalExecutions.increment();

    if (event.getLocalResult() != Result.NOT_RUN) {
      localFallbackLocalExecutions.increment();
    }

    if (event.getLocalResult() == Result.SUCCESS) {
      localFallbackSuccessfulLocalExecutions.increment();
    }
  }

  @Override
  public ImmutableMap<State, Integer> getActionsPerState() {
    return ImmutableMap.copyOf(
        actionStateCount.entrySet().stream()
            .collect(Collectors.toMap(Entry::getKey, entry -> entry.getValue().intValue())));
  }

  @Override
  public int getCasDownloads() {
    return downloads.intValue();
  }

  @Override
  public long getCasDownloadSizeBytes() {
    return donwloadBytes.sum();
  }

  @Override
  public int getCasUploads() {
    return uploads.intValue();
  }

  @Override
  public long getCasUploadSizeBytes() {
    return uploadBytes.intValue();
  }

  @Override
  public int getTotalRulesBuilt() {
    return totalBuildRules.intValue();
  }

  @Override
  public LocalFallbackStats getLocalFallbackStats() {
    return LocalFallbackStats.builder()
        .setLocallyExecutedRules(localFallbackLocalExecutions.intValue())
        .setLocallySuccessfulRules(localFallbackSuccessfulLocalExecutions.intValue())
        .setTotalExecutedRules(localFallbackTotalExecutions.intValue())
        .build();
  }

  @Override
  public long getRemoteCpuTimeMs() {
    return remoteCpuTime.sum();
  }

  @Override
  public long getRemoteQueueTimeMs() {
    return remoteQueueTime.sum();
  }

  @Override
  public long getTotalRemoteTimeMs() {
    return totalRemoteTime.sum();
  }

  @Override
  public ImmutableMap<String, String> exportFieldsToMap() {
    ImmutableMap.Builder<String, String> retval = ImmutableMap.builderWithExpectedSize(16);

    retval
        .put("cas_downloads_count", Integer.toString(getCasDownloads()))
        .put("cas_downloads_bytes", Long.toString(getCasDownloadSizeBytes()))
        .put("cas_uploads_count", Integer.toString(getCasUploads()))
        .put("cas_uploads_bytes", Long.toString(getCasUploadSizeBytes()))
        .put("localfallback_totally_executed_rules", localFallbackTotalExecutions.toString())
        .put("localfallback_locally_executed_rules", localFallbackLocalExecutions.toString())
        .put(
            "localfallback_locally_successful_executed_rules",
            localFallbackSuccessfulLocalExecutions.toString())
        .put("remote_cpu_time_ms", Long.toString(getRemoteCpuTimeMs()))
        .put("remote_queue_time_ms", Long.toString(getRemoteQueueTimeMs()))
        .put("remote_total_time_ms", Long.toString(getTotalRemoteTimeMs()));

    for (ImmutableMap.Entry<State, Integer> entry : getActionsPerState().entrySet()) {
      retval.put(
          String.format("remote_state_%s_count", entry.getKey().getAbbreviateName()),
          Integer.toString(entry.getValue()));
    }

    return retval.build();
  }
}
