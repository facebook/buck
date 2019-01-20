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

import com.google.common.base.Strings;
import org.junit.Assert;
import org.junit.Test;

public class ClientStatsTrackerTest {
  private static final String STAMPEDE_ID_ONE = "stampedeIdOne";
  private static final int DISTRIBUTED_BUILD_EXIT_CODE = 0;
  private static final int LOCAL_BUILD_EXIT_CODE = 1;
  private static final boolean IS_LOCAL_FALLBACK_BUILD_ENABLED = true;
  private static final int POST_DISTRIBUTED_BUILD_LOCAL_STEPS_DURATION = 11;
  private static final int PERFORM_DISTRIBUTED_BUILD_DURATION = 1;
  private static final int PERFORM_LOCAL_BUILD_DURATION = 2;
  private static final int CREATE_DISTRIBUTED_BUILD_DURATION = 3;
  private static final int UPLOAD_MISSING_FILES_DURATION = 4;
  private static final int UPLOAD_TARGET_GRAPH_DURATION = 5;
  private static final int UPLOAD_BUCK_DOT_FILES_DURATION = 6;
  private static final int SET_BUCK_VERSION_DURATION = 7;
  private static final int MATERIALIZE_SLAVE_LOGS_DURATION = 8;
  private static final int LOCAL_PREPARATION_DURATION = 9;
  private static final int LOCAL_GRAPH_CONSTRUCTION_DURATION = 10;
  private static final int POST_BUILD_ANALYSIS_DURATION_MS = 11;
  private static final int PUBLISH_BUILD_SLAVE_FINISHED_STATS_DURATION_MS = 12;
  private static final int LOCAL_FILE_HASH_COMPUTATION_DURATION_MS = 13;
  private static final int LOCAL_TARGET_GRAPH_SERIALIZATION_DURATION_MS = 14;
  private static final int MISSING_FILES_UPLOADED_COUNT = 2001;
  private static final String BUCK_CLIENT_ERROR_MESSAGE = "Some error message";
  private static final String BUILD_LABEL = "unit_test";
  private static final String MINION_TYPE = "standard_type";

  @Test
  public void testGenerateStatsDoesNotThrowOnEmptyStampedeId() {
    ClientStatsTracker tracker = new ClientStatsTracker(BUILD_LABEL, MINION_TYPE);
    tracker.setDistributedBuildExitCode(0);
    tracker.setIsLocalFallbackBuildEnabled(false);
    DistBuildClientStats stats = tracker.generateStats();
    Assert.assertNotNull(stats);
    Assert.assertFalse(Strings.isNullOrEmpty(stats.stampedeId()));
  }

  @Test(expected = java.lang.IllegalArgumentException.class)
  public void testGenerateThrowsExceptionWhenStatsNotRecorded() {
    ClientStatsTracker tracker = new ClientStatsTracker(BUILD_LABEL, MINION_TYPE);
    tracker.generateStats();
  }

  @Test
  public void testGenerateSucceedsWhenAllStandardStatsSet() {
    ClientStatsTracker tracker = new ClientStatsTracker(BUILD_LABEL, MINION_TYPE);
    initializeCommonStats(tracker);
    DistBuildClientStats stats = tracker.generateStats();
    assertCommonStats(stats);
  }

  @Test
  public void testBuildLabelOverrideWorks() {
    ClientStatsTracker tracker = new ClientStatsTracker(BUILD_LABEL, MINION_TYPE);
    String newLabel = "this-label-is-better";
    tracker.setUserOrInferredBuildLabel(newLabel);
    initializeCommonStats(tracker);
    DistBuildClientStats stats = tracker.generateStats();
    Assert.assertEquals(newLabel, stats.userOrInferredBuildLabel());
  }

  @Test(expected = java.lang.IllegalArgumentException.class)
  public void testGenerateFailsWhenLocalBuildButMissingLocalBuildFields() {
    ClientStatsTracker tracker = new ClientStatsTracker(BUILD_LABEL, MINION_TYPE);
    initializeCommonStats(tracker);
    tracker.setPerformedLocalBuild(true);
    tracker.generateStats();
  }

  @Test(expected = java.lang.IllegalArgumentException.class)
  public void testGenerateFailsWhenNoStampedeId() {
    ClientStatsTracker tracker = new ClientStatsTracker(BUILD_LABEL, MINION_TYPE);
    tracker.generateStats();
  }

  @Test(expected = java.lang.IllegalArgumentException.class)
  public void testGenerateFailsWhenNoErrorButRequiredFieldMissing() {
    ClientStatsTracker tracker = new ClientStatsTracker(BUILD_LABEL, MINION_TYPE);
    tracker.setStampedeId(STAMPEDE_ID_ONE);
    tracker.generateStats();
  }

