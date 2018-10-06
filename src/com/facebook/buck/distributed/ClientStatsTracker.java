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

import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.CREATE_DISTRIBUTED_BUILD;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.LOCAL_FILE_HASH_COMPUTATION;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.LOCAL_GRAPH_CONSTRUCTION;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.LOCAL_PREPARATION;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.LOCAL_TARGET_GRAPH_SERIALIZATION;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.LOCAL_UPLOAD_FROM_DIR_CACHE;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.MATERIALIZE_SLAVE_LOGS;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.PERFORM_DISTRIBUTED_BUILD;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.PERFORM_LOCAL_BUILD;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.POST_BUILD_ANALYSIS;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.POST_DISTRIBUTED_BUILD_LOCAL_STEPS;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.PUBLISH_BUILD_SLAVE_FINISHED_STATS;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.SET_BUCK_VERSION;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.UPLOAD_BUCK_DOT_FILES;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.UPLOAD_MISSING_FILES;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.UPLOAD_TARGET_GRAPH;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import javax.annotation.concurrent.GuardedBy;

/** Tracks client side statistics. */
public class ClientStatsTracker {
  public enum DistBuildClientStat {
    LOCAL_PREPARATION, // Measures everything that happens before starting distributed build
    LOCAL_GRAPH_CONSTRUCTION,
    LOCAL_FILE_HASH_COMPUTATION,
    LOCAL_TARGET_GRAPH_SERIALIZATION,
    LOCAL_UPLOAD_FROM_DIR_CACHE,
    PERFORM_DISTRIBUTED_BUILD,
    PERFORM_LOCAL_BUILD,
    POST_DISTRIBUTED_BUILD_LOCAL_STEPS,
    PUBLISH_BUILD_SLAVE_FINISHED_STATS,
    POST_BUILD_ANALYSIS,
    CREATE_DISTRIBUTED_BUILD,
    UPLOAD_MISSING_FILES,
    UPLOAD_TARGET_GRAPH,
    UPLOAD_BUCK_DOT_FILES,
    SET_BUCK_VERSION,
    MATERIALIZE_SLAVE_LOGS,
  }

  public static final String PENDING_STAMPEDE_ID = "PENDING_STAMPEDE_ID";

  @GuardedBy("this")
  private final Map<DistBuildClientStat, Stopwatch> stopwatchesByType = new HashMap<>();

  @GuardedBy("this")
  private final Map<DistBuildClientStat, Long> durationsMsByType = new HashMap<>();

  private volatile String stampedeId = PENDING_STAMPEDE_ID;

  private volatile OptionalInt distributedBuildExitCode = OptionalInt.empty();

  private volatile Optional<Boolean> isLocalFallbackBuildEnabled = Optional.empty();

  private volatile boolean performedLocalBuild = false;

  private volatile boolean performedRacingBuild = false;

  private volatile boolean racingBuildFinishedFirst = false;

  private volatile boolean buckClientError = false;

  private volatile OptionalInt localBuildExitCode = OptionalInt.empty();

  private volatile Optional<Long> missingFilesUploadedCount = Optional.empty();

  private volatile Optional<Long> missingRulesUploadedFromDirCacheCount = Optional.empty();

  private volatile Optional<String> buckClientErrorMessage = Optional.empty();

  private volatile String userOrInferredBuildLabel;

  private final String minionType;

  public ClientStatsTracker(String userOrInferredBuildLabel, String minionType) {
    this.userOrInferredBuildLabel = userOrInferredBuildLabel;
    this.minionType = minionType;
  }

  public void setUserOrInferredBuildLabel(String userOrInferredBuildLabel) {
    this.userOrInferredBuildLabel = userOrInferredBuildLabel;
  }

  @GuardedBy("this")
  private void generateStatsPreconditionChecksNoException() {
    // Unless there was an exception, we expect all the following fields to be present.
    Preconditions.checkArgument(
        distributedBuildExitCode.isPresent(), "distributedBuildExitCode not set");
    Preconditions.checkArgument(
        isLocalFallbackBuildEnabled.isPresent(), "isLocalFallbackBuildEnabled not set");

    if (performedLocalBuild) {
      Preconditions.checkArgument(localBuildExitCode.isPresent());
      Objects.requireNonNull(
          durationsMsByType.get(PERFORM_LOCAL_BUILD),
          "No time was recorded for stat: " + PERFORM_LOCAL_BUILD);
    }
  }

  @GuardedBy("this")
  private Optional<Long> getDurationOrEmpty(DistBuildClientStat stat) {
    if (!durationsMsByType.containsKey(stat)) {
      return Optional.empty();
    }

    return Optional.of(durationsMsByType.get(stat));
  }

