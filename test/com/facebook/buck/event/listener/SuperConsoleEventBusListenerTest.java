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
import com.facebook.buck.event.ProjectGenerationEvent;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.json.ProjectBuildFileParseEvents;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.rules.ActionGraphEvent;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleStatus;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.rules.CacheResult;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestRunEvent;
import com.facebook.buck.rules.TestSummaryEvent;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRuleEvent;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.EventBus;
import com.google.common.hash.HashCode;

import org.junit.Test;

import java.text.DecimalFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SuperConsoleEventBusListenerTest {
  private static final DecimalFormat timeFormatter = new DecimalFormat("0.0s");

  /**
   * Formats a string with times passed in in seconds.
   *
   * Used to avoid these tests failing if the user's locale doesn't use '.' as the decimal
   * separator, as was the case in https://github.com/facebook/buck/issues/58.
   */
  private static String formatConsoleTimes(String template, Double... time) {
    return String.format(template, (Object[]) FluentIterable.from(ImmutableList.copyOf(time))
        .transform(new Function<Double, String>() {
            @Override
            public String apply(Double input) {
              return timeFormatter.format(input);
            }
          }).toArray(String.class));
  }

  @Test
  public void testSimpleBuild() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    EventBus rawEventBus = BuckEventBusFactory.getEventBusFor(eventBus);
    TestConsole console = new TestConsole();

    BuildTarget fakeTarget = BuildTargetFactory.newInstance("//banana:stand");
    BuildTarget cachedTarget = BuildTargetFactory.newInstance("//chicken:dance");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(fakeTarget, cachedTarget);
    Iterable<String> buildArgs = Iterables.transform(buildTargets, Functions.toStringFunction());
    FakeBuildRule fakeRule = new FakeBuildRule(
        fakeTarget,
        pathResolver,
        ImmutableSortedSet.<BuildRule>of());
    FakeBuildRule cachedRule = new FakeBuildRule(
        cachedTarget,
        pathResolver,
        ImmutableSortedSet.<BuildRule>of());

    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            console,
            fakeClock,
            new DefaultExecutionEnvironment(
                new FakeProcessExecutor(),
                ImmutableMap.copyOf(System.getenv()),
                System.getProperties()),
        Optional.<WebServer>absent());
    eventBus.register(listener);

    ProjectBuildFileParseEvents.Started parseEventStarted =
        new ProjectBuildFileParseEvents.Started();
    rawEventBus.post(
        configureTestEventAtTime(
            parseEventStarted,
            0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    validateConsole(console, listener, 0L, ImmutableList.of(
        formatConsoleTimes("[+] PARSING BUCK FILES...%s", 0.0)));

    validateConsole(
        console, listener, 100L, ImmutableList.of(
            formatConsoleTimes("[+] PARSING BUCK FILES...%s", 0.1)));

    rawEventBus.post(
        configureTestEventAtTime(
            new ProjectBuildFileParseEvents.Finished(parseEventStarted),
            200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    validateConsole(
        console, listener, 200L, ImmutableList.of(
            formatConsoleTimes("[-] PARSING BUCK FILES...FINISHED %s", 0.2)));

    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs);
    rawEventBus.post(
        configureTestEventAtTime(
            buildEventStarted,
            200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    rawEventBus.post(configureTestEventAtTime(
        ParseEvent.started(buildTargets),
        200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(console, listener, 300L, ImmutableList.of(
        formatConsoleTimes("[+] PROCESSING BUCK FILES...%s", 0.1)));

    rawEventBus.post(
        configureTestEventAtTime(ParseEvent.finished(buildTargets,
                                                     Optional.<TargetGraph>absent()),
        300L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    rawEventBus.post(
        configureTestEventAtTime(
            ActionGraphEvent.finished(ActionGraphEvent.started()),
            400L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String parsingLine = formatConsoleTimes("[-] PROCESSING BUCK FILES...FINISHED %s", 0.2);

    validateConsole(console, listener, 540L, ImmutableList.of(parsingLine,
        formatConsoleTimes("[+] BUILDING...%s", 0.1)));

    rawEventBus.post(configureTestEventAtTime(
        BuildRuleEvent.started(fakeRule),
        600L, TimeUnit.MILLISECONDS, /* threadId */ 0L));


    validateConsole(console, listener, 800L, ImmutableList.of(parsingLine,
        formatConsoleTimes("[+] BUILDING...%s", 0.4),
        formatConsoleTimes(" |=> //banana:stand...  %s (checking local cache)", 0.2)));

    String stepShortName = "doing_something";
    String stepDescription = "working hard";
    UUID stepUuid = UUID.randomUUID();
    StepEvent.Started stepEventStarted =
        StepEvent.started(stepShortName, stepDescription, stepUuid);
    rawEventBus.post(configureTestEventAtTime(
            stepEventStarted,
          800L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(console, listener, 900L, ImmutableList.of(parsingLine,
        formatConsoleTimes("[+] BUILDING...%s", 0.5),
        formatConsoleTimes(" |=> //banana:stand...  %s (running doing_something[%s])", 0.3, 0.1)));

    rawEventBus.post(configureTestEventAtTime(StepEvent.finished(stepEventStarted, 0),
        900L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    rawEventBus.post(configureTestEventAtTime(
        BuildRuleEvent.finished(
            fakeRule,
            BuildRuleStatus.SUCCESS,
            CacheResult.miss(),
            Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
            Optional.<HashCode>absent(),
            Optional.<Long>absent()),
        1000L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(console, listener, 1000L, ImmutableList.of(parsingLine,
        formatConsoleTimes("[+] BUILDING...%s", 0.6),
        " |=> IDLE"));

    rawEventBus.post(configureTestEventAtTime(
        BuildRuleEvent.started(cachedRule),
        1010L, TimeUnit.MILLISECONDS, /* threadId */ 2L));

    validateConsole(console, listener, 1100L, ImmutableList.of(parsingLine,
        formatConsoleTimes("[+] BUILDING...%s", 0.7),
        " |=> IDLE",
        formatConsoleTimes(" |=> //chicken:dance...  %s (checking local cache)", 0.1)));

    rawEventBus.post(configureTestEventAtTime(
        BuildRuleEvent.finished(
            cachedRule,
            BuildRuleStatus.SUCCESS,
            CacheResult.miss(),
            Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
            Optional.<HashCode>absent(),
            Optional.<Long>absent()),
        1120L, TimeUnit.MILLISECONDS, /* threadId */ 2L));

    rawEventBus.post(configureTestEventAtTime(
        BuildEvent.finished(buildEventStarted, 0),
        1234L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    final String buildingLine = formatConsoleTimes("[-] BUILDING...FINISHED %s", 0.8);

    validateConsole(console, listener, 1300L, ImmutableList.of(parsingLine, buildingLine));

    rawEventBus.post(configureTestEventAtTime(
        ConsoleEvent.severe("I've made a huge mistake."),
        1500L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(console, listener, 1600L, ImmutableList.of(parsingLine,
        buildingLine,
        "Log:",
        "I've made a huge mistake."));

    InstallEvent.Started installEventStarted = InstallEvent.started(fakeTarget);
    rawEventBus.post(configureTestEventAtTime(
            installEventStarted,
        2500L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(console, listener, 3000L, ImmutableList.of(parsingLine,
        buildingLine,
        formatConsoleTimes("[+] INSTALLING...%s", 0.5),
        "Log:",
        "I've made a huge mistake."));

    rawEventBus.post(configureTestEventAtTime(
        InstallEvent.finished(installEventStarted, true, Optional.<Long>absent()),
        4000L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(console, listener, 5000L, ImmutableList.of(parsingLine,
        buildingLine,
        formatConsoleTimes("[-] INSTALLING...FINISHED %s", 1.5),
        "Log:",
        "I've made a huge mistake."));

    listener.render();
    String beforeStderrWrite = console.getTextWrittenToStdErr();
    console.getStdErr().print("ROFLCOPTER");
    listener.render();
    assertEquals("After stderr is written to by someone other than SuperConsole, rendering " +
        "should be a noop.",
        beforeStderrWrite + "ROFLCOPTER", console.getTextWrittenToStdErr());
  }

  @Test
  public void testSimpleTest() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    EventBus rawEventBus = BuckEventBusFactory.getEventBusFor(eventBus);
    TestConsole console = new TestConsole();

    BuildTarget testTarget = BuildTargetFactory.newInstance("//:test");
    ImmutableSet<BuildTarget> testTargets = ImmutableSet.of(testTarget);
    Iterable<String> testArgs = Iterables.transform(testTargets, Functions.toStringFunction());
    FakeBuildRule testBuildRule = new FakeBuildRule(
        testTarget,
        pathResolver,
        ImmutableSortedSet.<BuildRule>of());

    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            console,
            fakeClock,
            new DefaultExecutionEnvironment(
                new FakeProcessExecutor(),
                ImmutableMap.copyOf(System.getenv()),
                System.getProperties()),
        Optional.<WebServer>absent());
    eventBus.register(listener);

    ProjectBuildFileParseEvents.Started parseEventStarted =
        new ProjectBuildFileParseEvents.Started();
    rawEventBus.post(
        configureTestEventAtTime(
            parseEventStarted,
            0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    validateConsole(console, listener, 0L, ImmutableList.of(
        formatConsoleTimes("[+] PARSING BUCK FILES...%s", 0.0)));

    validateConsole(
            console, listener, 100L, ImmutableList.of(
                    formatConsoleTimes("[+] PARSING BUCK FILES...%s", 0.1)));

    rawEventBus.post(
        configureTestEventAtTime(
            new ProjectBuildFileParseEvents.Finished(parseEventStarted),
            200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    validateConsole(
        console, listener, 200L, ImmutableList.of(
            formatConsoleTimes("[-] PARSING BUCK FILES...FINISHED %s", 0.2)));

    BuildEvent.Started buildEventStarted = BuildEvent.started(testArgs);
    rawEventBus.post(
        configureTestEventAtTime(
            buildEventStarted,
            200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    rawEventBus.post(configureTestEventAtTime(
            ParseEvent.started(testTargets),
            200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(console, listener, 300L, ImmutableList.of(
        formatConsoleTimes("[+] PROCESSING BUCK FILES...%s", 0.1)));

    rawEventBus.post(
        configureTestEventAtTime(ParseEvent.finished(testTargets,
                                                     Optional.<TargetGraph>absent()),
        300L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    rawEventBus.post(
        configureTestEventAtTime(
            ActionGraphEvent.finished(ActionGraphEvent.started()),
            400L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String parsingLine = formatConsoleTimes("[-] PROCESSING BUCK FILES...FINISHED %s", 0.2);

    validateConsole(console, listener, 540L, ImmutableList.of(parsingLine,
        formatConsoleTimes("[+] BUILDING...%s", 0.1)));

    rawEventBus.post(configureTestEventAtTime(
        BuildRuleEvent.started(testBuildRule),
        600L, TimeUnit.MILLISECONDS, /* threadId */ 0L));


    validateConsole(console, listener, 800L, ImmutableList.of(parsingLine,
            formatConsoleTimes("[+] BUILDING...%s", 0.4),
            formatConsoleTimes(" |=> //:test...  %s (checking local cache)", 0.2)));

    rawEventBus.post(configureTestEventAtTime(
            BuildRuleEvent.finished(
                    testBuildRule,
                    BuildRuleStatus.SUCCESS,
                    CacheResult.miss(),
                    Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                    Optional.<HashCode>absent(),
                    Optional.<Long>absent()),
            1000L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    rawEventBus.post(configureTestEventAtTime(
                         BuildEvent.finished(buildEventStarted, 0),
                         1234L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    final String buildingLine = formatConsoleTimes("[-] BUILDING...FINISHED %s", 0.8);

    validateConsole(console, listener, 1300L, ImmutableList.of(parsingLine, buildingLine));

    rawEventBus.post(
        configureTestEventAtTime(
            TestRunEvent.started(
                    true, // isRunAllTests
                    TestSelectorList.empty(),
                    false, // shouldExplainTestSelectorList
                    ImmutableSet.copyOf(testArgs)),
            2500L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
            console,
            listener,
            3000L,
            ImmutableList.of(
                    parsingLine,
                    buildingLine,
                    formatConsoleTimes("[+] TESTING...%s", 0.5)));

    rawEventBus.post(
            configureTestEventAtTime(
                    TestRuleEvent.started(testTarget),
                    3100L,
                    TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
            console,
            listener,
            3200L,
            ImmutableList.of(
                    parsingLine,
                    buildingLine,
                    formatConsoleTimes("[+] TESTING...%s", 0.7),
                    formatConsoleTimes(" |=> //:test...  %s", 0.1)));

    UUID stepUuid = new UUID(0, 1);
    StepEvent.Started stepEventStarted = StepEvent.started(
        "step_name",
        "step_desc",
        stepUuid);
    rawEventBus.post(
        configureTestEventAtTime(
            stepEventStarted,
            3300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
            console,
            listener,
            3400L,
            ImmutableList.of(
                    parsingLine,
                    buildingLine,
                    formatConsoleTimes("[+] TESTING...%s", 0.9),
                    formatConsoleTimes(" |=> //:test...  %s (running step_name[%s])", 0.3, 0.1)));

    rawEventBus.post(
        configureTestEventAtTime(
            StepEvent.finished(stepEventStarted, 0),
            3500L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
            console,
            listener,
            3600L,
            ImmutableList.of(
                    parsingLine,
                    buildingLine,
                    formatConsoleTimes("[+] TESTING...%s", 1.1),
                    formatConsoleTimes(" |=> //:test...  %s", 0.5)));

    UUID testUUID = new UUID(2, 3);

    rawEventBus.post(
            configureTestEventAtTime(
                    TestSummaryEvent.started(testUUID, "TestClass", "Foo"),
                    3700L,
                    TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
            console,
            listener,
            3800L,
            ImmutableList.of(
                    parsingLine,
                    buildingLine,
                    formatConsoleTimes("[+] TESTING...%s", 1.3),
                    formatConsoleTimes(" |=> //:test...  %s (running Foo[%s])", 0.7, 0.1)));

    TestResultSummary testResultSummary =
        new TestResultSummary(
            "TestClass",
            "Foo",
            ResultType.SUCCESS,
            0L, // time
            null, // message
            null, // stacktrace
            null, // stdOut
            null); // stdErr
    rawEventBus.post(
        configureTestEventAtTime(
            TestSummaryEvent.finished(
                testUUID,
                testResultSummary),
            3900L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
            console,
            listener,
            4000L,
            ImmutableList.of(
                    parsingLine,
                    buildingLine,
                    formatConsoleTimes("[+] TESTING...%s (1 PASS/0 FAIL)", 1.5),
                    formatConsoleTimes(" |=> //:test...  %s", 0.9)));

    rawEventBus.post(
            configureTestEventAtTime(
                    TestRunEvent.finished(
                            ImmutableSet.copyOf(testArgs),
                            ImmutableList.of(
                                    new TestResults(
                                            testTarget,
                                            ImmutableList.of(
                                                    new TestCaseSummary(
                                                            "TestClass",
                                                            ImmutableList.of(
                                                                    testResultSummary))),
                                            ImmutableSet.<String>of(), // contacts
                                            ImmutableSet.<String>of()))), // labels
                    4100L,
                    TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String testingLine = formatConsoleTimes("[-] TESTING...FINISHED %s (1 PASS/0 FAIL)", 1.6);

    validateConsoleWithStdOutAndErr(
            console,
            listener,
            4200L,
            ImmutableList.of(
                    parsingLine,
                    buildingLine,
                    testingLine),
            Optional.of(
                    Joiner.on('\n').join(
                            "RESULTS FOR ALL TESTS",
                            "PASS    <100ms  1 Passed   0 Skipped   0 Failed   TestClass",
                            "TESTS PASSED",
                            "")),
            // We don't care about stderr, since the last frame will be flushed there.
            Optional.<String>absent());
  }

  @Test
  public void testSkippedTest() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    EventBus rawEventBus = BuckEventBusFactory.getEventBusFor(eventBus);
    TestConsole console = new TestConsole();

    BuildTarget testTarget = BuildTargetFactory.newInstance("//:test");
    ImmutableSet<BuildTarget> testTargets = ImmutableSet.of(testTarget);
    Iterable<String> testArgs = Iterables.transform(testTargets, Functions.toStringFunction());
    FakeBuildRule testBuildRule = new FakeBuildRule(
        testTarget,
        pathResolver,
        ImmutableSortedSet.<BuildRule>of());

    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            console,
            fakeClock,
            new DefaultExecutionEnvironment(
                new FakeProcessExecutor(),
                ImmutableMap.copyOf(System.getenv()),
                System.getProperties()),
        Optional.<WebServer>absent());
    eventBus.register(listener);

    ProjectBuildFileParseEvents.Started parseEventStarted =
        new ProjectBuildFileParseEvents.Started();
    rawEventBus.post(
        configureTestEventAtTime(
            parseEventStarted,
            0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    validateConsole(console, listener, 0L, ImmutableList.of(
        formatConsoleTimes("[+] PARSING BUCK FILES...%s", 0.0)));

    validateConsole(
        console, listener, 100L, ImmutableList.of(
            formatConsoleTimes("[+] PARSING BUCK FILES...%s", 0.1)));

    rawEventBus.post(
        configureTestEventAtTime(
            new ProjectBuildFileParseEvents.Finished(parseEventStarted),
            200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    validateConsole(
        console, listener, 200L, ImmutableList.of(
            formatConsoleTimes("[-] PARSING BUCK FILES...FINISHED %s", 0.2)));

    BuildEvent.Started buildEventStarted = BuildEvent.started(testArgs);
    rawEventBus.post(
        configureTestEventAtTime(
            buildEventStarted,
            200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    rawEventBus.post(configureTestEventAtTime(
        ParseEvent.started(testTargets),
        200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(console, listener, 300L, ImmutableList.of(
        formatConsoleTimes("[+] PROCESSING BUCK FILES...%s", 0.1)));

    rawEventBus.post(
        configureTestEventAtTime(ParseEvent.finished(testTargets,
                                                     Optional.<TargetGraph>absent()),
        300L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    rawEventBus.post(
        configureTestEventAtTime(
            ActionGraphEvent.finished(ActionGraphEvent.started()),
            400L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String parsingLine = formatConsoleTimes("[-] PROCESSING BUCK FILES...FINISHED %s", 0.2);

    validateConsole(console, listener, 540L, ImmutableList.of(parsingLine,
        formatConsoleTimes("[+] BUILDING...%s", 0.1)));

    rawEventBus.post(configureTestEventAtTime(
        BuildRuleEvent.started(testBuildRule),
        600L, TimeUnit.MILLISECONDS, /* threadId */ 0L));


    validateConsole(console, listener, 800L, ImmutableList.of(parsingLine,
        formatConsoleTimes("[+] BUILDING...%s", 0.4),
        formatConsoleTimes(" |=> //:test...  %s (checking local cache)", 0.2)));

    rawEventBus.post(configureTestEventAtTime(
        BuildRuleEvent.finished(
            testBuildRule,
            BuildRuleStatus.SUCCESS,
            CacheResult.miss(),
            Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
            Optional.<HashCode>absent(),
            Optional.<Long>absent()),
        1000L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    rawEventBus.post(configureTestEventAtTime(
                         BuildEvent.finished(buildEventStarted, 0),
                         1234L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    final String buildingLine = formatConsoleTimes("[-] BUILDING...FINISHED %s", 0.8);

    validateConsole(console, listener, 1300L, ImmutableList.of(parsingLine, buildingLine));

    rawEventBus.post(
        configureTestEventAtTime(
            TestRunEvent.started(
                true, // isRunAllTests
                TestSelectorList.empty(),
                false, // shouldExplainTestSelectorList
                ImmutableSet.copyOf(testArgs)),
            2500L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
        console,
        listener,
        3000L,
        ImmutableList.of(
            parsingLine,
            buildingLine,
            formatConsoleTimes("[+] TESTING...%s", 0.5)));

    rawEventBus.post(
        configureTestEventAtTime(
            TestRuleEvent.started(testTarget),
            3100L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
        console,
        listener,
        3200L,
        ImmutableList.of(
            parsingLine,
            buildingLine,
            formatConsoleTimes("[+] TESTING...%s", 0.7),
            formatConsoleTimes(" |=> //:test...  %s", 0.1)));

    UUID stepUuid = new UUID(0, 1);
    StepEvent.Started stepEventStarted = StepEvent.started(
        "step_name",
        "step_desc",
        stepUuid);
    rawEventBus.post(
        configureTestEventAtTime(
            stepEventStarted,
            3300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
        console,
        listener,
        3400L,
        ImmutableList.of(
            parsingLine,
            buildingLine,
            formatConsoleTimes("[+] TESTING...%s", 0.9),
            formatConsoleTimes(" |=> //:test...  %s (running step_name[%s])", 0.3, 0.1)));

    rawEventBus.post(
        configureTestEventAtTime(
            StepEvent.finished(stepEventStarted, 0),
            3500L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
        console,
        listener,
        3600L,
        ImmutableList.of(
            parsingLine,
            buildingLine,
            formatConsoleTimes("[+] TESTING...%s", 1.1),
            formatConsoleTimes(" |=> //:test...  %s", 0.5)));

    UUID testUUID = new UUID(2, 3);

    rawEventBus.post(
        configureTestEventAtTime(
            TestSummaryEvent.started(testUUID, "TestClass", "Foo"),
            3700L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
        console,
        listener,
        3800L,
        ImmutableList.of(
            parsingLine,
            buildingLine,
            formatConsoleTimes("[+] TESTING...%s", 1.3),
            formatConsoleTimes(" |=> //:test...  %s (running Foo[%s])", 0.7, 0.1)));

    TestResultSummary testResultSummary =
        new TestResultSummary(
            "TestClass",
            "Foo",
            ResultType.ASSUMPTION_VIOLATION,
            0L, // time
            null, // message
            null, // stacktrace
            null, // stdOut
            null); // stdErr
    rawEventBus.post(
        configureTestEventAtTime(
            TestSummaryEvent.finished(
                testUUID,
                testResultSummary),
            3900L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
        console,
        listener,
        4000L,
        ImmutableList.of(
            parsingLine,
            buildingLine,
            formatConsoleTimes("[+] TESTING...%s (0 PASS/1 SKIP/0 FAIL)", 1.5),
            formatConsoleTimes(" |=> //:test...  %s", 0.9)));

    rawEventBus.post(
        configureTestEventAtTime(
            TestRunEvent.finished(
                ImmutableSet.copyOf(testArgs),
                ImmutableList.of(
                    new TestResults(
                        testTarget,
                        ImmutableList.of(
                            new TestCaseSummary(
                                "TestClass",
                                ImmutableList.of(
                                    testResultSummary))),
                        ImmutableSet.<String>of(), // contacts
                        ImmutableSet.<String>of()))), // labels
            4100L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String testingLine = formatConsoleTimes(
        "[-] TESTING...FINISHED %s (0 PASS/1 SKIP/0 FAIL)",
        1.6);

    validateConsoleWithStdOutAndErr(
        console,
        listener,
        4200L,
        ImmutableList.of(
            parsingLine,
            buildingLine,
            testingLine),
        Optional.of(
            Joiner.on('\n').join(
                "RESULTS FOR ALL TESTS",
                "ASSUME  <100ms  0 Passed   1 Skipped   0 Failed   TestClass",
                "TESTS PASSED (with some assumption violations)",
                "")),
        // We don't care about stderr, since the last frame will be flushed there.
        Optional.<String>absent());
  }

  @Test
  public void testFailingTest() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    EventBus rawEventBus = BuckEventBusFactory.getEventBusFor(eventBus);
    TestConsole console = new TestConsole();

    BuildTarget testTarget = BuildTargetFactory.newInstance("//:test");
    ImmutableSet<BuildTarget> testTargets = ImmutableSet.of(testTarget);
    Iterable<String> testArgs = Iterables.transform(testTargets, Functions.toStringFunction());
    FakeBuildRule testBuildRule = new FakeBuildRule(
        testTarget,
        pathResolver,
        ImmutableSortedSet.<BuildRule>of());

    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            console,
            fakeClock,
            new DefaultExecutionEnvironment(
                new FakeProcessExecutor(),
                ImmutableMap.copyOf(System.getenv()),
                System.getProperties()),
        Optional.<WebServer>absent());
    eventBus.register(listener);

    ProjectBuildFileParseEvents.Started parseEventStarted =
        new ProjectBuildFileParseEvents.Started();
    rawEventBus.post(
        configureTestEventAtTime(
            parseEventStarted,
            0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    validateConsole(console, listener, 0L, ImmutableList.of(
        formatConsoleTimes("[+] PARSING BUCK FILES...%s", 0.0)));

    validateConsole(
        console, listener, 100L, ImmutableList.of(
            formatConsoleTimes("[+] PARSING BUCK FILES...%s", 0.1)));

    rawEventBus.post(
        configureTestEventAtTime(
            new ProjectBuildFileParseEvents.Finished(parseEventStarted),
            200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    validateConsole(
        console, listener, 200L, ImmutableList.of(
            formatConsoleTimes("[-] PARSING BUCK FILES...FINISHED %s", 0.2)));

    BuildEvent.Started buildEventStarted = BuildEvent.started(testArgs);
    rawEventBus.post(
        configureTestEventAtTime(
            buildEventStarted,
            200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    rawEventBus.post(configureTestEventAtTime(
        ParseEvent.started(testTargets),
        200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(console, listener, 300L, ImmutableList.of(
        formatConsoleTimes("[+] PROCESSING BUCK FILES...%s", 0.1)));

    rawEventBus.post(
        configureTestEventAtTime(ParseEvent.finished(testTargets,
                                                     Optional.<TargetGraph>absent()),
        300L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    rawEventBus.post(
        configureTestEventAtTime(
            ActionGraphEvent.finished(ActionGraphEvent.started()),
            400L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String parsingLine = formatConsoleTimes("[-] PROCESSING BUCK FILES...FINISHED %s", 0.2);

    validateConsole(console, listener, 540L, ImmutableList.of(parsingLine,
        formatConsoleTimes("[+] BUILDING...%s", 0.1)));

    rawEventBus.post(configureTestEventAtTime(
        BuildRuleEvent.started(testBuildRule),
        600L, TimeUnit.MILLISECONDS, /* threadId */ 0L));


    validateConsole(console, listener, 800L, ImmutableList.of(parsingLine,
        formatConsoleTimes("[+] BUILDING...%s", 0.4),
        formatConsoleTimes(" |=> //:test...  %s (checking local cache)", 0.2)));

    rawEventBus.post(configureTestEventAtTime(
        BuildRuleEvent.finished(
            testBuildRule,
            BuildRuleStatus.SUCCESS,
            CacheResult.miss(),
            Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
            Optional.<HashCode>absent(),
            Optional.<Long>absent()),
        1000L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    rawEventBus.post(configureTestEventAtTime(
                         BuildEvent.finished(buildEventStarted, 0),
                         1234L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    final String buildingLine = formatConsoleTimes("[-] BUILDING...FINISHED %s", 0.8);

    validateConsole(console, listener, 1300L, ImmutableList.of(parsingLine, buildingLine));

    rawEventBus.post(
        configureTestEventAtTime(
            TestRunEvent.started(
                true, // isRunAllTests
                TestSelectorList.empty(),
                false, // shouldExplainTestSelectorList
                ImmutableSet.copyOf(testArgs)),
            2500L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
        console,
        listener,
        3000L,
        ImmutableList.of(
            parsingLine,
            buildingLine,
            formatConsoleTimes("[+] TESTING...%s", 0.5)));

    rawEventBus.post(
        configureTestEventAtTime(
            TestRuleEvent.started(testTarget),
            3100L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
        console,
        listener,
        3200L,
        ImmutableList.of(
            parsingLine,
            buildingLine,
            formatConsoleTimes("[+] TESTING...%s", 0.7),
            formatConsoleTimes(" |=> //:test...  %s", 0.1)));

    UUID stepUuid = new UUID(0, 1);
    StepEvent.Started stepEventStarted = StepEvent.started(
        "step_name",
        "step_desc",
        stepUuid);
    rawEventBus.post(
        configureTestEventAtTime(
            stepEventStarted,
            3300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
        console,
        listener,
        3400L,
        ImmutableList.of(
            parsingLine,
            buildingLine,
            formatConsoleTimes("[+] TESTING...%s", 0.9),
            formatConsoleTimes(" |=> //:test...  %s (running step_name[%s])", 0.3, 0.1)));

    rawEventBus.post(
        configureTestEventAtTime(
            StepEvent.finished(stepEventStarted, 0),
            3500L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
        console,
        listener,
        3600L,
        ImmutableList.of(
            parsingLine,
            buildingLine,
            formatConsoleTimes("[+] TESTING...%s", 1.1),
            formatConsoleTimes(" |=> //:test...  %s", 0.5)));

    UUID testUUID = new UUID(2, 3);

    rawEventBus.post(
        configureTestEventAtTime(
            TestSummaryEvent.started(testUUID, "TestClass", "Foo"),
            3700L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
        console,
        listener,
        3800L,
        ImmutableList.of(
            parsingLine,
            buildingLine,
            formatConsoleTimes("[+] TESTING...%s", 1.3),
            formatConsoleTimes(" |=> //:test...  %s (running Foo[%s])", 0.7, 0.1)));

    TestResultSummary testResultSummary =
        new TestResultSummary(
            "TestClass",
            "Foo",
            ResultType.FAILURE,
            0L, // time
            "Foo.java:47: Assertion failure: 'foo' != 'bar'", // message
            null, // stacktrace
            "Message on stdout", // stdOut
            "Message on stderr"); // stdErr
    rawEventBus.post(
        configureTestEventAtTime(
            TestSummaryEvent.finished(
                testUUID,
                testResultSummary),
            3900L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
        console,
        listener,
        4000L,
        ImmutableList.of(
            parsingLine,
            buildingLine,
            formatConsoleTimes("[+] TESTING...%s (0 PASS/1 FAIL)", 1.5),
            formatConsoleTimes(" |=> //:test...  %s", 0.9),
            "Log:",
            "FAILURE TestClass Foo: Foo.java:47: Assertion failure: 'foo' != 'bar'"));

    rawEventBus.post(
        configureTestEventAtTime(
            TestRunEvent.finished(
                ImmutableSet.copyOf(testArgs),
                ImmutableList.of(
                    new TestResults(
                        testTarget,
                        ImmutableList.of(
                            new TestCaseSummary(
                                "TestClass",
                                ImmutableList.of(
                                    testResultSummary))),
                        ImmutableSet.<String>of(), // contacts
                        ImmutableSet.<String>of()))), // labels
            4100L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String testingLine = formatConsoleTimes("[-] TESTING...FINISHED %s (0 PASS/1 FAIL)", 1.6);

    validateConsoleWithStdOutAndErr(
        console,
        listener,
        4200L,
        ImmutableList.of(
            parsingLine,
            buildingLine,
            testingLine,
            "Log:",
            "FAILURE TestClass Foo: Foo.java:47: Assertion failure: 'foo' != 'bar'"),
        Optional.of(
            Joiner.on('\n').join(
                "RESULTS FOR ALL TESTS",
                "FAIL    <100ms  0 Passed   0 Skipped   1 Failed   TestClass",
                "FAILURE TestClass Foo: Foo.java:47: Assertion failure: 'foo' != 'bar'",
                "====STANDARD OUT====",
                "Message on stdout",
                "====STANDARD ERR====",
                "Message on stderr",
                "TESTS FAILED: 1 FAILURE",
                "Failed target: //:test",
                "FAIL TestClass",
                "")),
        // We don't care about stderr, since the last frame will be flushed there.
        Optional.<String>absent());
  }

  @Test
  public void testBuildRuleSuspendResumeEvents() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    EventBus rawEventBus = BuckEventBusFactory.getEventBusFor(eventBus);
    TestConsole console = new TestConsole();

    BuildTarget fakeTarget = BuildTargetFactory.newInstance("//banana:stand");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(fakeTarget);
    Iterable<String> buildArgs = Iterables.transform(buildTargets, Functions.toStringFunction());
    FakeBuildRule fakeRule = new FakeBuildRule(
        fakeTarget,
        pathResolver,
        ImmutableSortedSet.<BuildRule>of());
    String stepShortName = "doing_something";
    String stepDescription = "working hard";
    UUID stepUuid = UUID.randomUUID();

    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            console,
            fakeClock,
            new DefaultExecutionEnvironment(
                new FakeProcessExecutor(),
                ImmutableMap.copyOf(System.getenv()),
                System.getProperties()),
            Optional.<WebServer>absent());
    eventBus.register(listener);

    // Start the build.
    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs);
    rawEventBus.post(
        configureTestEventAtTime(
            buildEventStarted,
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    // Start and stop parsing.
    String parsingLine = formatConsoleTimes("[-] PROCESSING BUCK FILES...FINISHED 0.0s");
    rawEventBus.post(
            configureTestEventAtTime(
                    ParseEvent.started(buildTargets),
                    0L,
                    TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    rawEventBus.post(
        configureTestEventAtTime(
            ParseEvent.finished(buildTargets, Optional.<TargetGraph>absent()),
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    rawEventBus.post(
        configureTestEventAtTime(
            ActionGraphEvent.finished(ActionGraphEvent.started()),
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    // Start the rule.
    rawEventBus.post(
        configureTestEventAtTime(
            BuildRuleEvent.started(fakeRule),
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    // Post events that run a step for 100ms.
    StepEvent.Started stepEventStarted =
        StepEvent.started(stepShortName, stepDescription, stepUuid);
    rawEventBus.post(
        configureTestEventAtTime(
            stepEventStarted,
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    rawEventBus.post(
        configureTestEventAtTime(
            StepEvent.finished(stepEventStarted, /* exitCode */ 0),
            100L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    // Suspend the rule.
    rawEventBus.post(
            configureTestEventAtTime(
                    BuildRuleEvent.suspended(fakeRule),
                    100L,
                    TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    // Verify that the rule isn't printed now that it's suspended.
    validateConsole(
            console,
            listener,
            200L,
            ImmutableList.of(
                    parsingLine,
                    formatConsoleTimes("[+] BUILDING...%s", 0.2),
                    " |=> IDLE"));

    // Resume the rule.
    rawEventBus.post(
            configureTestEventAtTime(
                    BuildRuleEvent.resumed(fakeRule),
                    300L,
                    TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    // Verify that we print "checking local..." now that we've resumed, and that we're accounting
    // for previous running time.
    validateConsole(
            console,
            listener,
            300L,
            ImmutableList.of(
                    parsingLine,
                    formatConsoleTimes("[+] BUILDING...%s", 0.3),
                    formatConsoleTimes(" |=> //banana:stand...  %s (checking local cache)", 0.1)));

    // Post events that run another step.
    StepEvent.Started step2EventStarted =
        StepEvent.started(stepShortName, stepDescription, stepUuid);
    rawEventBus.post(
        configureTestEventAtTime(
            step2EventStarted,
            400L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    // Verify the current console now accounts for the step.
    validateConsole(
            console,
            listener,
            500L,
            ImmutableList.of(
                    parsingLine,
                    formatConsoleTimes("[+] BUILDING...%s", 0.5),
                    formatConsoleTimes(
                            " |=> //banana:stand...  %s (running doing_something[%s])",
                            0.3,
                            0.1)));

    // Finish the step and rule.
    rawEventBus.post(
        configureTestEventAtTime(
            StepEvent.finished(step2EventStarted, /* exitCode */ 0),
            600L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    rawEventBus.post(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                fakeRule,
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                Optional.<HashCode>absent(),
                Optional.<Long>absent()),
            600L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    // Verify that the rule isn't printed now that it's finally finished..
    validateConsole(
            console,
            listener,
            700L,
            ImmutableList.of(
                    parsingLine,
                    formatConsoleTimes("[+] BUILDING...%s", 0.7),
                    " |=> IDLE"));
  }

  @Test
  public void debugConsoleEventShouldNotPrintLogLineToConsole() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    EventBus rawEventBus = BuckEventBusFactory.getEventBusFor(eventBus);
    TestConsole console = new TestConsole();

    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            console,
            fakeClock,
            new DefaultExecutionEnvironment(
                new FakeProcessExecutor(),
                ImmutableMap.copyOf(System.getenv()),
                System.getProperties()),
        Optional.<WebServer>absent());
    eventBus.register(listener);

    rawEventBus.post(
        configureTestEventAtTime(
            ConsoleEvent.fine("I'll get you Bluths - Hel-loh"),
            0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    validateConsole(console, listener, 0L, ImmutableList.<String>of());
  }

  @Test
  public void testProjectGeneration() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    EventBus rawEventBus = BuckEventBusFactory.getEventBusFor(eventBus);
    TestConsole console = new TestConsole();
    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            console,
            fakeClock,
            new DefaultExecutionEnvironment(
                new FakeProcessExecutor(),
                ImmutableMap.copyOf(System.getenv()),
                System.getProperties()),
        Optional.<WebServer>absent());
    eventBus.register(listener);

    rawEventBus.post(
        configureTestEventAtTime(
            ProjectGenerationEvent.started(),
            0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(console, listener, 0L, ImmutableList.of(
        formatConsoleTimes("[+] GENERATING PROJECT...%s", 0.0)));

    rawEventBus.post(
        configureTestEventAtTime(
            new ProjectGenerationEvent.Finished(),
            0L, TimeUnit.MILLISECONDS, 0L));

    validateConsole(console, listener, 0L, ImmutableList.of(
        formatConsoleTimes("[-] GENERATING PROJECT...FINISHED %s", 0.0)));
}

  private void validateConsole(TestConsole console,
      SuperConsoleEventBusListener listener,
      long timeMs,
      ImmutableList<String> lines) {
    validateConsoleWithStdOutAndErr(
        console,
        listener,
        timeMs,
        lines,
        Optional.of(""),
        Optional.of(""));
  }

  private void validateConsoleWithStdOutAndErr(
      TestConsole console,
      SuperConsoleEventBusListener listener,
      long timeMs,
      ImmutableList<String> lines,
      Optional<String> stdout,
      Optional<String> stderr) {

    if (stdout.isPresent()) {
      assertEquals(stdout.get(), console.getTextWrittenToStdOut());
    }
    if (stderr.isPresent()) {
      assertEquals(stderr.get(), console.getTextWrittenToStdErr());
    }
    assertEquals(lines, listener.createRenderLinesAtTime(timeMs));
  }
}