  @Test
  public void testGeneratesPartialResultWhenErrorButRequiredFields() {
    ClientStatsTracker tracker = new ClientStatsTracker(BUILD_LABEL, MINION_TYPE);
    tracker.setBuckClientError(true);
    tracker.setBuckClientErrorMessage(BUCK_CLIENT_ERROR_MESSAGE);

    // The following fields are required for a partial result
    tracker.setStampedeId(STAMPEDE_ID_ONE);
    tracker.setDistributedBuildExitCode(DISTRIBUTED_BUILD_EXIT_CODE);
    tracker.setIsLocalFallbackBuildEnabled(IS_LOCAL_FALLBACK_BUILD_ENABLED);

    DistBuildClientStats stats = tracker.generateStats();

    Assert.assertEquals(STAMPEDE_ID_ONE, stats.stampedeId());
    Assert.assertEquals(
        DISTRIBUTED_BUILD_EXIT_CODE, (long) stats.distributedBuildExitCode().getAsInt());
    Assert.assertEquals(IS_LOCAL_FALLBACK_BUILD_ENABLED, stats.isLocalFallbackBuildEnabled().get());
    Assert.assertEquals(BUCK_CLIENT_ERROR_MESSAGE, stats.buckClientErrorMessage().get());
    Assert.assertEquals(BUILD_LABEL, stats.userOrInferredBuildLabel());
    Assert.assertEquals(MINION_TYPE, stats.minionType());
    Assert.assertTrue(stats.buckClientError());

    Assert.assertFalse(stats.localPreparationDurationMs().isPresent());
    Assert.assertFalse(stats.performDistributedBuildDurationMs().isPresent());
    Assert.assertFalse(stats.createDistributedBuildDurationMs().isPresent());
    Assert.assertFalse(stats.uploadMissingFilesDurationMs().isPresent());
    Assert.assertFalse(stats.uploadTargetGraphDurationMs().isPresent());
    Assert.assertFalse(stats.uploadBuckDotFilesDurationMs().isPresent());
    Assert.assertFalse(stats.setBuckVersionDurationMs().isPresent());
    Assert.assertFalse(stats.materializeSlaveLogsDurationMs().isPresent());
    Assert.assertFalse(stats.missingFilesUploadedCount().isPresent());
  }

  @Test
  public void testGenerateSucceedsWhenLocalBuildAndHasLocalBuildFields() {
    ClientStatsTracker tracker = new ClientStatsTracker(BUILD_LABEL, MINION_TYPE);
    initializeCommonStats(tracker);
    tracker.setPerformedLocalBuild(true);
    tracker.setDurationMs(PERFORM_LOCAL_BUILD, PERFORM_LOCAL_BUILD_DURATION);
    tracker.setDurationMs(POST_BUILD_ANALYSIS, POST_BUILD_ANALYSIS_DURATION_MS);
    tracker.setDurationMs(LOCAL_FILE_HASH_COMPUTATION, LOCAL_FILE_HASH_COMPUTATION_DURATION_MS);
    tracker.setDurationMs(
        LOCAL_TARGET_GRAPH_SERIALIZATION, LOCAL_TARGET_GRAPH_SERIALIZATION_DURATION_MS);
    tracker.setLocalBuildExitCode(LOCAL_BUILD_EXIT_CODE);
    DistBuildClientStats stats = tracker.generateStats();
    assertCommonStats(stats);

    Assert.assertTrue(stats.performedLocalBuild());
    Assert.assertTrue(stats.localBuildExitCode().isPresent());
    Assert.assertEquals(LOCAL_BUILD_EXIT_CODE, (long) stats.localBuildExitCode().getAsInt());
    Assert.assertEquals(PERFORM_LOCAL_BUILD_DURATION, (long) stats.localBuildDurationMs().get());
    Assert.assertEquals(
        POST_BUILD_ANALYSIS_DURATION_MS, (long) stats.postBuildAnalysisDurationMs().get());
    Assert.assertEquals(
        LOCAL_FILE_HASH_COMPUTATION_DURATION_MS,
        (long) stats.localFileHashComputationDurationMs().get());
    Assert.assertEquals(
        LOCAL_TARGET_GRAPH_SERIALIZATION_DURATION_MS,
        (long) stats.localTargetGraphSerializationDurationMs().get());
  }

  @Test
  public void testRacingBuildFinishedFirstIsPopulatedWhenRacingBuildIsPerformed() {
    // Racing build not performed.
    ClientStatsTracker tracker = new ClientStatsTracker(BUILD_LABEL, MINION_TYPE);
    initializeCommonStats(tracker);
    DistBuildClientStats stats = tracker.generateStats();
    Assert.assertFalse(stats.performedRacingBuild());
    Assert.assertFalse(stats.racingBuildFinishedFirst().isPresent());

    // Racing build performed.
    tracker = new ClientStatsTracker(BUILD_LABEL, MINION_TYPE);
    initializeCommonStats(tracker);
    tracker.setPerformedRacingBuild(true);
    tracker.setRacingBuildFinishedFirst(false);
    stats = tracker.generateStats();
    Assert.assertTrue(stats.performedRacingBuild());
    Assert.assertTrue(stats.racingBuildFinishedFirst().isPresent());
    Assert.assertFalse(stats.racingBuildFinishedFirst().get());
  }

