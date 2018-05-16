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

package com.facebook.buck.event.listener;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.makeThreadSafe;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import com.facebook.buck.artifact_cache.ArtifactCacheEvent.StoreType;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEventStoreData;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.build.stats.BuildRuleDurationTracker;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.BuildRuleKeys;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.distributed.DistBuildMode;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildUtil;
import com.facebook.buck.distributed.FileMaterializationStatsTracker;
import com.facebook.buck.distributed.build_slave.BuildSlaveTimingStatsTracker.SlaveEvents;
import com.facebook.buck.distributed.build_slave.HealthCheckStatsTracker;
import com.facebook.buck.distributed.testutil.FakeDistBuildSlaveTimingStatsTracker;
import com.facebook.buck.distributed.thrift.BuildSlaveEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEventType;
import com.facebook.buck.distributed.thrift.BuildSlaveFinishedStats;
import com.facebook.buck.distributed.thrift.BuildSlavePerStageTimingStats;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.CacheRateStats;
import com.facebook.buck.distributed.thrift.CoordinatorBuildProgress;
import com.facebook.buck.distributed.thrift.FileMaterializationStats;
import com.facebook.buck.distributed.thrift.HealthCheckStats;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.keys.FakeRuleKeyFactory;
import com.facebook.buck.util.network.hostname.HostnameFetching;
import com.facebook.buck.util.timing.SettableFakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.easymock.Capture;
import org.easymock.CaptureType;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DistBuildSlaveEventBusListenerTest {
  private static final int FILES_MATERIALIZED_COUNT = 1;

  private DistBuildSlaveEventBusListener listener;
  private BuildSlaveRunId buildSlaveRunId;
  private StampedeId stampedeId;
  private DistBuildService distBuildServiceMock;
  private BuckEventBus eventBus;
  private SettableFakeClock clock = SettableFakeClock.DO_NOT_CARE;
  private FileMaterializationStatsTracker fileMaterializationStatsTracker;
  private HealthCheckStatsTracker healthCheckStatsTracker;
  private FakeDistBuildSlaveTimingStatsTracker slaveStatsTracker;

  @Before
  public void setUp() {
    buildSlaveRunId = new BuildSlaveRunId();
    buildSlaveRunId.setId("this-is-the-slaves-id");
    stampedeId = new StampedeId();
    stampedeId.setId("this-is-the-big-id");
    distBuildServiceMock = EasyMock.createMock(DistBuildService.class);
    makeThreadSafe(distBuildServiceMock, true);
    eventBus = BuckEventBusForTests.newInstance();
    fileMaterializationStatsTracker = new FileMaterializationStatsTracker();
    healthCheckStatsTracker = new HealthCheckStatsTracker();
    slaveStatsTracker = new FakeDistBuildSlaveTimingStatsTracker();
  }

  private void setUpDistBuildSlaveEventBusListener() {
    listener =
        new DistBuildSlaveEventBusListener(
            stampedeId,
            buildSlaveRunId,
            DistBuildMode.REMOTE_BUILD,
            clock,
            slaveStatsTracker,
            fileMaterializationStatsTracker,
            healthCheckStatsTracker,
            Executors.newScheduledThreadPool(1),
            1);
    eventBus.register(listener);
    listener.setDistBuildService(distBuildServiceMock);
  }

  @After
  public void tearDown() throws IOException {
    listener.close();
  }

  public BuildSlaveStatus createBuildSlaveStatusWithZeros() {
    BuildSlaveStatus status = new BuildSlaveStatus();
    status.setStampedeId(stampedeId);
    status.setBuildSlaveRunId(buildSlaveRunId);
    status.setTotalRulesCount(0);
    status.setRulesBuildingCount(0);
    status.setRulesFinishedCount(0);
    status.setRulesFailureCount(0);

    status.setHttpArtifactTotalBytesUploaded(0);
    status.setHttpArtifactUploadsScheduledCount(0);
    status.setHttpArtifactUploadsOngoingCount(0);
    status.setHttpArtifactUploadsSuccessCount(0);
    status.setHttpArtifactUploadsFailureCount(0);

    CacheRateStats cacheRateStats = new CacheRateStats();
    status.setCacheRateStats(cacheRateStats);
    cacheRateStats.setTotalRulesCount(0);
    cacheRateStats.setUpdatedRulesCount(0);
    cacheRateStats.setCacheHitsCount(0);
    cacheRateStats.setCacheMissesCount(0);
    cacheRateStats.setCacheErrorsCount(0);
    cacheRateStats.setCacheIgnoresCount(0);
    cacheRateStats.setCacheLocalKeyUnchangedHitsCount(0);
    cacheRateStats.setUnexpectedCacheMissesCount(0);

    status.setFilesMaterializedCount(0);

    return status;
  }

  @Test
  public void testHandlingConsoleEvents() throws IOException {
    List<ConsoleEvent> consoleEvents = new LinkedList<>();
    consoleEvents.add(ConsoleEvent.create(Level.FINE, "fine message"));
    consoleEvents.add(ConsoleEvent.create(Level.INFO, "info message"));
    consoleEvents.add(ConsoleEvent.create(Level.WARNING, "warning message"));
    consoleEvents.add(ConsoleEvent.create(Level.SEVERE, "severe message"));
    consoleEvents.add(ConsoleEvent.create(Level.INFO, "info message"));
    consoleEvents.add(ConsoleEvent.create(Level.SEVERE, "severe message"));

    distBuildServiceMock.updateBuildSlaveStatus(eq(stampedeId), eq(buildSlaveRunId), anyObject());
    expectLastCall().anyTimes();

    distBuildServiceMock.storeBuildSlaveFinishedStats(
        eq(stampedeId), eq(buildSlaveRunId), anyObject());
    expectLastCall().anyTimes();

    Capture<List<BuildSlaveEvent>> capturedEventLists = Capture.newInstance(CaptureType.ALL);

    distBuildServiceMock.uploadBuildSlaveEvents(
        eq(stampedeId), eq(buildSlaveRunId), capture(capturedEventLists));
    expectLastCall().atLeastOnce();
    replay(distBuildServiceMock);
    setUpDistBuildSlaveEventBusListener();

    for (int i = 0; i < consoleEvents.size(); ++i) {
      clock.setCurrentTimeMillis(10 * i);
      eventBus.post(consoleEvents.get(i));
    }

    listener.close();

    // Note: Mock is not thread safe when we call verify. All work in other threads should
    // have stopped.
    verify(distBuildServiceMock);

    List<BuildSlaveEvent> capturedEvents =
        capturedEventLists.getValues().stream().flatMap(List::stream).collect(Collectors.toList());
    Assert.assertEquals(capturedEvents.size(), 3);
    Assert.assertTrue(
        capturedEvents.containsAll(
            ImmutableList.of(
                DistBuildUtil.createBuildSlaveConsoleEvent(consoleEvents.get(2), 20),
                DistBuildUtil.createBuildSlaveConsoleEvent(consoleEvents.get(3), 30),
                DistBuildUtil.createBuildSlaveConsoleEvent(consoleEvents.get(5), 50))));
  }

  @Test
  public void testHandlingTotalRuleCountUpdates() throws IOException {
    BuildSlaveStatus expectedStatus = createBuildSlaveStatusWithZeros();
    expectedStatus.setTotalRulesCount(50);

    distBuildServiceMock.uploadBuildSlaveEvents(eq(stampedeId), eq(buildSlaveRunId), anyObject());
    expectLastCall().anyTimes();

    distBuildServiceMock.storeBuildSlaveFinishedStats(
        eq(stampedeId), eq(buildSlaveRunId), anyObject());
    expectLastCall().anyTimes();

    Capture<BuildSlaveStatus> capturedStatus = Capture.newInstance(CaptureType.LAST);
    distBuildServiceMock.updateBuildSlaveStatus(
        eq(stampedeId), eq(buildSlaveRunId), capture(capturedStatus));
    expectLastCall().atLeastOnce();

    replay(distBuildServiceMock);
    setUpDistBuildSlaveEventBusListener();

    listener.updateTotalRuleCount(100);
    listener.updateTotalRuleCount(50);

    listener.close();
    verify(distBuildServiceMock);
    Assert.assertEquals(capturedStatus.getValue(), expectedStatus);
  }

  @Test
  public void testHandlingFinishedRuleCountUpdates() throws IOException {
    BuildSlaveStatus expectedStatus = createBuildSlaveStatusWithZeros();
    expectedStatus.setRulesFinishedCount(50);

    distBuildServiceMock.uploadBuildSlaveEvents(eq(stampedeId), eq(buildSlaveRunId), anyObject());
    expectLastCall().anyTimes();

    distBuildServiceMock.storeBuildSlaveFinishedStats(
        eq(stampedeId), eq(buildSlaveRunId), anyObject());
    expectLastCall().anyTimes();

    Capture<BuildSlaveStatus> capturedStatus = Capture.newInstance(CaptureType.LAST);
    distBuildServiceMock.updateBuildSlaveStatus(
        eq(stampedeId), eq(buildSlaveRunId), capture(capturedStatus));
    expectLastCall().atLeastOnce();

    replay(distBuildServiceMock);
    setUpDistBuildSlaveEventBusListener();

    listener.updateFinishedRuleCount(50);

    listener.close();
    verify(distBuildServiceMock);
    Assert.assertEquals(capturedStatus.getValue(), expectedStatus);
  }

  @Test
  public void testHandlingUnexpectedCacheMissTracking() throws IOException {
    BuildSlaveStatus expectedStatus = createBuildSlaveStatusWithZeros();

    CacheRateStats cacheRateStats = expectedStatus.getCacheRateStats();
    cacheRateStats.setUnexpectedCacheMissesCount(13);

    distBuildServiceMock.uploadBuildSlaveEvents(eq(stampedeId), eq(buildSlaveRunId), anyObject());
    expectLastCall().anyTimes();

    distBuildServiceMock.storeBuildSlaveFinishedStats(
        eq(stampedeId), eq(buildSlaveRunId), anyObject());
    expectLastCall().anyTimes();

    Capture<BuildSlaveStatus> capturedStatus = Capture.newInstance(CaptureType.LAST);
    distBuildServiceMock.updateBuildSlaveStatus(
        eq(stampedeId), eq(buildSlaveRunId), capture(capturedStatus));
    expectLastCall().atLeastOnce();

    replay(distBuildServiceMock);
    setUpDistBuildSlaveEventBusListener();

    listener.onUnexpectedCacheMiss(7);
    listener.onUnexpectedCacheMiss(6);

    listener.close();
    verify(distBuildServiceMock);
    Assert.assertEquals(capturedStatus.getValue(), expectedStatus);
  }

  @Test
  public void testCoordinatorBuildProgressUpdatesAreSent() throws IOException {
    Capture<List<BuildSlaveEvent>> capturedBuildSlaveEvents = Capture.newInstance(CaptureType.ALL);
    distBuildServiceMock.uploadBuildSlaveEvents(
        eq(stampedeId), eq(buildSlaveRunId), capture(capturedBuildSlaveEvents));
    expectLastCall().atLeastOnce();

    distBuildServiceMock.updateBuildSlaveStatus(eq(stampedeId), eq(buildSlaveRunId), anyObject());
    expectLastCall().anyTimes();

    distBuildServiceMock.storeBuildSlaveFinishedStats(
        eq(stampedeId), eq(buildSlaveRunId), anyObject());
    expectLastCall().anyTimes();

    CoordinatorBuildProgress expectedProgress =
        new CoordinatorBuildProgress()
            .setBuiltRulesCount(10)
            .setTotalRulesCount(30)
            .setSkippedRulesCount(20);

    replay(distBuildServiceMock);
    setUpDistBuildSlaveEventBusListener();

    listener.updateCoordinatorBuildProgress(expectedProgress);

    listener.close();
    verify(distBuildServiceMock);

    List<BuildSlaveEvent> progressEvents =
        capturedBuildSlaveEvents
            .getValues()
            .stream()
            .flatMap(List::stream)
            .filter(
                event ->
                    event.eventType.equals(BuildSlaveEventType.COORDINATOR_BUILD_PROGRESS_EVENT))
            .collect(Collectors.toList());

    Assert.assertTrue("No COORDINATOR_BUILD_PROGRESS_EVENTs were sent.", progressEvents.size() > 0);
    Assert.assertEquals(
        expectedProgress,
        progressEvents
            .get(progressEvents.size() - 1)
            .getCoordinatorBuildProgressEvent()
            .getBuildProgress());
  }

  @Test
  public void testHandlingBuildRuleEvents() throws IOException {
    BuildSlaveStatus expectedStatus = createBuildSlaveStatusWithZeros();
    expectedStatus.setTotalRulesCount(4);
    expectedStatus.setRulesBuildingCount(1);
    expectedStatus.setRulesFinishedCount(2);
    expectedStatus.setRulesFailureCount(1);

    // Total rules for cache stats are different because slave ended up building more rules than
    // the coordinator asked. Total rules for the cache should be counted with BuildRuleEvents, not
    // the explicit updateTotalRules/updateFinishedRules methods.
    CacheRateStats cacheRateStats = expectedStatus.getCacheRateStats();
    cacheRateStats.setTotalRulesCount(6);
    cacheRateStats.setUpdatedRulesCount(4);
    cacheRateStats.setCacheHitsCount(1);
    cacheRateStats.setCacheMissesCount(1);
    cacheRateStats.setCacheErrorsCount(1);
    cacheRateStats.setCacheIgnoresCount(1);

    fileMaterializationStatsTracker.recordLocalFileMaterialized();
    expectedStatus.setFilesMaterializedCount(FILES_MATERIALIZED_COUNT);

    distBuildServiceMock.uploadBuildSlaveEvents(eq(stampedeId), eq(buildSlaveRunId), anyObject());
    expectLastCall().anyTimes();

    distBuildServiceMock.storeBuildSlaveFinishedStats(
        eq(stampedeId), eq(buildSlaveRunId), anyObject());
    expectLastCall().anyTimes();

    Capture<BuildSlaveStatus> capturedStatus = Capture.newInstance(CaptureType.LAST);
    distBuildServiceMock.updateBuildSlaveStatus(
        eq(stampedeId), eq(buildSlaveRunId), capture(capturedStatus));
    expectLastCall().atLeastOnce();

    replay(distBuildServiceMock);
    setUpDistBuildSlaveEventBusListener();

    RuleKey fakeRuleKey = new RuleKey("aaaa");
    BuildTarget fakeTarget = BuildTargetFactory.newInstance("//some:target");
    FakeRuleKeyFactory fakeRuleKeyFactory =
        new FakeRuleKeyFactory(ImmutableMap.of(fakeTarget, fakeRuleKey));
    FakeBuildRule fakeRule = new FakeBuildRule(fakeTarget.getFullyQualifiedName());
    BuildRuleDurationTracker tracker = new BuildRuleDurationTracker();

    BuildRuleEvent.Started started1 = BuildRuleEvent.started(fakeRule, tracker);
    BuildRuleEvent.Started started2 = BuildRuleEvent.started(fakeRule, tracker);
    BuildRuleEvent.Started started3 = BuildRuleEvent.started(fakeRule, tracker);
    BuildRuleEvent.Started started4 = BuildRuleEvent.started(fakeRule, tracker);
    BuildRuleEvent.Started started5 = BuildRuleEvent.started(fakeRule, tracker);
    BuildRuleEvent.Started started6 = BuildRuleEvent.started(fakeRule, tracker);
    BuildRuleEvent.Started started7 = BuildRuleEvent.started(fakeRule, tracker);

    BuildRuleEvent.Suspended suspended3 = BuildRuleEvent.suspended(started3, fakeRuleKeyFactory);
    BuildRuleEvent.Resumed resumed3 = BuildRuleEvent.resumed(fakeRule, tracker, fakeRuleKeyFactory);
    BuildRuleEvent.Suspended suspended7 = BuildRuleEvent.suspended(started7, fakeRuleKeyFactory);

    listener.updateTotalRuleCount(4);
    eventBus.post(started1);
    eventBus.post(started2);
    eventBus.post(
        BuildRuleEvent.finished(
            started1,
            BuildRuleKeys.of(fakeRuleKey),
            BuildRuleStatus.SUCCESS,
            CacheResult.hit("buckcache", ArtifactCacheMode.http),
            Optional.empty(),
            Optional.of(BuildRuleSuccessType.FETCHED_FROM_CACHE),
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
    listener.updateFinishedRuleCount(1);
    eventBus.post(started3);
    eventBus.post(
        BuildRuleEvent.finished(
            started2,
            BuildRuleKeys.of(fakeRuleKey),
            BuildRuleStatus.SUCCESS,
            CacheResult.miss(),
            Optional.empty(),
            Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
    eventBus.post(suspended3);
    eventBus.post(started4);
    eventBus.post(started5);
    eventBus.post(
        BuildRuleEvent.finished(
            started5,
            BuildRuleKeys.of(fakeRuleKey),
            BuildRuleStatus.FAIL,
            CacheResult.error("buckcache", ArtifactCacheMode.http, "connection error"),
            Optional.empty(),
            Optional.empty(),
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
    eventBus.post(
        BuildRuleEvent.finished(
            started4,
            BuildRuleKeys.of(fakeRuleKey),
            BuildRuleStatus.CANCELED,
            CacheResult.miss(), // This value will be ignored, since the rule was canceled.
            Optional.empty(),
            Optional.empty(),
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
    listener.updateFinishedRuleCount(2);
    eventBus.post(started6);
    eventBus.post(started7);
    eventBus.post(resumed3);
    eventBus.post(
        BuildRuleEvent.finished(
            started6,
            BuildRuleKeys.of(fakeRuleKey),
            BuildRuleStatus.SUCCESS,
            CacheResult.ignored(),
            Optional.empty(),
            Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
    eventBus.post(suspended7);

    listener.close();
    verify(distBuildServiceMock);
    Assert.assertEquals(capturedStatus.getValue(), expectedStatus);
  }

  @Test
  public void testCacheUploadEvents() throws IOException {
    final long kSizeBytesMultiplier = 1234567890000L;
    BuildSlaveStatus expectedStatus = createBuildSlaveStatusWithZeros();
    expectedStatus.setHttpArtifactTotalBytesUploaded((1 + 2) * kSizeBytesMultiplier);
    expectedStatus.setHttpArtifactUploadsScheduledCount(6);
    expectedStatus.setHttpArtifactUploadsOngoingCount(2);
    expectedStatus.setHttpArtifactUploadsSuccessCount(3);
    expectedStatus.setHttpArtifactUploadsFailureCount(1);

    distBuildServiceMock.uploadBuildSlaveEvents(eq(stampedeId), eq(buildSlaveRunId), anyObject());
    expectLastCall().anyTimes();

    distBuildServiceMock.storeBuildSlaveFinishedStats(
        eq(stampedeId), eq(buildSlaveRunId), anyObject());
    expectLastCall().anyTimes();

    Capture<BuildSlaveStatus> capturedStatus = Capture.newInstance(CaptureType.LAST);
    distBuildServiceMock.updateBuildSlaveStatus(
        eq(stampedeId), eq(buildSlaveRunId), capture(capturedStatus));
    expectLastCall().atLeastOnce();

    replay(distBuildServiceMock);
    setUpDistBuildSlaveEventBusListener();

    List<HttpArtifactCacheEvent.Scheduled> scheduledEvents = new ArrayList<>();
    List<HttpArtifactCacheEvent.Started> startedEvents = new ArrayList<>();
    List<HttpArtifactCacheEvent.Finished> finishedEvents = new ArrayList<>();
    for (int i = 0; i < 6; ++i) {
      scheduledEvents.add(
          HttpArtifactCacheEvent.newStoreScheduledEvent(
              Optional.of("fake"), ImmutableSet.of(), StoreType.ARTIFACT));

      startedEvents.add(HttpArtifactCacheEvent.newStoreStartedEvent(scheduledEvents.get(i)));

      HttpArtifactCacheEvent.Finished.Builder finishedEventBuilder =
          HttpArtifactCacheEvent.newFinishedEventBuilder(startedEvents.get(i));
      HttpArtifactCacheEventStoreData.Builder storeData = finishedEventBuilder.getStoreBuilder();
      storeData.setStoreType(StoreType.ARTIFACT);
      if (i < 3) {
        storeData.setWasStoreSuccessful(true).setArtifactSizeBytes(i * kSizeBytesMultiplier);
      } else {
        storeData.setWasStoreSuccessful(false);
      }
      finishedEvents.add(finishedEventBuilder.build());
    }

    eventBus.post(scheduledEvents.get(0));
    eventBus.post(scheduledEvents.get(1));
    eventBus.post(startedEvents.get(1));
    eventBus.post(scheduledEvents.get(2));
    eventBus.post(startedEvents.get(0));
    eventBus.post(finishedEvents.get(1));
    eventBus.post(startedEvents.get(2));

    eventBus.post(scheduledEvents.get(3));
    eventBus.post(scheduledEvents.get(4));
    eventBus.post(scheduledEvents.get(5));

    eventBus.post(startedEvents.get(3));
    eventBus.post(startedEvents.get(4));
    eventBus.post(startedEvents.get(5));

    eventBus.post(finishedEvents.get(0));
    eventBus.post(finishedEvents.get(2));
    eventBus.post(finishedEvents.get(3));

    listener.close();
    verify(distBuildServiceMock);
    Assert.assertEquals(capturedStatus.getValue(), expectedStatus);
  }

  @Test
  public void testGeneratingSlaveFinishedEventAndStats() throws IOException {
    final int TOTAL_RULE_COUNT = 50;
    final int EXIT_CODE = 42;
    final long FILE_MATERIALIZATION_TIME_MS = 100;
    final long ACTION_GRAPH_CREATION_TIME_MS = 200;
    final int NUM_FILES_MATERIALIZED_FROM_CAS = 1;
    final int NUM_TOTAL_FILES_MATERIALIZED = 2;

    BuildSlaveStatus status =
        createBuildSlaveStatusWithZeros()
            .setTotalRulesCount(TOTAL_RULE_COUNT)
            .setFilesMaterializedCount(NUM_TOTAL_FILES_MATERIALIZED);

    FileMaterializationStats fileMaterializationStats =
        new FileMaterializationStats()
            .setTotalFilesMaterializedCount(NUM_TOTAL_FILES_MATERIALIZED)
            .setFilesMaterializedFromCASCount(NUM_FILES_MATERIALIZED_FROM_CAS)
            .setTotalTimeSpentMaterializingFilesFromCASMillis(FILE_MATERIALIZATION_TIME_MS)
            .setFullBufferCasMultiFetchCount(2)
            .setPeriodicCasMultiFetchCount(1)
            .setTimeSpentInMultiFetchNetworkCallsMs(128);

    BuildSlavePerStageTimingStats timingStats =
        new BuildSlavePerStageTimingStats()
            .setDistBuildStateFetchTimeMillis(0)
            .setDistBuildStateLoadingTimeMillis(0)
            .setTargetGraphDeserializationTimeMillis(0)
            .setActionGraphCreationTimeMillis(ACTION_GRAPH_CREATION_TIME_MS)
            .setSourceFilePreloadTimeMillis(0)
            .setTotalBuildtimeMillis(0)
            .setDistBuildPreparationTimeMillis(0);

    BuildSlaveFinishedStats expectedFinishedStats = new BuildSlaveFinishedStats();
    expectedFinishedStats.setHostname(HostnameFetching.getHostname());
    expectedFinishedStats.setBuildSlaveStatus(status);
    expectedFinishedStats.setFileMaterializationStats(fileMaterializationStats);
    expectedFinishedStats.setBuildSlavePerStageTimingStats(timingStats);
    expectedFinishedStats.setDistBuildMode("REMOTE_BUILD");
    expectedFinishedStats.setExitCode(EXIT_CODE);

    HealthCheckStats healthCheckStats = new HealthCheckStats();
    healthCheckStats.setSlowHeartbeatsReceivedCount(0);
    healthCheckStats.setSlowestHeartbeatIntervalMillis(0);
    healthCheckStats.setHeartbeatsReceivedCount(0);
    healthCheckStats.setAverageHeartbeatIntervalMillis(0);
    healthCheckStats.setSlowestHeartbeatMinionId("");
    healthCheckStats.setSlowDeadMinionChecksCount(0);
    healthCheckStats.setSlowestDeadMinionCheckIntervalMillis(0);
    expectedFinishedStats.setHealthCheckStats(healthCheckStats);

    distBuildServiceMock.uploadBuildSlaveEvents(eq(stampedeId), eq(buildSlaveRunId), anyObject());
    expectLastCall().anyTimes();

    distBuildServiceMock.updateBuildSlaveStatus(eq(stampedeId), eq(buildSlaveRunId), anyObject());
    expectLastCall().anyTimes();

    Capture<BuildSlaveFinishedStats> capturedStats = Capture.newInstance(CaptureType.LAST);
    distBuildServiceMock.storeBuildSlaveFinishedStats(
        eq(stampedeId), eq(buildSlaveRunId), capture(capturedStats));
    expectLastCall().once();

    replay(distBuildServiceMock);
    setUpDistBuildSlaveEventBusListener();

    // Test updates to slave status are included.
    listener.updateTotalRuleCount(TOTAL_RULE_COUNT);
    // Test updates to file materialization stats are included.
    fileMaterializationStatsTracker.recordLocalFileMaterialized();
    fileMaterializationStatsTracker.recordRemoteFileMaterialized(FILE_MATERIALIZATION_TIME_MS);
    fileMaterializationStatsTracker.recordPeriodicCasMultiFetch(50);
    fileMaterializationStatsTracker.recordFullBufferCasMultiFetch(40);
    fileMaterializationStatsTracker.recordFullBufferCasMultiFetch(38);
    // Test updates to timing stats are included.
    slaveStatsTracker.setElapsedTimeMillis(
        SlaveEvents.ACTION_GRAPH_CREATION_TIME, ACTION_GRAPH_CREATION_TIME_MS);

    listener.sendFinalServerUpdates(EXIT_CODE);
    listener.close();
    verify(distBuildServiceMock);
    Assert.assertEquals(expectedFinishedStats, capturedStats.getValue());
  }

  @Test
  public void testUnlockedEventsAreSent() throws IOException {
    distBuildServiceMock.updateBuildSlaveStatus(eq(stampedeId), eq(buildSlaveRunId), anyObject());
    expectLastCall().anyTimes();
    distBuildServiceMock.storeBuildSlaveFinishedStats(
        eq(stampedeId), eq(buildSlaveRunId), anyObject());
    expectLastCall().anyTimes();

    Capture<List<BuildSlaveEvent>> capturedEventsLists = Capture.newInstance(CaptureType.ALL);
    distBuildServiceMock.uploadBuildSlaveEvents(
        eq(stampedeId), eq(buildSlaveRunId), capture(capturedEventsLists));
    expectLastCall().atLeastOnce();
    replay(distBuildServiceMock);

    ImmutableList<String> unlockedTargets = ImmutableList.of("unlock_1", "unlock2");
    setUpDistBuildSlaveEventBusListener();
    listener.createBuildRuleUnlockedEvents(unlockedTargets);
    listener.close();

    verify(distBuildServiceMock);
    List<String> capturedEventsTargets =
        capturedEventsLists
            .getValues()
            .stream()
            .flatMap(List::stream)
            .filter(event -> event.eventType.equals(BuildSlaveEventType.BUILD_RULE_UNLOCKED_EVENT))
            .map(event -> event.getBuildRuleUnlockedEvent().getBuildTarget())
            .collect(Collectors.toList());
    Assert.assertEquals(capturedEventsTargets.size(), 2);
    Assert.assertTrue(capturedEventsTargets.containsAll(unlockedTargets));
  }
}
