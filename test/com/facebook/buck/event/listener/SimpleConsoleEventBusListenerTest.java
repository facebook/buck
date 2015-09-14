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
import static org.junit.Assert.assertEquals;

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
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleStatus;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.rules.FakeBuildRule;
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

import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class SimpleConsoleEventBusListenerTest {
  @Test
  public void testSimpleBuild() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    EventBus rawEventBus = BuckEventBusFactory.getEventBusFor(eventBus);
    TestConsole console = new TestConsole();

    BuildTarget fakeTarget = BuildTargetFactory.newInstance("//banana:stand");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(fakeTarget);
    Iterable<String> buildArgs = Iterables.transform(buildTargets, Functions.toStringFunction());
    FakeBuildRule fakeRule = new FakeBuildRule(
        fakeTarget,
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSortedSet.<BuildRule>of());

    SimpleConsoleEventBusListener listener = new SimpleConsoleEventBusListener(
        console,
        fakeClock,
        TestResultSummaryVerbosity.of(false, false));
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

    assertEquals("", console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());

    rawEventBus.post(configureTestEventAtTime(
        ParseEvent.finished(parseStarted,
            Optional.<TargetGraph>absent()),
            400L,
            TimeUnit.MILLISECONDS,
            threadId));

    final String parsingLine = "[-] PARSING BUCK FILES...FINISHED 0.4s\n";

    assertEquals("", console.getTextWrittenToStdOut());
    assertEquals(parsingLine,
        console.getTextWrittenToStdErr());

    rawEventBus.post(configureTestEventAtTime(
        BuildRuleEvent.started(fakeRule), 600L, TimeUnit.MILLISECONDS, threadId));

    rawEventBus.post(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                fakeRule,
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

    final String buildingLine = "BUILT //banana:stand\n[-] BUILDING...FINISHED 0.8s\n";

    assertEquals("", console.getTextWrittenToStdOut());
    assertEquals(parsingLine + buildingLine,
        console.getTextWrittenToStdErr());

    rawEventBus.post(configureTestEventAtTime(
        ConsoleEvent.severe("I've made a huge mistake."), 1500L, TimeUnit.MILLISECONDS, threadId));

    final String logLine = "I've made a huge mistake.\n";

    assertEquals("", console.getTextWrittenToStdOut());
    assertEquals(parsingLine + buildingLine + logLine,
        console.getTextWrittenToStdErr());

    InstallEvent.Started installEventStarted = configureTestEventAtTime(
        InstallEvent.started(fakeTarget), 2500L, TimeUnit.MILLISECONDS, threadId);
    rawEventBus.post(installEventStarted);

    assertEquals("", console.getTextWrittenToStdOut());
    assertEquals(parsingLine + buildingLine + logLine,
        console.getTextWrittenToStdErr());

    rawEventBus.post(configureTestEventAtTime(
        InstallEvent.finished(installEventStarted, true, Optional.<Long>absent()),
        4000L,
        TimeUnit.MILLISECONDS,
        threadId));

    final String installLine = "[-] INSTALLING...FINISHED 1.5s\n";

    assertEquals("", console.getTextWrittenToStdOut());
    assertEquals(parsingLine + buildingLine + logLine + installLine,
        console.getTextWrittenToStdErr());
  }

}