  private void initializeCommonStats(ClientStatsTracker tracker) {
    tracker.setStampedeId(STAMPEDE_ID_ONE);
    tracker.setDistributedBuildExitCode(DISTRIBUTED_BUILD_EXIT_CODE);
    tracker.setIsLocalFallbackBuildEnabled(IS_LOCAL_FALLBACK_BUILD_ENABLED);
    tracker.setDurationMs(LOCAL_PREPARATION, LOCAL_PREPARATION_DURATION);
    tracker.setDurationMs(LOCAL_GRAPH_CONSTRUCTION, LOCAL_GRAPH_CONSTRUCTION_DURATION);
    tracker.setDurationMs(LOCAL_FILE_HASH_COMPUTATION, LOCAL_FILE_HASH_COMPUTATION_DURATION_MS);
    tracker.setDurationMs(
        LOCAL_TARGET_GRAPH_SERIALIZATION, LOCAL_TARGET_GRAPH_SERIALIZATION_DURATION_MS);
    tracker.setDurationMs(
        POST_DISTRIBUTED_BUILD_LOCAL_STEPS, POST_DISTRIBUTED_BUILD_LOCAL_STEPS_DURATION);
    tracker.setDurationMs(PERFORM_DISTRIBUTED_BUILD, PERFORM_DISTRIBUTED_BUILD_DURATION);
    tracker.setDurationMs(CREATE_DISTRIBUTED_BUILD, CREATE_DISTRIBUTED_BUILD_DURATION);
    tracker.setDurationMs(UPLOAD_MISSING_FILES, UPLOAD_MISSING_FILES_DURATION);
    tracker.setDurationMs(UPLOAD_TARGET_GRAPH, UPLOAD_TARGET_GRAPH_DURATION);
    tracker.setDurationMs(UPLOAD_BUCK_DOT_FILES, UPLOAD_BUCK_DOT_FILES_DURATION);
    tracker.setDurationMs(SET_BUCK_VERSION, SET_BUCK_VERSION_DURATION);
    tracker.setDurationMs(MATERIALIZE_SLAVE_LOGS, MATERIALIZE_SLAVE_LOGS_DURATION);
    tracker.setDurationMs(
        PUBLISH_BUILD_SLAVE_FINISHED_STATS, PUBLISH_BUILD_SLAVE_FINISHED_STATS_DURATION_MS);

    tracker.setMissingFilesUploadedCount(MISSING_FILES_UPLOADED_COUNT);
  }

  private void assertCommonStats(DistBuildClientStats stats) {
    Assert.assertEquals(STAMPEDE_ID_ONE, stats.stampedeId());
    Assert.assertEquals(IS_LOCAL_FALLBACK_BUILD_ENABLED, stats.isLocalFallbackBuildEnabled().get());
    Assert.assertEquals(
        DISTRIBUTED_BUILD_EXIT_CODE, (long) stats.distributedBuildExitCode().getAsInt());
    Assert.assertEquals(
        LOCAL_GRAPH_CONSTRUCTION_DURATION, (long) stats.localGraphConstructionDurationMs().get());
    Assert.assertEquals(
        LOCAL_PREPARATION_DURATION, (long) stats.localPreparationDurationMs().get());
    Assert.assertEquals(
        LOCAL_FILE_HASH_COMPUTATION_DURATION_MS,
        (long) stats.localFileHashComputationDurationMs().get());
    Assert.assertEquals(
        LOCAL_TARGET_GRAPH_SERIALIZATION_DURATION_MS,
        (long) stats.localTargetGraphSerializationDurationMs().get());
    Assert.assertEquals(
        PERFORM_DISTRIBUTED_BUILD_DURATION, (long) stats.performDistributedBuildDurationMs().get());
    Assert.assertEquals(
        CREATE_DISTRIBUTED_BUILD_DURATION, (long) stats.createDistributedBuildDurationMs().get());
    Assert.assertEquals(
        UPLOAD_MISSING_FILES_DURATION, (long) stats.uploadMissingFilesDurationMs().get());
    Assert.assertEquals(
        UPLOAD_TARGET_GRAPH_DURATION, (long) stats.uploadTargetGraphDurationMs().get());
    Assert.assertEquals(
        UPLOAD_BUCK_DOT_FILES_DURATION, (long) stats.uploadBuckDotFilesDurationMs().get());
    Assert.assertEquals(SET_BUCK_VERSION_DURATION, (long) stats.setBuckVersionDurationMs().get());
    Assert.assertEquals(
        MATERIALIZE_SLAVE_LOGS_DURATION, (long) stats.materializeSlaveLogsDurationMs().get());
    Assert.assertEquals(
        MISSING_FILES_UPLOADED_COUNT, (long) stats.missingFilesUploadedCount().get());
    Assert.assertEquals(
        POST_DISTRIBUTED_BUILD_LOCAL_STEPS_DURATION,
        (long) stats.postDistBuildLocalStepsDurationMs().get());
    Assert.assertEquals(
        PUBLISH_BUILD_SLAVE_FINISHED_STATS_DURATION_MS,
        (long) stats.publishBuildSlaveFinishedStatsDurationMs().get());
    Assert.assertFalse(stats.buckClientError());
  }
}
