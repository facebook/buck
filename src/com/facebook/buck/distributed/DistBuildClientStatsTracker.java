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
package com.facebook.buck.distributed;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Maps;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.annotation.concurrent.GuardedBy;

public class DistBuildClientStatsTracker {
  @VisibleForTesting
  protected enum DistBuildClientStat {
    PERFORM_DISTRIBUTED_BUILD,
    PERFORM_LOCAL_BUILD,
    CREATE_DISTRIBUTED_BUILD,
    UPLOAD_MISSING_FILES,
    UPLOAD_TARGET_GRAPH,
    UPLOAD_BUCK_DOT_FILES,
    SET_BUCK_VERSION,
    MATERIALIZE_SLAVE_LOGS,
  }

  @GuardedBy("this")
  private final Map<DistBuildClientStat, Stopwatch> stopwatchesByType = Maps.newHashMap();

  @GuardedBy("this")
  private final Map<DistBuildClientStat, Long> durationsMsByType = Maps.newHashMap();

  private volatile Optional<String> stampedeId = Optional.empty();

  private volatile Optional<Integer> distributedBuildExitCode = Optional.empty();

  private volatile Optional<Boolean> isLocalFallbackBuildEnabled = Optional.empty();

  private volatile boolean performedLocalBuild = false;

  private volatile boolean buckClientError = false;

  private volatile Optional<Integer> localBuildExitCode = Optional.empty();

  private volatile Optional<Long> missingFilesUploadedCount = Optional.empty();

  private volatile Optional<String> buckClientErrorMessage = Optional.empty();

  private void generateStatsPreconditionChecksNoException() {
    // Unless there was an exception, we expect all the following fields to be present.
    Preconditions.checkArgument(
        distributedBuildExitCode.isPresent(), "distributedBuildExitCode not set");
    Preconditions.checkArgument(
        isLocalFallbackBuildEnabled.isPresent(), "isLocalFallbackBuildEnabled not set");
    Preconditions.checkArgument(
        missingFilesUploadedCount.isPresent(), "missingFilesUploadedCount not set");

    if (performedLocalBuild) {
      Preconditions.checkArgument(localBuildExitCode.isPresent());
      Preconditions.checkNotNull(
          durationsMsByType.get(DistBuildClientStat.PERFORM_LOCAL_BUILD),
          "No time was recorded for stat: " + DistBuildClientStat.PERFORM_LOCAL_BUILD);
    }

    Preconditions.checkNotNull(
        durationsMsByType.get(DistBuildClientStat.PERFORM_DISTRIBUTED_BUILD),
        "No time was recorded for stat: " + DistBuildClientStat.PERFORM_DISTRIBUTED_BUILD);
    Preconditions.checkNotNull(
        durationsMsByType.get(DistBuildClientStat.CREATE_DISTRIBUTED_BUILD),
        "No time was recorded for stat: " + DistBuildClientStat.CREATE_DISTRIBUTED_BUILD);
    Preconditions.checkNotNull(
        durationsMsByType.get(DistBuildClientStat.UPLOAD_MISSING_FILES),
        "No time was recorded for stat: " + DistBuildClientStat.UPLOAD_MISSING_FILES);
    Preconditions.checkNotNull(
        durationsMsByType.get(DistBuildClientStat.UPLOAD_TARGET_GRAPH),
        "No time was recorded for stat: " + DistBuildClientStat.UPLOAD_TARGET_GRAPH);
    Preconditions.checkNotNull(
        durationsMsByType.get(DistBuildClientStat.UPLOAD_BUCK_DOT_FILES),
        "No time was recorded for stat: " + DistBuildClientStat.UPLOAD_BUCK_DOT_FILES);
    Preconditions.checkNotNull(
        durationsMsByType.get(DistBuildClientStat.SET_BUCK_VERSION),
        "No time was recorded for stat: " + DistBuildClientStat.SET_BUCK_VERSION);

    // MATERIALIZE_SLAVE_LOGS is optional even if no buck client errors.
  }

  private Optional<Long> getDurationOrEmpty(DistBuildClientStat stat) {
    if (!durationsMsByType.containsKey(stat)) {
      return Optional.empty();
    }

    return Optional.of(durationsMsByType.get(stat));
  }

  public synchronized DistBuildClientStats generateStats() {
    // Without a Stampede ID there is nothing useful to record.
    Preconditions.checkArgument(stampedeId.isPresent());

    if (!buckClientError) {
      generateStatsPreconditionChecksNoException();
    } else {
      // Buck client threw an exception, so we will log on a best effort basis.
      Preconditions.checkArgument(buckClientErrorMessage.isPresent());
    }

    DistBuildClientStats.Builder builder =
        DistBuildClientStats.builder()
            .setStampedeId(stampedeId.get())
            .setPerformedLocalBuild(performedLocalBuild)
            .setBuckClientError(buckClientError);

    builder.setDistributedBuildExitCode(distributedBuildExitCode);
    builder.setLocalFallbackBuildEnabled(isLocalFallbackBuildEnabled);
    builder.setBuckClientErrorMessage(buckClientErrorMessage);

    if (performedLocalBuild) {
      builder.setLocalBuildExitCode(localBuildExitCode);
      builder.setLocalBuildDurationMs(getDurationOrEmpty(DistBuildClientStat.PERFORM_LOCAL_BUILD));
    }

    builder.setPerformDistributedBuildDurationMs(
        getDurationOrEmpty(DistBuildClientStat.PERFORM_DISTRIBUTED_BUILD));
    builder.setCreateDistributedBuildDurationMs(
        getDurationOrEmpty(DistBuildClientStat.CREATE_DISTRIBUTED_BUILD));
    builder.setUploadMissingFilesDurationMs(
        getDurationOrEmpty(DistBuildClientStat.UPLOAD_MISSING_FILES));
    builder.setUploadTargetGraphDurationMs(
        getDurationOrEmpty(DistBuildClientStat.UPLOAD_TARGET_GRAPH));
    builder.setUploadBuckDotFilesDurationMs(
        getDurationOrEmpty(DistBuildClientStat.UPLOAD_BUCK_DOT_FILES));
    builder.setSetBuckVersionDurationMs(getDurationOrEmpty(DistBuildClientStat.SET_BUCK_VERSION));

    builder.setMaterializeSlaveLogsDurationMs(
        getDurationOrEmpty(DistBuildClientStat.MATERIALIZE_SLAVE_LOGS));

    builder.setMissingFilesUploadedCount(missingFilesUploadedCount);

    return builder.build();
  }