  public synchronized DistBuildClientStats generateStats() {
    if (!buckClientError) {
      generateStatsPreconditionChecksNoException();
    } else {
      // Buck client threw an exception, so we will log on a best effort basis.
      Preconditions.checkArgument(buckClientErrorMessage.isPresent());
    }

    DistBuildClientStats.Builder builder =
        DistBuildClientStats.builder()
            .setStampedeId(stampedeId)
            .setPerformedLocalBuild(performedLocalBuild)
            .setBuckClientError(buckClientError)
            .setUserOrInferredBuildLabel(userOrInferredBuildLabel)
            .setMinionType(minionType);

    builder.setDistributedBuildExitCode(distributedBuildExitCode);
    builder.setLocalFallbackBuildEnabled(isLocalFallbackBuildEnabled);
    builder.setBuckClientErrorMessage(buckClientErrorMessage);

    if (performedLocalBuild) {
      builder.setLocalBuildExitCode(localBuildExitCode);
      builder.setLocalBuildDurationMs(getDurationOrEmpty(PERFORM_LOCAL_BUILD));
      builder.setPostBuildAnalysisDurationMs(getDurationOrEmpty(POST_BUILD_ANALYSIS));
    }

    builder.setPerformedRacingBuild(performedRacingBuild);
    if (performedRacingBuild) {
      builder.setRacingBuildFinishedFirst(racingBuildFinishedFirst);
    }

    builder.setLocalPreparationDurationMs(getDurationOrEmpty(LOCAL_PREPARATION));
    builder.setLocalGraphConstructionDurationMs(getDurationOrEmpty(LOCAL_GRAPH_CONSTRUCTION));
    builder.setLocalFileHashComputationDurationMs(getDurationOrEmpty(LOCAL_FILE_HASH_COMPUTATION));
    builder.setLocalTargetGraphSerializationDurationMs(
        getDurationOrEmpty(LOCAL_TARGET_GRAPH_SERIALIZATION));
    builder.setLocalUploadFromDirCacheDurationMs(getDurationOrEmpty(LOCAL_UPLOAD_FROM_DIR_CACHE));
    builder.setPostDistBuildLocalStepsDurationMs(
        getDurationOrEmpty(POST_DISTRIBUTED_BUILD_LOCAL_STEPS));
    builder.setPerformDistributedBuildDurationMs(getDurationOrEmpty(PERFORM_DISTRIBUTED_BUILD));
    builder.setCreateDistributedBuildDurationMs(getDurationOrEmpty(CREATE_DISTRIBUTED_BUILD));
    builder.setUploadMissingFilesDurationMs(getDurationOrEmpty(UPLOAD_MISSING_FILES));
    builder.setUploadTargetGraphDurationMs(getDurationOrEmpty(UPLOAD_TARGET_GRAPH));
    builder.setUploadBuckDotFilesDurationMs(getDurationOrEmpty(UPLOAD_BUCK_DOT_FILES));
    builder.setSetBuckVersionDurationMs(getDurationOrEmpty(SET_BUCK_VERSION));
    builder.setMaterializeSlaveLogsDurationMs(getDurationOrEmpty(MATERIALIZE_SLAVE_LOGS));
    builder.setPublishBuildSlaveFinishedStatsDurationMs(
        getDurationOrEmpty(PUBLISH_BUILD_SLAVE_FINISHED_STATS));

    builder.setMissingRulesUploadedFromDirCacheCount(missingRulesUploadedFromDirCacheCount);
    builder.setMissingFilesUploadedCount(missingFilesUploadedCount);

    return builder.build();
  }

  public void setMissingRulesUploadedFromDirCacheCount(long missingRulesUploadedFromDirCacheCount) {
    this.missingRulesUploadedFromDirCacheCount = Optional.of(missingRulesUploadedFromDirCacheCount);
  }

  public void setMissingFilesUploadedCount(long missingFilesUploadedCount) {
    this.missingFilesUploadedCount = Optional.of(missingFilesUploadedCount);
  }

  public void setPerformedLocalBuild(boolean performedLocalBuild) {
    this.performedLocalBuild = performedLocalBuild;
  }

  public void setPerformedRacingBuild(boolean performedRacingBuild) {
    this.performedRacingBuild = performedRacingBuild;
  }

  public void setRacingBuildFinishedFirst(boolean racingBuildFinishedFirst) {
    this.racingBuildFinishedFirst = racingBuildFinishedFirst;
  }

  public void setLocalBuildExitCode(int localBuildExitCode) {
    this.localBuildExitCode = OptionalInt.of(localBuildExitCode);
  }

  public void setStampedeId(String stampedeId) {
    this.stampedeId = stampedeId;
  }

  public synchronized void setDistributedBuildExitCode(int distributedBuildExitCode) {
    this.distributedBuildExitCode = OptionalInt.of(distributedBuildExitCode);
  }

  public void setIsLocalFallbackBuildEnabled(boolean isLocalFallbackBuildEnabled) {
    this.isLocalFallbackBuildEnabled = Optional.of(isLocalFallbackBuildEnabled);
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

  public synchronized void startTimer(DistBuildClientStat stat) {
    if (stopwatchesByType.containsKey(stat)) {
      return;
    }
    Stopwatch stopwatch = Stopwatch.createStarted();
    stopwatchesByType.put(stat, stopwatch);
  }

  public synchronized void stopTimer(DistBuildClientStat stat) {
    Objects.requireNonNull(
        stopwatchesByType.get(stat),
        "Cannot stop timer for stat: [" + stat + "] as it was not started.");

    Stopwatch stopwatch = stopwatchesByType.get(stat);
    stopwatch.stop();
    long elapsed = stopwatch.elapsed(TimeUnit.MILLISECONDS);
    durationsMsByType.put(stat, elapsed);
  }
}
