/*
 * Copyright 2013-present Facebook, Inc.
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

import static com.facebook.buck.event.TestEventConfigurator.configureTestEventAtTime;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.distributed.DistBuildCreatedEvent;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.InstallEvent;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRuleDurationTracker;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.BuildRuleKeys;
import com.facebook.buck.rules.BuildRuleStatus;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.test.TestResultSummaryVerbosity;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.IncrementingFakeClock;
import com.facebook.buck.util.unit.SizeUnit;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;

public class SimpleConsoleEventBusListenerTest {
  private static final StampedeId STAMPEDE_ID_ONE = new StampedeId().setId("stampedeIdOne");
  private static final String STAMPEDE_ID_ONE_MESSAGE = "StampedeId=[stampedeIdOne]\n";
  private static final String TARGET_ONE = "TARGET_ONE";
  private static final String TARGET_TWO = "TARGET_TWO";
  private static final String SEVERE_MESSAGE = "This is a sample severe message.";

  private static final String FINISHED_DOWNLOAD_STRING = "DOWNLOADED 0 ARTIFACTS, 0.00 BYTES";

  private BuildRuleDurationTracker durationTracker;

  private Clock fakeClock;
  private BuckEventBus eventBus;
  private TestConsole console;
  Path logPath;

  @Before
  public void setUp() {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    logPath = vfs.getPath("log.txt");
    durationTracker = new BuildRuleDurationTracker();

    fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    eventBus = BuckEventBusForTests.newInstance(fakeClock);
    console = new TestConsole();
  }

  @Test
  public void testSimpleBuild() {
    setupSimpleConsole(false);
    String expectedOutput = "";
    assertOutput(expectedOutput, console);

    BuildTarget fakeTarget = BuildTargetFactory.newInstance("//banana:stand");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(fakeTarget);
    Iterable<String> buildArgs = Iterables.transform(buildTargets, Object::toString);
    FakeBuildRule fakeRule = new FakeBuildRule(fakeTarget, ImmutableSortedSet.of());

    long threadId = 0;

    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(buildEventStarted, 0L, TimeUnit.MILLISECONDS, threadId));
    ParseEvent.Started parseStarted = ParseEvent.started(buildTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(parseStarted, 0L, TimeUnit.MILLISECONDS, threadId));

    assertOutput(expectedOutput, console);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ParseEvent.finished(parseStarted, 10, Optional.empty()),
            400L,
            TimeUnit.MILLISECONDS,
            threadId));

    expectedOutput += "PARSING BUCK FILES: FINISHED IN 0.4s\n";
    assertOutput(expectedOutput, console);

    BuildRuleEvent.Started started = BuildRuleEvent.started(fakeRule, durationTracker);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(started, 600L, TimeUnit.MILLISECONDS, threadId));

    HttpArtifactCacheEvent.Scheduled storeScheduledOne =
        ArtifactCacheTestUtils.postStoreScheduled(eventBus, threadId, TARGET_ONE, 700L);

    HttpArtifactCacheEvent.Scheduled storeScheduledTwo =
        ArtifactCacheTestUtils.postStoreScheduled(eventBus, threadId, TARGET_TWO, 700L);

    HttpArtifactCacheEvent.Started storeStartedOne =
        ArtifactCacheTestUtils.postStoreStarted(eventBus, threadId, 710L, storeScheduledOne);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                started,
                BuildRuleKeys.of(new RuleKey("aaaa")),
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.empty(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            1000L,
            TimeUnit.MILLISECONDS,
            threadId));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.finished(buildEventStarted, ExitCode.SUCCESS),
            1234L,
            TimeUnit.MILLISECONDS,
            threadId));

    expectedOutput +=
        "BUILT  0.4s //banana:stand\n"
            + FINISHED_DOWNLOAD_STRING
            + "\n"
            + "BUILDING: FINISHED IN 1.2s\n"
            + "WAITING FOR HTTP CACHE UPLOADS 0.00 BYTES (0 COMPLETE/0 FAILED/1 UPLOADING/1 PENDING)\n"
            + "BUILD SUCCEEDED\n";
    assertOutput(expectedOutput, console);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ConsoleEvent.severe(SEVERE_MESSAGE), 1500L, TimeUnit.MILLISECONDS, threadId));

    expectedOutput += SEVERE_MESSAGE + "\n";
    assertOutput(expectedOutput, console);

    InstallEvent.Started installEventStarted =
        configureTestEventAtTime(
            InstallEvent.started(fakeTarget), 2500L, TimeUnit.MILLISECONDS, threadId);
    eventBus.postWithoutConfiguring(installEventStarted);

    assertOutput(expectedOutput, console);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            InstallEvent.finished(installEventStarted, true, Optional.empty(), Optional.empty()),
            4000L,
            TimeUnit.MILLISECONDS,
            threadId));

    expectedOutput += "INSTALLING: FINISHED IN 1.5s\n";
    assertOutput(expectedOutput, console);

    long artifactSizeOne = SizeUnit.MEGABYTES.toBytes(1.5);
    ArtifactCacheTestUtils.postStoreFinished(
        eventBus, threadId, artifactSizeOne, 5015L, true, storeStartedOne);

    HttpArtifactCacheEvent.Started storeStartedTwo =
        ArtifactCacheTestUtils.postStoreStarted(eventBus, threadId, 5020L, storeScheduledTwo);

    long artifactSizeTwo = 600;
    ArtifactCacheTestUtils.postStoreFinished(
        eventBus, threadId, artifactSizeTwo, 5020L, false, storeStartedTwo);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            HttpArtifactCacheEvent.newShutdownEvent(), 6000L, TimeUnit.MILLISECONDS, threadId));

    expectedOutput +=
        "HTTP CACHE UPLOAD: FINISHED 1.50 MBYTES (1 COMPLETE/1 FAILED/0 UPLOADING/0 PENDING)\n";
    assertOutput(expectedOutput, console);
  }

  @Test
  public void testJobSummaryIsDisplayed() {
    setupSimpleConsole(false);
    String expectedOutput = "";
    assertOutput(expectedOutput, console);

    BuildEvent.RuleCountCalculated ruleCountCalculated =
        BuildEvent.ruleCountCalculated(ImmutableSet.of(), 10);
    eventBus.post(ruleCountCalculated);

    BuildTarget fakeTarget = BuildTargetFactory.newInstance("//banana:stand");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(fakeTarget);
    Iterable<String> buildArgs = Iterables.transform(buildTargets, Object::toString);

    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted, 200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.finished(buildEventStarted, ExitCode.SUCCESS),
            1234L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    expectedOutput += "BUILDING: FINISHED IN 1.0s 0/10 JOBS, 0 UPDATED, 0.0% CACHE MISS\n";
    expectedOutput += "BUILD SUCCEEDED\n";
    assertOutput(FINISHED_DOWNLOAD_STRING + "\n" + expectedOutput, console);
  }

  @Test
  public void testBuildTimeDoesNotDisplayNegativeOffset() {
    setupSimpleConsole(false);
    String expectedOutput = "";
    assertOutput(expectedOutput, console);

    BuildTarget fakeTarget = BuildTargetFactory.newInstance("//banana:stand");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(fakeTarget);
    Iterable<String> buildArgs = Iterables.transform(buildTargets, Object::toString);

    // Do a full parse and action graph cycle before the build event starts
    // This sequencing occurs when running `buck project`
    ParseEvent.Started parseStarted = ParseEvent.started(buildTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(parseStarted, 100L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ParseEvent.finished(parseStarted, 10, Optional.empty()),
            300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    expectedOutput += "PARSING BUCK FILES: FINISHED IN 0.2s\n";
    assertOutput(expectedOutput, console);

    ActionGraphEvent.Started actionGraphStarted = ActionGraphEvent.started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            actionGraphStarted, 300L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ActionGraphEvent.finished(actionGraphStarted),
            500L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted, 500L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.finished(buildEventStarted, ExitCode.SUCCESS),
            600L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    expectedOutput += "CREATING ACTION GRAPH: FINISHED IN 0.2s\n";
    expectedOutput += FINISHED_DOWNLOAD_STRING + "\n";
    expectedOutput += "BUILDING: FINISHED IN 0.1s\n";
    expectedOutput += "BUILD SUCCEEDED\n";
    assertOutput(expectedOutput, console);
  }

  @Test
  public void testSimpleHideSucceededBuild() {
    setupSimpleConsole(true);
    String expectedOutput = "";
    assertOutput(expectedOutput, console);

    BuildTarget fakeTarget = BuildTargetFactory.newInstance("//banana:stand");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(fakeTarget);
    Iterable<String> buildArgs = Iterables.transform(buildTargets, Object::toString);
    FakeBuildRule fakeRule = new FakeBuildRule(fakeTarget, ImmutableSortedSet.of());

    long threadId = 0;

    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(buildEventStarted, 0L, TimeUnit.MILLISECONDS, threadId));
    ParseEvent.Started parseStarted = ParseEvent.started(buildTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(parseStarted, 0L, TimeUnit.MILLISECONDS, threadId));

    assertOutput(expectedOutput, console);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ParseEvent.finished(parseStarted, 10, Optional.empty()),
            400L,
            TimeUnit.MILLISECONDS,
            threadId));

    expectedOutput += "PARSING BUCK FILES: FINISHED IN 0.4s\n";
    assertOutput(expectedOutput, console);

    BuildRuleEvent.Started started = BuildRuleEvent.started(fakeRule, durationTracker);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(started, 600L, TimeUnit.MILLISECONDS, threadId));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                started,
                BuildRuleKeys.of(new RuleKey("aaaa")),
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.empty(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            1000L,
            TimeUnit.MILLISECONDS,
            threadId));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.finished(buildEventStarted, ExitCode.SUCCESS),
            1234L,
            TimeUnit.MILLISECONDS,
            threadId));

    expectedOutput +=
        FINISHED_DOWNLOAD_STRING + "\n" + "BUILDING: FINISHED IN 1.2s\n" + "BUILD SUCCEEDED\n";
    assertOutput(expectedOutput, console);
  }

  @Test
  public void testPrintsStampedeIdForDistributedBuild() {
    setupSimpleConsole(true);
    String expectedOutput = "";
    assertOutput(expectedOutput, console);

    eventBus.post(new DistBuildCreatedEvent(STAMPEDE_ID_ONE));

    expectedOutput += STAMPEDE_ID_ONE_MESSAGE;
    assertOutput(expectedOutput, console);
  }

  private void assertOutput(String expectedOutput, TestConsole console) {
    assertEquals("", console.getTextWrittenToStdOut());
    assertEquals(expectedOutput, console.getTextWrittenToStdErr());
  }

  private void setupSimpleConsole(boolean hideSucceededRules) {
    SimpleConsoleEventBusListener listener =
        new SimpleConsoleEventBusListener(
            console,
            fakeClock,
            TestResultSummaryVerbosity.of(false, false),
            hideSucceededRules,
            /* numberOfSlowRulesToShow */ 0,
            false,
            Locale.US,
            logPath,
            new DefaultExecutionEnvironment(
                ImmutableMap.copyOf(System.getenv()), System.getProperties()),
            Optional.empty());

    eventBus.register(listener);
  }
}
