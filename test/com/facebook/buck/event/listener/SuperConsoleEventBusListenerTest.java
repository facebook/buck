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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.artifact_cache.ArtifactCacheEvent;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.DirArtifactCacheEvent;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.cli.CommandEvent;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.distributed.DistBuildStatus;
import com.facebook.buck.distributed.DistBuildStatusEvent;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.LogRecord;
import com.facebook.buck.event.ArtifactCompressionEvent;
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
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.BuildRuleKeys;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleStatus;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestRunEvent;
import com.facebook.buck.rules.TestSummaryEvent;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResultSummaryVerbosity;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRuleEvent;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.hash.HashCode;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.gson.Gson;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SuperConsoleEventBusListenerTest {
  private static final String TARGET_ONE = "TARGET_ONE";
  private static final String TARGET_TWO = "TARGET_TWO";
  private static final String DOWNLOAD_STRING =
      "[+] DOWNLOADING... (0.00 B/S, TOTAL: 0.00 B, 0 Artifacts)";
  private static final String FINISHED_DOWNLOAD_STRING =
      "[-] DOWNLOADING... (0.00 B/S AVG, TOTAL: 0.00 B, 0 Artifacts)";

  private static final TestResultSummaryVerbosity noisySummaryVerbosity =
      TestResultSummaryVerbosity.of(true, true);

  private static final TestResultSummaryVerbosity silentSummaryVerbosity =
      TestResultSummaryVerbosity.of(false, false);

  private static final DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  private FileSystem vfs;
  private Path logPath;
  private SuperConsoleConfig emptySuperConsoleConfig =
      new SuperConsoleConfig(FakeBuckConfig.builder().build());

  private final TimeZone timeZone = TimeZone.getTimeZone("UTC");

  @Before
  public void createTestLogFile() {
    vfs = Jimfs.newFileSystem(Configuration.unix());
    logPath = vfs.getPath("log.txt");
  }

  @Test
  public void testSimpleBuild() {
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
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
            new SuperConsoleConfig(FakeBuckConfig.builder().build()),
            console,
            fakeClock,
            silentSummaryVerbosity,
            new DefaultExecutionEnvironment(
                ImmutableMap.copyOf(System.getenv()),
                System.getProperties()),
            Optional.<WebServer>absent(),
            Locale.US,
            logPath,
            timeZone);
    eventBus.register(listener);

    ProjectBuildFileParseEvents.Started parseEventStarted =
        new ProjectBuildFileParseEvents.Started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            parseEventStarted,
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(console, listener, 0L, ImmutableList.of(
        "[+] PARSING BUCK FILES...0.0s"));

    validateConsole(
        console, listener, 100L, ImmutableList.of(
            "[+] PARSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectBuildFileParseEvents.Finished(parseEventStarted),
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(
        console, listener, 200L, ImmutableList.of(
            "[-] PARSING BUCK FILES...FINISHED 0.2s"));

    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted,
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    ParseEvent.Started parseStarted = ParseEvent.started(buildTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            parseStarted,
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 300L, ImmutableList.of(
        "[+] PROCESSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ParseEvent.finished(parseStarted, Optional.<TargetGraph>absent()),
            300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    ActionGraphEvent.Started actionGraphStarted = ActionGraphEvent.started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            actionGraphStarted,
            300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ActionGraphEvent.finished(actionGraphStarted),
            400L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String parsingLine = "[-] PROCESSING BUCK FILES...FINISHED 0.2s";

    validateConsole(console, listener, 540L, ImmutableList.of(parsingLine,
        DOWNLOAD_STRING,
        "[+] BUILDING...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.started(fakeRule),
            600L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 700L, ImmutableList.of(
        parsingLine,
        DOWNLOAD_STRING,
        "[+] BUILDING...0.3s",
        " |=> //banana:stand...  0.1s (checking local cache)"));

    ArtifactCompressionEvent.Started compressStarted = ArtifactCompressionEvent.started(
        ArtifactCompressionEvent.Operation.COMPRESS, ImmutableSet.<RuleKey>of());
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            compressStarted,
            701L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 702L, ImmutableList.of(parsingLine,
        DOWNLOAD_STRING,
        "[+] BUILDING...0.3s",
        " |=> //banana:stand...  0.1s (running artifact_compress[0.0s])"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ArtifactCompressionEvent.finished(compressStarted),
            703L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 704L, ImmutableList.of(parsingLine,
        DOWNLOAD_STRING,
        "[+] BUILDING...0.3s",
        " |=> //banana:stand...  0.1s (checking local cache)"));

    DirArtifactCacheEvent.DirArtifactCacheEventFactory dirArtifactCacheEventFactory =
        new DirArtifactCacheEvent.DirArtifactCacheEventFactory();

    ArtifactCacheEvent.Started dirFetchStarted = dirArtifactCacheEventFactory
        .newFetchStartedEvent(ImmutableSet.<RuleKey>of());

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            dirFetchStarted,
            740L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 741L, ImmutableList.of(parsingLine,
        DOWNLOAD_STRING,
        "[+] BUILDING...0.3s",
        " |=> //banana:stand...  0.1s (running dir_artifact_fetch[0.0s])"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            dirArtifactCacheEventFactory.newFetchFinishedEvent(
                dirFetchStarted,
                CacheResult.hit("dir")),
            742L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 800L, ImmutableList.of(parsingLine,
        DOWNLOAD_STRING,
        "[+] BUILDING...0.4s",
        " |=> //banana:stand...  0.2s (checking local cache)"));

    String stepShortName = "doing_something";
    String stepDescription = "working hard";
    UUID stepUuid = UUID.randomUUID();
    StepEvent.Started stepEventStarted =
        StepEvent.started(stepShortName, stepDescription, stepUuid);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            stepEventStarted,
            800L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 900L, ImmutableList.of(parsingLine,
        DOWNLOAD_STRING,
        "[+] BUILDING...0.5s",
        " |=> //banana:stand...  0.3s (running doing_something[0.1s])"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            StepEvent.finished(stepEventStarted, 0),
            900L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
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
            /* threadId */ 0L));

    validateConsole(console, listener, 1000L, ImmutableList.of(parsingLine,
        DOWNLOAD_STRING,
        "[+] BUILDING...0.6s",
        " |=> IDLE"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.started(cachedRule),
            1010L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 2L));

    validateConsole(console, listener, 1100L, ImmutableList.of(parsingLine,
        DOWNLOAD_STRING,
        "[+] BUILDING...0.7s",
        " |=> IDLE",
        " |=> //chicken:dance...  0.1s (checking local cache)"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                cachedRule,
                BuildRuleKeys.of(new RuleKey("aaaa")),
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                Optional.<HashCode>absent(),
                Optional.<Long>absent()),
            1120L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 2L));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.finished(buildEventStarted, 0),
            1234L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String buildingLine = "[-] BUILDING...FINISHED 0.8s";

    validateConsole(console, listener, 1300L, ImmutableList.of(parsingLine,
        FINISHED_DOWNLOAD_STRING,
        buildingLine));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ConsoleEvent.severe("I've made a huge mistake."),
            1500L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsoleWithLogLines(
        console,
        listener,
        1600L,
        ImmutableList.of(
            parsingLine,
            FINISHED_DOWNLOAD_STRING,
            buildingLine),
        ImmutableList.of("I've made a huge mistake."));

    InstallEvent.Started installEventStarted = InstallEvent.started(fakeTarget);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            installEventStarted,
            2500L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 3000L, ImmutableList.of(parsingLine,
        FINISHED_DOWNLOAD_STRING,
        buildingLine,
        "[+] INSTALLING...0.5s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            InstallEvent.finished(
                installEventStarted,
                true, Optional.<Long>absent(),
                Optional.<String>absent()),
            4000L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String installingFinished = "[-] INSTALLING...FINISHED 1.5s";

    validateConsole(console, listener, 5000L, ImmutableList.of(parsingLine,
        FINISHED_DOWNLOAD_STRING,
        buildingLine,
        installingFinished));

    HttpArtifactCacheEvent.Scheduled storeScheduledOne =
        postStoreScheduled(eventBus, 0L, TARGET_ONE, 6000L);

    HttpArtifactCacheEvent.Scheduled storeScheduledTwo =
        postStoreScheduled(eventBus, 0L, TARGET_TWO, 6010L);

    validateConsole(console, listener, 6011L, ImmutableList.of(parsingLine,
        FINISHED_DOWNLOAD_STRING,
        buildingLine,
        installingFinished,
        "[+] HTTP CACHE UPLOAD...0.0s (0 COMPLETE/0 FAILED/0 UPLOADING/2 PENDING)"));

    HttpArtifactCacheEvent.Started storeStartedOne =
        postStoreStarted(eventBus, 0, 6015L, storeScheduledOne);

    validateConsole(console, listener, 7000, ImmutableList.of(parsingLine,
        FINISHED_DOWNLOAD_STRING,
        buildingLine,
        installingFinished,
        "[+] HTTP CACHE UPLOAD...1.0s (0 COMPLETE/0 FAILED/1 UPLOADING/1 PENDING)"));

    postStoreFinished(eventBus, 0, 7020L, true, storeStartedOne);

    validateConsole(console, listener, 7020, ImmutableList.of(parsingLine,
        FINISHED_DOWNLOAD_STRING,
        buildingLine,
        installingFinished,
        "[+] HTTP CACHE UPLOAD...1.0s (1 COMPLETE/0 FAILED/0 UPLOADING/1 PENDING)"));

    HttpArtifactCacheEvent.Started storeStartedTwo =
        postStoreStarted(eventBus, 0, 7030L, storeScheduledTwo);
    postStoreFinished(eventBus, 0, 7030L, false, storeStartedTwo);

    validateConsole(console, listener, 7040, ImmutableList.of(parsingLine,
        FINISHED_DOWNLOAD_STRING,
        buildingLine,
        installingFinished,
        "[+] HTTP CACHE UPLOAD...1.0s (1 COMPLETE/1 FAILED/0 UPLOADING/0 PENDING)"));


    listener.render();
    String beforeStderrWrite = console.getTextWrittenToStdErr();
    console.getStdErr().print("ROFLCOPTER");
    listener.render();
    assertEquals("After stderr is written to by someone other than SuperConsole, rendering " +
            "should be a noop.",
        beforeStderrWrite + "ROFLCOPTER", console.getTextWrittenToStdErr());
  }

  @Test
  public void testSimpleBuildWithProgress() throws IOException {
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
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
            emptySuperConsoleConfig,
            console,
            fakeClock,
            silentSummaryVerbosity,
            new DefaultExecutionEnvironment(
                ImmutableMap.copyOf(System.getenv()),
                System.getProperties()),
            Optional.<WebServer>absent(),
            Locale.US,
            logPath,
            timeZone);
    ProgressEstimator e = new ProgressEstimator(
        getStorageForTest(),
        eventBus,
        ObjectMappers.newDefaultInstance());
    listener.setProgressEstimator(e);
    eventBus.register(listener);

    BuildEvent.RuleCountCalculated ruleCountCalculated =
        BuildEvent.ruleCountCalculated(ImmutableSet.<BuildTarget>of(), 10);
    eventBus.post(ruleCountCalculated);

    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted,
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    ParseEvent.Started parseStarted = ParseEvent.started(buildTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            parseStarted,
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 300L, ImmutableList.of(
        "[+] PROCESSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(ParseEvent.finished(parseStarted,
            Optional.<TargetGraph>absent()),
            300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    ActionGraphEvent.Started actionGraphStarted = ActionGraphEvent.started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            actionGraphStarted,
            300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ActionGraphEvent.finished(actionGraphStarted),
            400L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    final String parsingLine = "[-] PROCESSING BUCK FILES...FINISHED 0.2s";

    validateConsole(console, listener, 540L, ImmutableList.of(parsingLine,
        DOWNLOAD_STRING,
        "[+] BUILDING...0.1s" + " [0%] (0/10 JOBS, 0 UPDATED, " +
            "0 [0.0%] CACHE MISS)"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.started(fakeRule),
            600L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 800L, ImmutableList.of(parsingLine,
        DOWNLOAD_STRING,
        "[+] BUILDING...0.4s" + " [1%] (0/10 JOBS, 0 UPDATED, " +
            "0 [0.0%] CACHE MISS)",
        " |=> //banana:stand...  0.2s (checking local cache)"));

    String stepShortName = "doing_something";
    String stepDescription = "working hard";
    UUID stepUuid = UUID.randomUUID();
    StepEvent.Started stepEventStarted =
        StepEvent.started(stepShortName, stepDescription, stepUuid);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            stepEventStarted,
            800L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 900L, ImmutableList.of(parsingLine,
        DOWNLOAD_STRING,
        "[+] BUILDING...0.5s" + " [1%] (0/10 JOBS, 0 UPDATED, " +
            "0 [0.0%] CACHE MISS)",
        " |=> //banana:stand...  0.3s (running doing_something[0.1s])"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            StepEvent.finished(stepEventStarted, 0),
            900L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
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
            /* threadId */ 0L));

    validateConsole(console, listener, 1000L, ImmutableList.of(parsingLine,
        DOWNLOAD_STRING,
        "[+] BUILDING...0.6s [1%] (1/10 JOBS, 1 UPDATED, 1 [10.0%] CACHE MISS)",
        " |=> IDLE"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.started(cachedRule),
            1010L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 2L));

    validateConsole(console, listener, 1100L, ImmutableList.of(parsingLine,
        DOWNLOAD_STRING,
        "[+] BUILDING...0.7s [2%] (1/10 JOBS, 1 UPDATED, 1 [10.0%] CACHE MISS)",
        " |=> IDLE",
        " |=> //chicken:dance...  0.1s (checking local cache)"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                cachedRule,
                BuildRuleKeys.of(new RuleKey("aaaa")),
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                Optional.<HashCode>absent(),
                Optional.<Long>absent()),
            1120L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 2L));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.finished(buildEventStarted, 0),
            1234L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String buildingLine = "[-] BUILDING...FINISHED 0.8s" +
        " [100%] (2/10 JOBS, 2 UPDATED, 2 [20.0%] CACHE MISS)";

    validateConsole(console, listener, 1300L, ImmutableList.of(parsingLine,
        FINISHED_DOWNLOAD_STRING,
        buildingLine));
  }

  @Test
  public void testSimpleDistBuildWithProgress() throws IOException {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    TestConsole console = new TestConsole();

    BuildTarget fakeTarget = BuildTargetFactory.newInstance("//banana:stand");
    BuildTarget cachedTarget = BuildTargetFactory.newInstance("//chicken:dance");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(fakeTarget, cachedTarget);
    Iterable<String> buildArgs = Iterables.transform(buildTargets, Functions.toStringFunction());

    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            emptySuperConsoleConfig,
            console,
            fakeClock,
            silentSummaryVerbosity,
            new DefaultExecutionEnvironment(
                ImmutableMap.copyOf(System.getenv()),
                System.getProperties()),
            Optional.<WebServer>absent(),
            Locale.US,
            logPath,
            timeZone);
    ProgressEstimator e = new ProgressEstimator(
        getStorageForTest(),
        eventBus,
        ObjectMappers.newDefaultInstance());
    listener.setProgressEstimator(e);
    eventBus.register(listener);

    ProjectBuildFileParseEvents.Started parseEventStarted =
        new ProjectBuildFileParseEvents.Started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            parseEventStarted,
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(console, listener, 0L, ImmutableList.of(
        "[+] PARSING BUCK FILES...0.0s"));

    validateConsole(
        console, listener, 100L, ImmutableList.of(
            "[+] PARSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectBuildFileParseEvents.Finished(parseEventStarted),
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(
        console, listener, 200L, ImmutableList.of(
            "[-] PARSING BUCK FILES...FINISHED 0.2s"));

    // trigger a distributed build instead of a local build
    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs, true);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted,
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    ParseEvent.Started parseStarted = ParseEvent.started(buildTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            parseStarted,
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 300L, ImmutableList.of(
        "[+] PROCESSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(ParseEvent.finished(parseStarted,
            Optional.<TargetGraph>absent()),
            300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    ActionGraphEvent.Started actionGraphStarted = ActionGraphEvent.started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            actionGraphStarted,
            300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ActionGraphEvent.finished(actionGraphStarted),
            400L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String parsingLine = "[-] PROCESSING BUCK FILES...FINISHED 0.2s";

    validateConsole(console, listener, 540L, ImmutableList.of(parsingLine,
        DOWNLOAD_STRING,
        "[+] DISTBUILD STATUS: INIT...",
        "[+] BUILDING...0.1s [0%]"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildStatusEvent(
                DistBuildStatus.builder()
                    .setStatus(BuildStatus.QUEUED)
                    .setMessage("step 1")
                    .setETAMillis(2000)
                    .setLogBook(Optional.<List<LogRecord>>absent())
                    .build()),
            800L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 900L, ImmutableList.of(
        parsingLine,
        DOWNLOAD_STRING,
        "[+] DISTBUILD STATUS: QUEUED... ETA: 2.0s (step 1)",
        "[+] BUILDING...0.5s [29%]"));


    LinkedList<LogRecord> debugLogs = new LinkedList<LogRecord>();
    LogRecord log = new LogRecord();
    log.setName("buck-client");
    log.setTimestampMillis(0);
    debugLogs.add(log);
    final String distDebugLine = "Distributed build debug info:";
    final String logLine1 = "[1970-01-01 00:00:00.000] buck-client";

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildStatusEvent(
                DistBuildStatus.builder()
                    .setStatus(BuildStatus.BUILDING)
                    .setMessage("step 2")
                    .setETAMillis(1800)
                    .setLogBook(debugLogs)
                    .build()),
            1000L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 1100L, ImmutableList.of(
        distDebugLine,
        logLine1,
        parsingLine,
        DOWNLOAD_STRING,
        "[+] DISTBUILD STATUS: BUILDING... ETA: 1.8s (step 2)",
        "[+] BUILDING...0.7s [50%]"));


    log = new LogRecord();
    log.setName("buck-frontend");
    log.setTimestampMillis(100);
    debugLogs.add(log);
    final String logLine2 = "[1970-01-01 00:00:00.100] buck-frontend";

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildStatusEvent(
                DistBuildStatus.builder()
                    .setStatus(BuildStatus.BUILDING)
                    .setMessage("step 2")
                    .setETAMillis(1600)
                    .setLogBook(debugLogs)
                    .build()),
            1200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 1300L, ImmutableList.of(
        distDebugLine,
        logLine1,
        logLine2,
        parsingLine,
        DOWNLOAD_STRING,
        "[+] DISTBUILD STATUS: BUILDING... ETA: 1.6s (step 2)",
        "[+] BUILDING...0.9s [64%]"));


    log = new LogRecord();
    log.setName("build_slave_start");
    log.setTimestampMillis(200);
    debugLogs.add(log);
    final String logLine3 = "[1970-01-01 00:00:00.200] build_slave_start";

    log = new LogRecord();
    log.setName("build_slave_end");
    log.setTimestampMillis(300);
    debugLogs.add(log);
    final String logLine4 = "[1970-01-01 00:00:00.300] build_slave_end";

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildStatusEvent(
                DistBuildStatus.builder()
                    .setStatus(BuildStatus.FINISHED_SUCCESSFULLY)
                    .setMessage("step 3")
                    .setETAMillis(0)
                    .setLogBook(debugLogs)
                    .build()),
            1400L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String distbuildLine = "[-] DISTBUILD STATUS: FINISHED_SUCCESSFULLY... (step 3)";
    validateConsole(console, listener, 1500L, ImmutableList.of(
        distDebugLine,
        logLine1,
        logLine2,
        logLine3,
        logLine4,
        parsingLine,
        DOWNLOAD_STRING,
        distbuildLine,
        "[+] BUILDING...1.1s [100%]"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.finished(buildEventStarted, 0),
            1600L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String buildingLine = "[-] BUILDING...FINISHED 1.2s [100%]";

    validateConsole(console, listener, 1600L, ImmutableList.of(
        distDebugLine,
        logLine1,
        logLine2,
        logLine3,
        logLine4,
        parsingLine,
        FINISHED_DOWNLOAD_STRING,
        distbuildLine,
        buildingLine));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ConsoleEvent.severe("I've made a huge mistake."),
            1700L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsoleWithLogLines(
        console,
        listener,
        1750L,
        ImmutableList.of(
            distDebugLine,
            logLine1,
            logLine2,
            logLine3,
            logLine4,
            parsingLine,
            FINISHED_DOWNLOAD_STRING,
            distbuildLine,
            buildingLine),
        ImmutableList.of("I've made a huge mistake."));
  }

  @Test
  public void testSimpleTest() {
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
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
            emptySuperConsoleConfig,
            console,
            fakeClock,
            silentSummaryVerbosity,
            new DefaultExecutionEnvironment(
                ImmutableMap.copyOf(System.getenv()),
                System.getProperties()),
            Optional.<WebServer>absent(),
            Locale.US,
            logPath,
            timeZone);
    eventBus.register(listener);

    ProjectBuildFileParseEvents.Started parseEventStarted =
        new ProjectBuildFileParseEvents.Started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            parseEventStarted,
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(console, listener, 0L, ImmutableList.of(
        "[+] PARSING BUCK FILES...0.0s"));

    validateConsole(
        console, listener, 100L, ImmutableList.of(
            "[+] PARSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectBuildFileParseEvents.Finished(parseEventStarted),
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(
        console, listener, 200L, ImmutableList.of(
            "[-] PARSING BUCK FILES...FINISHED 0.2s"));

    BuildEvent.Started buildEventStarted = BuildEvent.started(testArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted,
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    ParseEvent.Started parseStarted = ParseEvent.started(testTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            parseStarted,
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 300L, ImmutableList.of(
        "[+] PROCESSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ParseEvent.finished(parseStarted, Optional.<TargetGraph>absent()),
            300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    ActionGraphEvent.Started actionGraphStarted = ActionGraphEvent.started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            actionGraphStarted,
            300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ActionGraphEvent.finished(actionGraphStarted),
            400L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String parsingLine = "[-] PROCESSING BUCK FILES...FINISHED 0.2s";

    validateConsole(console, listener, 540L, ImmutableList.of(parsingLine,
        DOWNLOAD_STRING,
        "[+] BUILDING...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.started(testBuildRule),
            600L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));


    validateConsole(console, listener, 800L, ImmutableList.of(parsingLine,
        DOWNLOAD_STRING,
        "[+] BUILDING...0.4s",
        " |=> //:test...  0.2s (checking local cache)"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                testBuildRule,
                BuildRuleKeys.of(new RuleKey("aaaa")),
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                Optional.<HashCode>absent(),
                Optional.<Long>absent()),
            1000L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.finished(buildEventStarted, 0),
            1234L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String buildingLine = "[-] BUILDING...FINISHED 0.8s";

    validateConsole(console, listener, 1300L, ImmutableList.of(parsingLine,
        FINISHED_DOWNLOAD_STRING,
        buildingLine));

    eventBus.postWithoutConfiguring(
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
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            "[+] TESTING...0.5s"));

    eventBus.postWithoutConfiguring(
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
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            "[+] TESTING...0.7s",
            " |=> //:test...  0.1s"));

    UUID stepUuid = new UUID(0, 1);
    StepEvent.Started stepEventStarted = StepEvent.started(
        "step_name",
        "step_desc",
        stepUuid);
    eventBus.postWithoutConfiguring(
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
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            "[+] TESTING...0.9s",
            " |=> //:test...  0.3s (running step_name[0.1s])"));

    eventBus.postWithoutConfiguring(
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
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            "[+] TESTING...1.1s",
            " |=> //:test...  0.5s"));

    UUID testUUID = new UUID(2, 3);

    eventBus.postWithoutConfiguring(
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
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            "[+] TESTING...1.3s",
            " |=> //:test...  0.7s (running Foo[0.1s])"));

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
    eventBus.postWithoutConfiguring(
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
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            "[+] TESTING...1.5s (1 PASS/0 FAIL)",
            " |=> //:test...  0.9s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            TestRunEvent.finished(
                ImmutableSet.copyOf(testArgs),
                ImmutableList.of(
                    TestResults.of(
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

    final String testingLine = "[-] TESTING...FINISHED 1.6s (1 PASS/0 FAIL)";

    validateConsoleWithStdOutAndErr(
        console,
        listener,
        4200L,
        ImmutableList.of(
            parsingLine,
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            testingLine),
        ImmutableList.<String>of(),
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
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
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
            emptySuperConsoleConfig,
            console,
            fakeClock,
            silentSummaryVerbosity,
            new DefaultExecutionEnvironment(
                ImmutableMap.copyOf(System.getenv()),
                System.getProperties()),
            Optional.<WebServer>absent(),
            Locale.US,
            logPath,
            timeZone);
    eventBus.register(listener);

    ProjectBuildFileParseEvents.Started parseEventStarted =
        new ProjectBuildFileParseEvents.Started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            parseEventStarted,
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(console, listener, 0L, ImmutableList.of(
        "[+] PARSING BUCK FILES...0.0s"));

    validateConsole(
        console, listener, 100L, ImmutableList.of(
            "[+] PARSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectBuildFileParseEvents.Finished(parseEventStarted),
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(
        console, listener, 200L, ImmutableList.of(
            "[-] PARSING BUCK FILES...FINISHED 0.2s"));

    BuildEvent.Started buildEventStarted = BuildEvent.started(testArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted,
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    ParseEvent.Started parseStarted = ParseEvent.started(testTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            parseStarted,
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 300L, ImmutableList.of(
        "[+] PROCESSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ParseEvent.finished(parseStarted, Optional.<TargetGraph>absent()),
            300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    ActionGraphEvent.Started actionGraphStarted = ActionGraphEvent.started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            actionGraphStarted,
            300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ActionGraphEvent.finished(actionGraphStarted),
            400L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String parsingLine = "[-] PROCESSING BUCK FILES...FINISHED 0.2s";

    validateConsole(console, listener, 540L, ImmutableList.of(parsingLine,
        DOWNLOAD_STRING,
        "[+] BUILDING...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.started(testBuildRule),
            600L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));


    validateConsole(console, listener, 800L, ImmutableList.of(parsingLine,
        DOWNLOAD_STRING,
        "[+] BUILDING...0.4s",
        " |=> //:test...  0.2s (checking local cache)"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                testBuildRule,
                BuildRuleKeys.of(new RuleKey("aaaa")),
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                Optional.<HashCode>absent(),
                Optional.<Long>absent()),
            1000L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.finished(buildEventStarted, 0),
            1234L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String buildingLine = "[-] BUILDING...FINISHED 0.8s";

    validateConsole(console, listener, 1300L, ImmutableList.of(parsingLine,
        FINISHED_DOWNLOAD_STRING,
        buildingLine));

    eventBus.postWithoutConfiguring(
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
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            "[+] TESTING...0.5s"));

    eventBus.postWithoutConfiguring(
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
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            "[+] TESTING...0.7s",
            " |=> //:test...  0.1s"));

    UUID stepUuid = new UUID(0, 1);
    StepEvent.Started stepEventStarted = StepEvent.started(
        "step_name",
        "step_desc",
        stepUuid);
    eventBus.postWithoutConfiguring(
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
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            "[+] TESTING...0.9s",
            " |=> //:test...  0.3s (running step_name[0.1s])"));

    eventBus.postWithoutConfiguring(
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
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            "[+] TESTING...1.1s",
            " |=> //:test...  0.5s"));

    UUID testUUID = new UUID(2, 3);

    eventBus.postWithoutConfiguring(
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
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            "[+] TESTING...1.3s",
            " |=> //:test...  0.7s (running Foo[0.1s])"));

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
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            TestSummaryEvent.finished(testUUID, testResultSummary),
            3900L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
        console,
        listener,
        4000L,
        ImmutableList.of(
            parsingLine,
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            "[+] TESTING...1.5s (0 PASS/1 SKIP/0 FAIL)",
            " |=> //:test...  0.9s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            TestRunEvent.finished(
                ImmutableSet.copyOf(testArgs),
                ImmutableList.of(
                    TestResults.of(
                        testTarget,
                        ImmutableList.of(
                            new TestCaseSummary("TestClass", ImmutableList.of(testResultSummary))),
                        ImmutableSet.<String>of(), // contacts
                        ImmutableSet.<String>of()))), // labels
            4100L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String testingLine = "[-] TESTING...FINISHED 1.6s (0 PASS/1 SKIP/0 FAIL)";

    validateConsoleWithStdOutAndErr(
        console,
        listener,
        4200L,
        ImmutableList.of(
            parsingLine,
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            testingLine),
        ImmutableList.<String>of(),
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
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
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
            emptySuperConsoleConfig,
            console,
            fakeClock,
            noisySummaryVerbosity,
            new DefaultExecutionEnvironment(
                ImmutableMap.copyOf(System.getenv()),
                System.getProperties()),
            Optional.<WebServer>absent(),
            Locale.US,
            logPath,
            timeZone);
    eventBus.register(listener);

    ProjectBuildFileParseEvents.Started parseEventStarted =
        new ProjectBuildFileParseEvents.Started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            parseEventStarted,
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(console, listener, 0L, ImmutableList.of(
        "[+] PARSING BUCK FILES...0.0s"));

    validateConsole(
        console, listener, 100L, ImmutableList.of(
            "[+] PARSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectBuildFileParseEvents.Finished(parseEventStarted),
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(
        console, listener, 200L, ImmutableList.of(
            "[-] PARSING BUCK FILES...FINISHED 0.2s"));

    BuildEvent.Started buildEventStarted = BuildEvent.started(testArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted,
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    ParseEvent.Started parseStarted = ParseEvent.started(testTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            parseStarted,
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 300L, ImmutableList.of(
        "[+] PROCESSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ParseEvent.finished(parseStarted, Optional.<TargetGraph>absent()),
            300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    ActionGraphEvent.Started actionGraphStarted = ActionGraphEvent.started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            actionGraphStarted,
            300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ActionGraphEvent.finished(actionGraphStarted),
            400L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String parsingLine = "[-] PROCESSING BUCK FILES...FINISHED 0.2s";

    validateConsole(console, listener, 540L, ImmutableList.of(parsingLine,
        DOWNLOAD_STRING,
        "[+] BUILDING...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.started(testBuildRule),
            600L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));


    validateConsole(console, listener, 800L, ImmutableList.of(parsingLine,
        DOWNLOAD_STRING,
        "[+] BUILDING...0.4s",
        " |=> //:test...  0.2s (checking local cache)"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                testBuildRule,
                BuildRuleKeys.of(new RuleKey("aaaa")),
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                Optional.<HashCode>absent(),
                Optional.<Long>absent()),
            1000L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.finished(buildEventStarted, 0),
            1234L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String buildingLine = "[-] BUILDING...FINISHED 0.8s";

    validateConsole(console, listener, 1300L, ImmutableList.of(parsingLine,
        FINISHED_DOWNLOAD_STRING,
        buildingLine));

    eventBus.postWithoutConfiguring(
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
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            "[+] TESTING...0.5s"));

    eventBus.postWithoutConfiguring(
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
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            "[+] TESTING...0.7s",
            " |=> //:test...  0.1s"));

    UUID stepUuid = new UUID(0, 1);
    StepEvent.Started stepEventStarted = StepEvent.started(
        "step_name",
        "step_desc",
        stepUuid);
    eventBus.postWithoutConfiguring(
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
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            "[+] TESTING...0.9s",
            " |=> //:test...  0.3s (running step_name[0.1s])"));

    eventBus.postWithoutConfiguring(
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
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            "[+] TESTING...1.1s",
            " |=> //:test...  0.5s"));

    UUID testUUID = new UUID(2, 3);

    eventBus.postWithoutConfiguring(
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
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            "[+] TESTING...1.3s",
            " |=> //:test...  0.7s (running Foo[0.1s])"));

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
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            TestSummaryEvent.finished(
                testUUID,
                testResultSummary),
            3900L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsoleWithLogLines(
        console,
        listener,
        4000L,
        ImmutableList.of(
            parsingLine,
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            "[+] TESTING...1.5s (0 PASS/1 FAIL)",
            " |=> //:test...  0.9s"),
        ImmutableList.of(
            "FAILURE TestClass Foo: Foo.java:47: Assertion failure: 'foo' != 'bar'"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            TestRunEvent.finished(
                ImmutableSet.copyOf(testArgs),
                ImmutableList.of(
                    TestResults.of(
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

    final String testingLine = "[-] TESTING...FINISHED 1.6s (0 PASS/1 FAIL)";

    validateConsoleWithStdOutAndErr(
        console,
        listener,
        4200L,
        ImmutableList.of(
            parsingLine,
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            testingLine),
        ImmutableList.<String>of(),
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
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
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

    FakeRuleKeyBuilderFactory ruleKeyBuilderFactory = new FakeRuleKeyBuilderFactory(
        ImmutableMap.of(
            fakeTarget, new RuleKey("aaaa")));
    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            emptySuperConsoleConfig,
            console,
            fakeClock,
            silentSummaryVerbosity,
            new DefaultExecutionEnvironment(
                ImmutableMap.copyOf(System.getenv()),
                System.getProperties()),
            Optional.<WebServer>absent(),
            Locale.US,
            logPath,
            timeZone);
    eventBus.register(listener);

    // Start the build.
    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted,
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    // Start and stop parsing.
    String parsingLine = "[-] PROCESSING BUCK FILES...FINISHED 0.0s";
    ParseEvent.Started parseStarted = ParseEvent.started(buildTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            parseStarted,
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ParseEvent.finished(parseStarted, Optional.<TargetGraph>absent()),
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ActionGraphEvent.finished(ActionGraphEvent.started()),
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    // Start the rule.
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.started(fakeRule),
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    // Post events that run a step for 100ms.
    StepEvent.Started stepEventStarted =
        StepEvent.started(stepShortName, stepDescription, stepUuid);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            stepEventStarted,
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            StepEvent.finished(stepEventStarted, /* exitCode */ 0),
            100L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    // Suspend the rule.
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.suspended(fakeRule, ruleKeyBuilderFactory),
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
            DOWNLOAD_STRING,
            "[+] BUILDING...0.2s",
            " |=> IDLE"));

    // Resume the rule.
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.resumed(fakeRule, ruleKeyBuilderFactory),
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
            DOWNLOAD_STRING,
            "[+] BUILDING...0.3s",
            " |=> //banana:stand...  0.1s (checking local cache)"));

    // Post events that run another step.
    StepEvent.Started step2EventStarted =
        StepEvent.started(stepShortName, stepDescription, stepUuid);
    eventBus.postWithoutConfiguring(
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
            DOWNLOAD_STRING,
            "[+] BUILDING...0.5s",
            " |=> //banana:stand...  0.3s (running doing_something[0.1s])"));

    // Finish the step and rule.
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            StepEvent.finished(step2EventStarted, /* exitCode */ 0),
            600L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                fakeRule,
                BuildRuleKeys.of(new RuleKey("aaaa")),
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
            DOWNLOAD_STRING,
            "[+] BUILDING...0.7s",
            " |=> IDLE"));
  }

  @Test
  public void debugConsoleEventShouldNotPrintLogLineToConsole() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    TestConsole console = new TestConsole();

    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            emptySuperConsoleConfig,
            console,
            fakeClock,
            silentSummaryVerbosity,
            new DefaultExecutionEnvironment(
                ImmutableMap.copyOf(System.getenv()),
                System.getProperties()),
            Optional.<WebServer>absent(),
            Locale.US,
            logPath,
            timeZone);
    eventBus.register(listener);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ConsoleEvent.fine("I'll get you Bluths - Hel-loh"),
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(console, listener, 0L, ImmutableList.<String>of());
  }

  @Test
  public void testProjectGeneration() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    TestConsole console = new TestConsole();
    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            emptySuperConsoleConfig,
            console,
            fakeClock,
            silentSummaryVerbosity,
            new DefaultExecutionEnvironment(
                ImmutableMap.copyOf(System.getenv()),
                System.getProperties()),
            Optional.<WebServer>absent(),
            Locale.US,
            logPath,
            timeZone);
    eventBus.register(listener);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ProjectGenerationEvent.started(),
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 0L, ImmutableList.of(
        "[+] GENERATING PROJECT...0.0s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectGenerationEvent.Finished(),
            0L,
            TimeUnit.MILLISECONDS,
            0L));

    validateConsole(console, listener, 0L, ImmutableList.of(
        "[-] GENERATING PROJECT...FINISHED 0.0s"));
  }

  private Path getStorageForTest() throws IOException {
    tmp.create();
    return tmp.newFile().toPath();
  }

  @Test
  public void testProjectGenerationWithProgress() throws IOException {
    Path storagePath = getStorageForTest();
    Map<String, Object> storageContents = ImmutableSortedMap.<String, Object>naturalOrder()
        .put(
            "project arg1 arg2",
            ImmutableSortedMap.<String, Number>naturalOrder()
                .put(ProgressEstimator.EXPECTED_NUMBER_OF_GENERATED_PROJECT_FILES, 10)
                .build())
        .build();
    String contents = new Gson().toJson(storageContents);
    Files.createDirectories(storagePath.getParent());
    Files.write(storagePath, contents.getBytes(StandardCharsets.UTF_8));

    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    TestConsole console = new TestConsole();
    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            emptySuperConsoleConfig,
            console,
            fakeClock,
            silentSummaryVerbosity,
            new DefaultExecutionEnvironment(
                ImmutableMap.copyOf(System.getenv()),
                System.getProperties()),
            Optional.<WebServer>absent(),
            Locale.US,
            logPath,
            timeZone);
    ProgressEstimator e = new ProgressEstimator(
        storagePath,
        eventBus,
        ObjectMappers.newDefaultInstance());
    listener.setProgressEstimator(e);
    eventBus.register(listener);

    eventBus.post(
        CommandEvent.started(
            "project",
            ImmutableList.of("arg1", "arg2"),
            false));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ProjectGenerationEvent.started(),
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 0L, ImmutableList.of(
        "[+] GENERATING PROJECT...0.0s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ProjectGenerationEvent.processed(),
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ProjectGenerationEvent.processed(),
            100L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 100L, ImmutableList.of(
        "[+] GENERATING PROJECT...0.1s [20%]"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectGenerationEvent.Finished(),
            200L,
            TimeUnit.MILLISECONDS,
            0L));

    validateConsole(console, listener, 0L, ImmutableList.of(
        "[-] GENERATING PROJECT...FINISHED 0.2s [100%]"));
  }

  @Test
  public void testPostingEventBeforeAnyLines() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    TestConsole console = new TestConsole();
    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            emptySuperConsoleConfig,
            console,
            fakeClock,
            silentSummaryVerbosity,
            new DefaultExecutionEnvironment(
                ImmutableMap.copyOf(System.getenv()),
                System.getProperties()),
            Optional.<WebServer>absent(),
            Locale.US,
            logPath,
            timeZone);
    eventBus.register(listener);

    eventBus.post(ConsoleEvent.info("Hello world!"));

    validateConsoleWithLogLines(
        console,
        listener,
        0L,
        ImmutableList.<String>of(),
        ImmutableList.of("Hello world!"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ProjectGenerationEvent.started(),
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 0L, ImmutableList.of(
        "[+] GENERATING PROJECT...0.0s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectGenerationEvent.Finished(),
            0L,
            TimeUnit.MILLISECONDS,
            0L));

    validateConsole(console, listener, 0L, ImmutableList.of(
        "[-] GENERATING PROJECT...FINISHED 0.0s"));
  }

  @Test
  public void renderLinesWithLineLimit() throws IOException {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    TestConsole console = new TestConsole();
    try (SuperConsoleEventBusListener listener =
             new SuperConsoleEventBusListener(
                 emptySuperConsoleConfig,
                 console,
                 fakeClock,
                 silentSummaryVerbosity,
                 new DefaultExecutionEnvironment(
                     ImmutableMap.copyOf(System.getenv()),
                     System.getProperties()),
                 Optional.<WebServer>absent(),
                 Locale.US,
                 logPath,
                 timeZone)) {

      FakeThreadStateRenderer fakeRenderer =
          new FakeThreadStateRenderer(ImmutableList.of(2L, 1L, 4L, 8L, 5L));
      ImmutableList.Builder<String> lines;

      ImmutableList<String> fullOutput = ImmutableList.of(
          " |=> Status of thread 2",
          " |=> Status of thread 1",
          " |=> Status of thread 4",
          " |=> Status of thread 8",
          " |=> Status of thread 5");

      lines = ImmutableList.builder();
      listener.renderLines(fakeRenderer, lines, 10000, false);
      assertThat(
          lines.build(),
          equalTo(fullOutput));
      assertThat(fakeRenderer.lastSortWasByTime(), is(false));

      lines = ImmutableList.builder();
      listener.renderLines(fakeRenderer, lines, 10000, true);
      assertThat(
          lines.build(),
          equalTo(fullOutput));
      assertThat(fakeRenderer.lastSortWasByTime(), is(true));

      lines = ImmutableList.builder();
      listener.renderLines(fakeRenderer, lines, 6, false);
      assertThat(
          lines.build(),
          equalTo(fullOutput));
      assertThat(fakeRenderer.lastSortWasByTime(), is(false));

      lines = ImmutableList.builder();
      listener.renderLines(fakeRenderer, lines, 5, false);
      assertThat(
          lines.build(),
          equalTo(fullOutput));
      assertThat(fakeRenderer.lastSortWasByTime(), is(false));

      lines = ImmutableList.builder();
      listener.renderLines(fakeRenderer, lines, 4, false);
      assertThat(
          lines.build(),
          equalTo(
              ImmutableList.of(
                  " |=> Status of thread 2",
                  " |=> Status of thread 1",
                  " |=> Status of thread 4",
                  " |=> 2 MORE THREADS: t8 t5")));
      assertThat(fakeRenderer.lastSortWasByTime(), is(true));

      lines = ImmutableList.builder();
      listener.renderLines(fakeRenderer, lines, 2, false);
      assertThat(
          lines.build(),
          equalTo(
              ImmutableList.of(
                  " |=> Status of thread 2",
                  " |=> 4 MORE THREADS: t1 t4 t8 t5")));
      assertThat(fakeRenderer.lastSortWasByTime(), is(true));

      lines = ImmutableList.builder();
      listener.renderLines(fakeRenderer, lines, 1, false);
      assertThat(
          lines.build(),
          equalTo(
              ImmutableList.of(
                  " |=> 5 THREADS: t2 t1 t4 t8 t5")));
      assertThat(fakeRenderer.lastSortWasByTime(), is(true));
    }
  }

  private void validateConsole(TestConsole console,
      SuperConsoleEventBusListener listener,
      long timeMs,
      ImmutableList<String> lines) {
    validateConsoleWithLogLines(
        console,
        listener,
        timeMs,
        lines,
        ImmutableList.<String>of());
  }

  private void validateConsoleWithLogLines(TestConsole console,
      SuperConsoleEventBusListener listener,
      long timeMs,
      ImmutableList<String> lines,
      ImmutableList<String> logLines) {
    validateConsoleWithStdOutAndErr(
        console,
        listener,
        timeMs,
        lines,
        logLines,
        Optional.of(""),
        Optional.of(""));
  }

  private void validateConsoleWithStdOutAndErr(
      TestConsole console,
      SuperConsoleEventBusListener listener,
      long timeMs,
      ImmutableList<String> lines,
      ImmutableList<String> logLines,
      Optional<String> stdout,
      Optional<String> stderr) {

    if (stdout.isPresent()) {
      assertThat(console.getTextWrittenToStdOut(), equalTo(stdout.get()));
    }
    if (stderr.isPresent()) {
      assertThat(console.getTextWrittenToStdErr(), equalTo(stderr.get()));
    }
    assertThat(listener.createRenderLinesAtTime(timeMs), equalTo(lines));
    assertThat(listener.createLogRenderLines(), equalTo(logLines));
  }

  @Test
  public void timestampsInLocaleWithDecimalCommaFormatCorrectly() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    TestConsole console = new TestConsole();
    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            new SuperConsoleConfig(FakeBuckConfig.builder().build()),
            console,
            fakeClock,
            silentSummaryVerbosity,
            new DefaultExecutionEnvironment(
                ImmutableMap.copyOf(System.getenv()),
                System.getProperties()),
            Optional.<WebServer>absent(),
            // Note we use de_DE to ensure we get a decimal comma in the output.
            Locale.GERMAN,
            logPath,
            timeZone);
    eventBus.register(listener);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ProjectGenerationEvent.started(),
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 0L, ImmutableList.of(
                        "[+] GENERATING PROJECT...0,0s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectGenerationEvent.Finished(),
            0L,
            TimeUnit.MILLISECONDS,
            0L));

    validateConsole(console, listener, 0L, ImmutableList.of(
                        "[-] GENERATING PROJECT...FINISHED 0,0s"));
  }

  @Test
  public void testBuildTimeDoesNotDisplayNegativeOffset() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    TestConsole console = new TestConsole();

    BuildTarget fakeTarget = BuildTargetFactory.newInstance("//banana:stand");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(fakeTarget);
    Iterable<String> buildArgs = Iterables.transform(buildTargets, Functions.toStringFunction());

    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            new SuperConsoleConfig(FakeBuckConfig.builder().build()),
            console,
            fakeClock,
            silentSummaryVerbosity,
            new DefaultExecutionEnvironment(
                ImmutableMap.copyOf(System.getenv()),
                System.getProperties()),
            Optional.<WebServer>absent(),
            Locale.US,
            logPath,
            timeZone);
    eventBus.register(listener);

    // Do a full parse and action graph cycle before the build event starts
    // This sequencing occurs when running `buck project`
    ParseEvent.Started parseStarted = ParseEvent.started(buildTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            parseStarted,
            100L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ParseEvent.finished(parseStarted, Optional.<TargetGraph>absent()),
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    ActionGraphEvent.Started actionGraphStarted = ActionGraphEvent.started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            actionGraphStarted,
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(console, listener, 200L, ImmutableList.of("[+] PROCESSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ActionGraphEvent.finished(actionGraphStarted),
            300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted,
            300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));


    final String parsingLine = "[-] PROCESSING BUCK FILES...FINISHED 0.2s";

    validateConsole(console, listener, 433L, ImmutableList.of(parsingLine,
        DOWNLOAD_STRING,
        "[+] BUILDING...0.1s"));
  }
}