  public void setMissingFilesUploadedCount(long missingFilesUploadedCount) {
    this.missingFilesUploadedCount = Optional.of(missingFilesUploadedCount);
  }

  public void setPerformedLocalBuild(boolean performedLocalBuild) {
    this.performedLocalBuild = performedLocalBuild;
  }

  public void setLocalBuildExitCode(int localBuildExitCode) {
    this.localBuildExitCode = Optional.of(localBuildExitCode);
  }

  public void setStampedeId(String stampedeId) {
    this.stampedeId = Optional.of(stampedeId);
  }

  public void setDistributedBuildExitCode(int distributedBuildExitCode) {
    this.distributedBuildExitCode = Optional.of(distributedBuildExitCode);
  }

  public void setIsLocalFallbackBuildEnabled(boolean isLocalFallbackBuildEnabled) {
    this.isLocalFallbackBuildEnabled = Optional.of(isLocalFallbackBuildEnabled);
  }

  public void startCreateBuildTimer() {
    startTimer(DistBuildClientStat.CREATE_DISTRIBUTED_BUILD);
  }

  public void stopCreateBuildTimer() {
    stopTimer(DistBuildClientStat.CREATE_DISTRIBUTED_BUILD);
  }

  public void startUploadMissingFilesTimer() {
    startTimer(DistBuildClientStat.UPLOAD_MISSING_FILES);
  }

  public void stopUploadMissingFilesTimer() {
    stopTimer(DistBuildClientStat.UPLOAD_MISSING_FILES);
  }

  public void startUploadTargetGraphTimer() {
    startTimer(DistBuildClientStat.UPLOAD_TARGET_GRAPH);
  }

  public void stopUploadTargetGraphTimer() {
    stopTimer(DistBuildClientStat.UPLOAD_TARGET_GRAPH);
  }

  public void startUploadBuckDotFilesTimer() {
    startTimer(DistBuildClientStat.UPLOAD_BUCK_DOT_FILES);
  }

  public void stopUploadBuckDotFilesTimer() {
    stopTimer(DistBuildClientStat.UPLOAD_BUCK_DOT_FILES);
  }

  public void startSetBuckVersionTimer() {
    startTimer(DistBuildClientStat.SET_BUCK_VERSION);
  }

  public void stopSetBuckVersionTimer() {
    stopTimer(DistBuildClientStat.SET_BUCK_VERSION);
  }

  public void startPerformDistributedBuildTimer() {
    startTimer(DistBuildClientStat.PERFORM_DISTRIBUTED_BUILD);
  }

  public void stopPerformDistributedBuildTimer() {
    stopTimer(DistBuildClientStat.PERFORM_DISTRIBUTED_BUILD);
  }

  public void startPerformLocalBuildTimer() {
    startTimer(DistBuildClientStat.PERFORM_LOCAL_BUILD);
  }

  public void stopPerformLocalBuildTimer() {
    stopTimer(DistBuildClientStat.PERFORM_LOCAL_BUILD);
  }

  public void startMaterializeSlaveLogsTimer() {
    startTimer(DistBuildClientStat.MATERIALIZE_SLAVE_LOGS);
  }

  public void stopMaterializeSlaveLogsTimer() {
    stopTimer(DistBuildClientStat.MATERIALIZE_SLAVE_LOGS);
  }

  public boolean hasStampedeId() {
    return stampedeId.isPresent();
  }

  public void setBuckClientError(boolean buckClientError) {
    this.buckClientError = buckClientError;
  }

  public void setBuckClientErrorMessage(String buckClientErrorMessage) {
    this.buckClientErrorMessage = Optional.of(buckClientErrorMessage);
  }

  @VisibleForTesting
  protected synchronized void setDurationMs(DistBuildClientStat stat, long duration) {
    durationsMsByType.put(stat, duration);
  }

  private synchronized void startTimer(DistBuildClientStat stat) {
    Stopwatch stopwatch = Stopwatch.createStarted();
    stopwatchesByType.put(stat, stopwatch);
  }

  private synchronized void stopTimer(DistBuildClientStat stat) {
    Preconditions.checkNotNull(
        stopwatchesByType.get(stat),
        "Cannot stop timer for stat: [" + stat + "] as it was not started.");

    Stopwatch stopwatch = stopwatchesByType.get(stat);
    stopwatch.stop();
    long elapsed = stopwatch.elapsed(TimeUnit.MILLISECONDS);
    durationsMsByType.put(stat, elapsed);
  }
}
