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

import static com.facebook.buck.distributed.DistBuildClientStatsTracker.DistBuildClientStat.*;

import org.junit.Assert;
import org.junit.Test;

public class DistBuildClientStatsTrackerTest {
  private static final String STAMPEDE_ID_ONE = "stampedeIdOne";
  private static final int DISTRIBUTED_BUILD_EXIT_CODE = 0;
  private static final int LOCAL_BUILD_EXIT_CODE = 1;
  private static final boolean IS_LOCAL_FALLBACK_BUILD_ENABLED = true;
  private static final int PERFORM_DISTRIBUTED_BUILD_DURATION = 1;
  private static final int PERFORM_LOCAL_BUILD_DURATION = 2;
  private static final int CREATE_DISTRIBUTED_BUILD_DURATION = 3;
  private static final int UPLOAD_MISSING_FILES_DURATION = 4;
  private static final int UPLOAD_TARGET_GRAPH_DURATION = 5;
  private static final int UPLOAD_BUCK_DOT_FILES_DURATION = 6;
  private static final int SET_BUCK_VERSION_DURATION = 7;
  private static final int MATERIALIZE_SLAVE_LOGS_DURATION = 8;
  public static final int MISSING_FILES_UPLOADED_COUNT = 2001;
  public static final String BUCK_CLIENT_ERROR_MESSAGE = "Some error message";

  @Test(expected = java.lang.IllegalArgumentException.class)
  public void testGenerateThrowsExceptionWhenStatsNotRecorded() {
    DistBuildClientStatsTracker tracker = new DistBuildClientStatsTracker();
    tracker.generateStats();
  }

  @Test
  public void testGenerateSucceedsWhenAllStandardStatsSet() {
    DistBuildClientStatsTracker tracker = new DistBuildClientStatsTracker();
    initializeCommonStats(tracker);
    DistBuildClientStats stats = tracker.generateStats();
    assertCommonStats(stats);
  }

  @Test(expected = java.lang.IllegalArgumentException.class)
  public void testGenerateFailsWhenLocalBuildButMissingLocalBuildFields() {
    DistBuildClientStatsTracker tracker = new DistBuildClientStatsTracker();
    initializeCommonStats(tracker);
    tracker.setPerformedLocalBuild(true);
    tracker.generateStats();
  }

  @Test(expected = java.lang.IllegalArgumentException.class)
  public void testGenerateFailsWhenNoStampedeId() {
    DistBuildClientStatsTracker tracker = new DistBuildClientStatsTracker();
    tracker.generateStats();
  }

  @Test(expected = java.lang.IllegalArgumentException.class)
  public void testGenerateFailsWhenNoErrorButRequiredFieldMissing() {
    DistBuildClientStatsTracker tracker = new DistBuildClientStatsTracker();
    tracker.setStampedeId(STAMPEDE_ID_ONE);
    tracker.generateStats();
  }

  @Test
  public void testGeneratesPartialResultWhenErrorButRequiredFields() {
    DistBuildClientStatsTracker tracker = new DistBuildClientStatsTracker();
    tracker.setBuckClientError(true);
    tracker.setBuckClientErrorMessage(BUCK_CLIENT_ERROR_MESSAGE);

    // The following fields are required for a partial result
    tracker.setStampedeId(STAMPEDE_ID_ONE);
    tracker.setDistributedBuildExitCode(DISTRIBUTED_BUILD_EXIT_CODE);
    tracker.setIsLocalFallbackBuildEnabled(IS_LOCAL_FALLBACK_BUILD_ENABLED);

    DistBuildClientStats stats = tracker.generateStats();

    Assert.assertEquals(STAMPEDE_ID_ONE, stats.stampedeId());
    Assert.assertEquals(DISTRIBUTED_BUILD_EXIT_CODE, (long) stats.distributedBuildExitCode().get());
    Assert.assertEquals(IS_LOCAL_FALLBACK_BUILD_ENABLED, stats.isLocalFallbackBuildEnabled().get());
    Assert.assertEquals(BUCK_CLIENT_ERROR_MESSAGE, stats.buckClientErrorMessage().get());
    Assert.assertTrue(stats.buckClientError());

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
    DistBuildClientStatsTracker tracker = new DistBuildClientStatsTracker();
    initializeCommonStats(tracker);
    tracker.setPerformedLocalBuild(true);
    tracker.setDurationMs(PERFORM_LOCAL_BUILD, PERFORM_LOCAL_BUILD_DURATION);
    tracker.setLocalBuildExitCode(LOCAL_BUILD_EXIT_CODE);
    DistBuildClientStats stats = tracker.generateStats();
    assertCommonStats(stats);

    Assert.assertTrue(stats.performedLocalBuild());
    Assert.assertTrue(stats.localBuildExitCode().isPresent());
    Assert.assertEquals(LOCAL_BUILD_EXIT_CODE, (long) stats.localBuildExitCode().get());
    Assert.assertEquals(PERFORM_LOCAL_BUILD_DURATION, (long) stats.localBuildDurationMs().get());
  }

  private void initializeCommonStats(DistBuildClientStatsTracker tracker) {
    tracker.setStampedeId(STAMPEDE_ID_ONE);
    tracker.setDistributedBuildExitCode(DISTRIBUTED_BUILD_EXIT_CODE);
    tracker.setIsLocalFallbackBuildEnabled(IS_LOCAL_FALLBACK_BUILD_ENABLED);
    tracker.setDurationMs(PERFORM_DISTRIBUTED_BUILD, PERFORM_DISTRIBUTED_BUILD_DURATION);
    tracker.setDurationMs(CREATE_DISTRIBUTED_BUILD, CREATE_DISTRIBUTED_BUILD_DURATION);
    tracker.setDurationMs(UPLOAD_MISSING_FILES, UPLOAD_MISSING_FILES_DURATION);
    tracker.setDurationMs(UPLOAD_TARGET_GRAPH, UPLOAD_TARGET_GRAPH_DURATION);
    tracker.setDurationMs(UPLOAD_BUCK_DOT_FILES, UPLOAD_BUCK_DOT_FILES_DURATION);
    tracker.setDurationMs(SET_BUCK_VERSION, SET_BUCK_VERSION_DURATION);
    tracker.setDurationMs(MATERIALIZE_SLAVE_LOGS, MATERIALIZE_SLAVE_LOGS_DURATION);

    tracker.setMissingFilesUploadedCount(MISSING_FILES_UPLOADED_COUNT);
  }

  private void assertCommonStats(DistBuildClientStats stats) {
    Assert.assertEquals(STAMPEDE_ID_ONE, stats.stampedeId());
    Assert.assertEquals(IS_LOCAL_FALLBACK_BUILD_ENABLED, stats.isLocalFallbackBuildEnabled().get());
    Assert.assertEquals(DISTRIBUTED_BUILD_EXIT_CODE, (long) stats.distributedBuildExitCode().get());
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
    Assert.assertFalse(stats.buckClientError());
  }
}
