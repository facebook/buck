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

import static com.facebook.buck.event.TestEventConfigerator.configureTestEventAtTime;
import static com.facebook.buck.event.listener.ConsoleTestUtils.postStoreFinished;
import static com.facebook.buck.event.listener.ConsoleTestUtils.postStoreScheduled;
import static com.facebook.buck.event.listener.ConsoleTestUtils.postStoreStarted;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.InstallEvent;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.BuildRuleKeys;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleStatus;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.test.TestResultSummaryVerbosity;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.EventBus;
import com.google.common.hash.HashCode;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.FileSystem;
import java.nio.file.Path;

import java.util.concurrent.TimeUnit;
import java.util.Locale;

public class SimpleConsoleEventBusListenerTest {
  private static final String TARGET_ONE = "TARGET_ONE";
  private static final String TARGET_TWO = "TARGET_TWO";

  private FileSystem vfs;
  private Path logPath;

    @Before
    public void createTestLogFile() {
    vfs = Jimfs.newFileSystem(Configuration.unix());
    logPath = vfs.getPath("log.txt");
  }

  @Test
  public void testSimpleBuild() {
    String expectedOutput = "";
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    EventBus rawEventBus = BuckEventBusFactory.getEventBusFor(eventBus);
    TestConsole console = new TestConsole();

    BuildTarget fakeTarget = BuildTargetFactory.newInstance("//banana:stand");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(fakeTarget);
    Iterable<String> buildArgs = Iterables.transform(buildTargets, Functions.toStringFunction());
    FakeBuildRule fakeRule = new FakeBuildRule(
        fakeTarget,
        new SourcePathResolver(
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer())),
        ImmutableSortedSet.<BuildRule>of());

    SimpleConsoleEventBusListener listener = new SimpleConsoleEventBusListener(
        console,
        fakeClock,
        TestResultSummaryVerbosity.of(false, false),
        Locale.US,
        logPath);
    eventBus.register(listener);

    final long threadId = 0;

    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs);
    rawEventBus.post(
        configureTestEventAtTime(
            buildEventStarted,
            0L,
            TimeUnit.MILLISECONDS,
            threadId));
    ParseEvent.Started parseStarted = ParseEvent.started(buildTargets);
    rawEventBus.post(configureTestEventAtTime(
            parseStarted, 0L, TimeUnit.MILLISECONDS, threadId));

    assertOutput("", console);

    rawEventBus.post(configureTestEventAtTime(
        ParseEvent.finished(parseStarted,
            Optional.<TargetGraph>absent()),
            400L,
            TimeUnit.MILLISECONDS,
            threadId));

    expectedOutput += "[-] PARSING BUCK FILES...FINISHED 0.4s\n";
    assertOutput(expectedOutput, console);

    rawEventBus.post(
        configureTestEventAtTime(
            BuildRuleEvent.started(fakeRule),
            600L,
            TimeUnit.MILLISECONDS,
            threadId));

    HttpArtifactCacheEvent.Scheduled storeScheduledOne = postStoreScheduled(
        rawEventBus, threadId, TARGET_ONE, 700L);

    HttpArtifactCacheEvent.Scheduled storeScheduledTwo = postStoreScheduled(
        rawEventBus, threadId, TARGET_TWO, 700L);

    HttpArtifactCacheEvent.Started storeStartedOne =
        postStoreStarted(rawEventBus, threadId, 710L, storeScheduledOne);

    rawEventBus.post(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                fakeRule,
                BuildRuleKeys.of(new RuleKey("aaaa")),
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                Optional.<HashCode>absent(),
                Optional.<Long>absent()),
            1000L,
            TimeUnit.MILLISECONDS,
            threadId));
    rawEventBus.post(configureTestEventAtTime(
        BuildEvent.finished(buildEventStarted, 0), 1234L, TimeUnit.MILLISECONDS, threadId));

    expectedOutput += "BUILT //banana:stand\n" +
        "[-] BUILDING...FINISHED 0.8s\n" +
        "WAITING FOR HTTP CACHE UPLOADS (0 COMPLETE/0 FAILED/1 UPLOADING/1 PENDING)\n";
    assertOutput(expectedOutput, console);

    rawEventBus.post(configureTestEventAtTime(
        ConsoleEvent.severe("I've made a huge mistake."), 1500L, TimeUnit.MILLISECONDS, threadId));

    expectedOutput += "I've made a huge mistake.\n";
    assertOutput(expectedOutput, console);

    InstallEvent.Started installEventStarted = configureTestEventAtTime(
        InstallEvent.started(fakeTarget), 2500L, TimeUnit.MILLISECONDS, threadId);
    rawEventBus.post(installEventStarted);

    assertOutput(expectedOutput, console);

    rawEventBus.post(configureTestEventAtTime(
        InstallEvent.finished(installEventStarted, true, Optional.<Long>absent()),
        4000L,
        TimeUnit.MILLISECONDS,
        threadId));

    expectedOutput += "[-] INSTALLING...FINISHED 1.5s\n";
    assertOutput(expectedOutput, console);


    postStoreFinished(rawEventBus, threadId, 5015L, true, storeStartedOne);

    HttpArtifactCacheEvent.Started storeStartedTwo =
        postStoreStarted(rawEventBus, threadId, 5020L, storeScheduledTwo);

    postStoreFinished(rawEventBus, threadId, 5020L, false, storeStartedTwo);

    rawEventBus.post(configureTestEventAtTime(
            HttpArtifactCacheEvent.newShutdownEvent(),
            6000L,
            TimeUnit.MILLISECONDS,
            threadId));

    expectedOutput +=
        "[-] HTTP CACHE UPLOAD...FINISHED 5.3s (1 COMPLETE/1 FAILED/0 UPLOADING/0 PENDING)\n";
    assertOutput(expectedOutput, console);
  }

  private void assertOutput(String expectedOutput, TestConsole console) {
    assertEquals("", console.getTextWrittenToStdOut());
    assertEquals(expectedOutput, console.getTextWrittenToStdErr());
  }


}
