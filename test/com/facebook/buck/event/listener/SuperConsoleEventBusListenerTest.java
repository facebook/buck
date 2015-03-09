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

import com.facebook.buck.cli.InstallEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.ConsoleEvent;
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
import com.facebook.buck.rules.BuildRuleSuccess;
import com.facebook.buck.rules.CacheResult;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.shell.GenruleDescription;
import com.facebook.buck.step.FakeStep;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.eventbus.EventBus;

import org.junit.Test;

import java.text.DecimalFormat;
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
    FakeBuildRule fakeRule = new FakeBuildRule(
        GenruleDescription.TYPE,
        fakeTarget,
        pathResolver,
        ImmutableSortedSet.<BuildRule>of()
    );
    FakeBuildRule cachedRule = new FakeBuildRule(
        GenruleDescription.TYPE,
        cachedTarget,
        pathResolver,
        ImmutableSortedSet.<BuildRule>of()
    );

    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            console,
            fakeClock,
            new DefaultExecutionEnvironment(
                new FakeProcessExecutor(),
                ImmutableMap.copyOf(System.getenv()),
                System.getProperties()),
            /* isTreatingAssumptionsAsErrors */ false,
        Optional.<WebServer>absent());
    eventBus.register(listener);

    rawEventBus.post(configureTestEventAtTime(
        new ProjectBuildFileParseEvents.Started(),
        0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    validateConsole(console, listener, 0L, ImmutableList.of(
        formatConsoleTimes("[+] PARSING BUCK FILES...%s", 0.0)));

    validateConsole(console, listener, 100L, ImmutableList.of(
        formatConsoleTimes("[+] PARSING BUCK FILES...%s", 0.1)));

    rawEventBus.post(configureTestEventAtTime(
        new ProjectBuildFileParseEvents.Finished(),
        200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    validateConsole(console, listener, 200L, ImmutableList.of(
        formatConsoleTimes("[-] PARSING BUCK FILES...FINISHED %s", 0.2)));

    rawEventBus.post(configureTestEventAtTime(
        BuildEvent.started(buildTargets),
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
            ActionGraphEvent.finished(),
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

    FakeStep fakeStep = new FakeStep("doing_something", "working hard", 0);
    rawEventBus.post(configureTestEventAtTime(
        StepEvent.started(fakeStep, "working hard"),
          800L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(console, listener, 900L, ImmutableList.of(parsingLine,
        formatConsoleTimes("[+] BUILDING...%s", 0.5),
        formatConsoleTimes(" |=> //banana:stand...  %s (running doing_something[%s])", 0.3, 0.1)));

    rawEventBus.post(configureTestEventAtTime(
        StepEvent.finished(fakeStep, "working hard", 0),
        900L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    rawEventBus.post(configureTestEventAtTime(
        BuildRuleEvent.finished(
          fakeRule,
          BuildRuleStatus.SUCCESS,
          CacheResult.MISS,
          Optional.of(BuildRuleSuccess.Type.BUILT_LOCALLY)),
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
          CacheResult.MISS,
          Optional.of(BuildRuleSuccess.Type.BUILT_LOCALLY)),
        1120L, TimeUnit.MILLISECONDS, /* threadId */ 2L));

    rawEventBus.post(configureTestEventAtTime(
        BuildEvent.finished(buildTargets, 0),
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

    rawEventBus.post(configureTestEventAtTime(
        InstallEvent.started(fakeTarget),
        2500L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(console, listener, 3000L, ImmutableList.of(parsingLine,
        buildingLine,
        formatConsoleTimes("[+] INSTALLING...%s", 0.5),
        "Log:",
        "I've made a huge mistake."));

    rawEventBus.post(configureTestEventAtTime(
        InstallEvent.finished(fakeTarget, true),
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

  private void validateConsole(TestConsole console,
      SuperConsoleEventBusListener listener,
      long timeMs,
      ImmutableList<String> lines) {
    assertEquals("", console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());
    assertEquals(lines, listener.createRenderLinesAtTime(timeMs));
  }
}
