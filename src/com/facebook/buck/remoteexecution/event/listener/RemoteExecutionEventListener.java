/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
import com.facebook.buck.remoteexecution.event.RemoteExecutionSessionEvent;
import com.facebook.buck.remoteexecution.event.RemoteExecutionStatsProvider;
import com.facebook.buck.remoteexecution.proto.ExecutedActionInfo;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;
import com.google.protobuf.Duration;
import com.google.protobuf.util.Timestamps;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
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
  private final LongAdder downloadBytes;
  private final LongAdder uploads;
  private final LongAdder uploadBytes;

  private final LongAdder remoteCpuTimeMs;
  private final LongAdder remoteQueueTimeMs;
  private final LongAdder totalRemoteTimeMs;

  // mem used to execute action on remote workers
  private final LongAdder remoteMemUsed;
  // total mem was available on remote workers
  private final LongAdder remoteTotalAvailableMem;
  // total mem was available for tasks
  private final LongAdder remoteTaskTotalAvailableMem;

  private final AtomicBoolean hasFirstRemoteActionStarted;

  private final LongAdder localFallbackTotalExecutions;
  private final LongAdder localFallbackLocalExecutions;
  private final LongAdder localFallbackSuccessfulLocalExecutions;

  private static class REStatsDumpContext {
    private final Optional<String> reStatsDumpPath;
    private final JsonFactory jsonFactory;
    private Optional<JsonGenerator> writer;

    public REStatsDumpContext(Optional<String> reStatsDumpPath) {
      this.reStatsDumpPath = reStatsDumpPath;
      this.jsonFactory = new JsonFactory();
    }

    public void append(
        long startTs,
        long endTs,
        boolean elastic,
        long memUsed,
        long memAvailable,
        long totalMemAvailable,
        long cpuUsedUsec) {
      // if the result was loaded from the action cache we don't need to log this
      if (startTs == 0) {
        return;
      }

      writer.ifPresent(
          w -> {
            try {
              synchronized (w) {
                w.writeStartObject();

                w.writeNumberField("start", startTs);
                w.writeNumberField("end", endTs);
                w.writeNumberField("memUsed", memUsed);
                w.writeNumberField("memAvailable", memAvailable);
                w.writeNumberField("hostTotalMem", totalMemAvailable);
                w.writeNumberField("cpuUsedUsec", cpuUsedUsec);
                w.writeBooleanField("elastic", elastic);

                w.writeEndObject();
              }

            } catch (IOException ignored) {
            }
          });
    }

    public void initialize() {
      try {
        // non-functional way ti simplify excepction handling
        if (reStatsDumpPath.isPresent()) {
          writer =
              Optional.of(
                  jsonFactory.createGenerator(new FileOutputStream(reStatsDumpPath.get(), false)));
          writer.get().writeStartArray();
        } else {
          writer = Optional.empty();
        }
      } catch (IOException ignored) {
        // Unable to create the FOS
        writer = Optional.empty();
      }
    }

    public void close() {
      writer.ifPresent(
          w -> {
            try {
              w.writeEndArray();
              w.close();
            } catch (IOException ignored) {
            }
          });
    }
  }

  private final REStatsDumpContext reStatsDumpContext;

  public RemoteExecutionEventListener(Optional<String> reStatsDumpPath) {
    this.downloads = new LongAdder();
    this.downloadBytes = new LongAdder();
    this.uploads = new LongAdder();
    this.uploadBytes = new LongAdder();
    this.remoteCpuTimeMs = new LongAdder();
    this.remoteQueueTimeMs = new LongAdder();
    this.totalRemoteTimeMs = new LongAdder();
    this.totalBuildRules = new LongAdder();
    this.hasFirstRemoteActionStarted = new AtomicBoolean(false);

    localFallbackTotalExecutions = new LongAdder();
    localFallbackLocalExecutions = new LongAdder();
    localFallbackSuccessfulLocalExecutions = new LongAdder();

    remoteMemUsed = new LongAdder();
    remoteTotalAvailableMem = new LongAdder();
    remoteTaskTotalAvailableMem = new LongAdder();

    reStatsDumpContext = new REStatsDumpContext(reStatsDumpPath);

    this.actionStateCount = Maps.newConcurrentMap();
    for (State state : RemoteExecutionActionEvent.State.values()) {
      actionStateCount.put(state, new LongAdder());
    }
  }

  /** Mark the start of a Remote Execution session */
  @Subscribe
  public void onRemoteExecutionSessionStarted(
      @SuppressWarnings("unused") RemoteExecutionSessionEvent.Started event) {
    reStatsDumpContext.initialize();
  }

  @Subscribe
  public void onRemoteExecutionSessionFinished(
      @SuppressWarnings("unused") RemoteExecutionSessionEvent.Finished event) {
    reStatsDumpContext.close();
  }

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
    downloadBytes.add(event.getStartedEvent().getSizeBytes());
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

    if (event.getCachedResult()) {
      getStateCount(State.LOADED_FROM_CACHE).increment();
    }

    if (event.getExecutedActionMetadata().isPresent()) {
      ExecutedActionInfo executedActionInfo =
          event.getRemoteExecutionMetadata().get().getExecutedActionInfo();
      remoteCpuTimeMs.add(TimeUnit.MICROSECONDS.toMillis(executedActionInfo.getCpuStatUsageUsec()));

      Duration queueDuration =
          Timestamps.between(
              event.getExecutedActionMetadata().get().getQueuedTimestamp(),
              event.getExecutedActionMetadata().get().getWorkerStartTimestamp());
      remoteQueueTimeMs.add(
          TimeUnit.SECONDS.toMillis(queueDuration.getSeconds())
              + TimeUnit.NANOSECONDS.toMillis(queueDuration.getNanos()));

      Duration totalDuration =
          Timestamps.between(
              event.getExecutedActionMetadata().get().getWorkerStartTimestamp(),
              event.getExecutedActionMetadata().get().getWorkerCompletedTimestamp());
      totalRemoteTimeMs.add(
          TimeUnit.SECONDS.toMillis(totalDuration.getSeconds())
              + TimeUnit.NANOSECONDS.toMillis(totalDuration.getNanos()));

      remoteMemUsed.add(executedActionInfo.getMaxUsedMem());
      remoteTotalAvailableMem.add(executedActionInfo.getHostTotalMem());
      remoteTaskTotalAvailableMem.add(executedActionInfo.getTaskTotalMem());

      reStatsDumpContext.append(
          Timestamps.toMillis(event.getExecutedActionMetadata().get().getWorkerStartTimestamp()),
          Timestamps.toMillis(
              event.getExecutedActionMetadata().get().getWorkerCompletedTimestamp()),
          executedActionInfo.getExecutedOnElasticCapacity(),
          executedActionInfo.getMaxUsedMem(),
          executedActionInfo.getTaskTotalMem(),
          executedActionInfo.getHostTotalMem(),
          executedActionInfo.getCpuStatUsageUsec());
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
    if (event.getRemoteResult() != Result.CANCELLED) {
      localFallbackTotalExecutions.increment();
    }

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
    return downloadBytes.sum();
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
    return remoteCpuTimeMs.sum();
  }

  @Override
  public long getRemoteQueueTimeMs() {
    return remoteQueueTimeMs.sum();
  }

  @Override
  public long getTotalRemoteTimeMs() {
    return totalRemoteTimeMs.sum();
  }

  @Override
  public float getWeightedMemUsage() {
    long totalAvailableMem = remoteTotalAvailableMem.sum();
    return totalAvailableMem == 0
        ? .0f
        : (float) (remoteMemUsed.doubleValue() / remoteTaskTotalAvailableMem.sum());
  }

  @Override
  public long getTotalUsedRemoteMemory() {
    return remoteMemUsed.sum();
  }

  @Override
  public long getTotalAvailableRemoteMemory() {
    return remoteTotalAvailableMem.sum();
  }

  @Override
  public long getTaskTotalAvailableRemoteMemory() {
    return remoteTaskTotalAvailableMem.sum();
  }

  @Override
  public ImmutableMap<String, String> exportFieldsToMap() {
    ImmutableMap.Builder<String, String> retval = ImmutableMap.builderWithExpectedSize(32);

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
        .put("remote_total_time_ms", Long.toString(getTotalRemoteTimeMs()))
        .put("remote_total_used_mem", Long.toString(getTotalUsedRemoteMemory()))
        .put("remote_total_available_used_mem", Long.toString(getTotalAvailableRemoteMemory()))
        .put(
            "remote_task_total_available_used_mem",
            Long.toString(getTaskTotalAvailableRemoteMemory()))
        .put("remote_weighted_mem_usage", Float.toString(getWeightedMemUsage()));

    for (ImmutableMap.Entry<State, Integer> entry : getActionsPerState().entrySet()) {
      retval.put(
          String.format("remote_state_%s_count", entry.getKey().getAbbreviateName()),
          Integer.toString(entry.getValue()));
    }

    return retval.build();
  }
}
