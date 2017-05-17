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
import static com.facebook.buck.event.listener.SuperConsoleEventBusListener.EMOJI_BUNNY;
import static com.facebook.buck.event.listener.SuperConsoleEventBusListener.EMOJI_DESERT;
import static com.facebook.buck.event.listener.SuperConsoleEventBusListener.EMOJI_ROLODEX;
import static com.facebook.buck.event.listener.SuperConsoleEventBusListener.EMOJI_SNAIL;
import static com.facebook.buck.event.listener.SuperConsoleEventBusListener.EMOJI_WHALE;
import static com.facebook.buck.event.listener.SuperConsoleEventBusListener.NEW_DAEMON_INSTANCE_MSG;
import static com.facebook.buck.event.listener.SuperConsoleEventBusListener.createParsingMessage;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.artifact_cache.ArtifactCacheEvent;
import com.facebook.buck.artifact_cache.ArtifactCacheMode;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.DirArtifactCacheEvent;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.distributed.DistBuildStatus;
import com.facebook.buck.distributed.DistBuildStatusEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.CacheRateStats;
import com.facebook.buck.distributed.thrift.RunId;
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.ArtifactCompressionEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.DaemonEvent;
import com.facebook.buck.event.InstallEvent;
import com.facebook.buck.event.ParsingEvent;
import com.facebook.buck.event.ProjectGenerationEvent;
import com.facebook.buck.event.WatchmanStatusEvent;
import com.facebook.buck.json.ProjectBuildFileParseEvents;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRuleCacheEvent;
import com.facebook.buck.rules.BuildRuleDurationTracker;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.BuildRuleKeys;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleStatus;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestRunEvent;
import com.facebook.buck.rules.TestSummaryEvent;
import com.facebook.buck.rules.keys.FakeRuleKeyFactory;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResultSummaryVerbosity;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRuleEvent;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.facebook.buck.util.autosparse.AutoSparseStateEvents;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.facebook.buck.util.unit.SizeUnit;
import com.facebook.buck.util.versioncontrol.SparseSummary;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class SuperConsoleEventBusListenerTest {
  private static final String TARGET_ONE = "TARGET_ONE";
  private static final String TARGET_TWO = "TARGET_TWO";
  private static final String TARGET_THREE = "TARGET_THREE";
  private static final String DOWNLOAD_STRING =
      "[+] DOWNLOADING... (0.00 B/S, TOTAL: 0.00 B, 0 Artifacts)";
  private static final String FINISHED_DOWNLOAD_STRING =
      "[-] DOWNLOADING... (0.00 B/S AVG, TOTAL: 0.00 B, 0 Artifacts)";
  private static final String SEVERE_MESSAGE = "This is a sample severe message.";

  private static final TestResultSummaryVerbosity noisySummaryVerbosity =
      TestResultSummaryVerbosity.of(true, true);

  private static final TestResultSummaryVerbosity silentSummaryVerbosity =
      TestResultSummaryVerbosity.of(false, false);

  @Rule public final TemporaryPaths tmp = new TemporaryPaths();

  private FileSystem vfs;
  private Path logPath;
  private BuildRuleDurationTracker durationTracker;
  private SuperConsoleConfig emptySuperConsoleConfig =
      new SuperConsoleConfig(FakeBuckConfig.builder().build());

  private final TimeZone timeZone = TimeZone.getTimeZone("UTC");

  @Before
  public void setUp() {
    vfs = Jimfs.newFileSystem(Configuration.unix());
    logPath = vfs.getPath("log.txt");
    durationTracker = new BuildRuleDurationTracker();
  }

  @Test
  public void testSimpleBuild() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    SourcePathResolver pathResolver =
        new SourcePathResolver(
            new SourcePathRuleFinder(
                new BuildRuleResolver(
                    TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())));

    BuildTarget fakeTarget = BuildTargetFactory.newInstance("//banana:stand");
    BuildTarget cachedTarget = BuildTargetFactory.newInstance("//chicken:dance");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(fakeTarget, cachedTarget);
    Iterable<String> buildArgs = Iterables.transform(buildTargets, Object::toString);
    FakeBuildRule fakeRule = new FakeBuildRule(fakeTarget, pathResolver, ImmutableSortedSet.of());
    FakeBuildRule cachedRule =
        new FakeBuildRule(cachedTarget, pathResolver, ImmutableSortedSet.of());

    ProjectBuildFileParseEvents.Started parseEventStarted =
        new ProjectBuildFileParseEvents.Started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(parseEventStarted, 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    validateConsole(listener, 0L, ImmutableList.of("[+] PARSING BUCK FILES...0.0s"));

    validateConsole(listener, 100L, ImmutableList.of("[+] PARSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectBuildFileParseEvents.Finished(parseEventStarted),
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(listener, 200L, ImmutableList.of("[-] PARSING BUCK FILES...FINISHED 0.2s"));

    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted, 200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    ParseEvent.Started parseStarted = ParseEvent.started(buildTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(parseStarted, 200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(listener, 300L, ImmutableList.of("[+] PROCESSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ParseEvent.finished(parseStarted, 10, Optional.empty()),
            300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    ActionGraphEvent.Started actionGraphStarted = ActionGraphEvent.started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            actionGraphStarted, 300L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ActionGraphEvent.finished(actionGraphStarted),
            400L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String parsingLine = "[-] PROCESSING BUCK FILES...FINISHED 0.2s";

    validateConsole(
        listener, 540L, ImmutableList.of(parsingLine, DOWNLOAD_STRING, "[+] BUILDING...0.1s"));

    BuildRuleEvent.Started started = BuildRuleEvent.started(fakeRule, durationTracker);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(started, 600L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
        listener,
        700L,
        ImmutableList.of(
            parsingLine,
            DOWNLOAD_STRING,
            "[+] BUILDING...0.3s",
            " |=> //banana:stand...  0.1s (checking_cache)"));

    BuildRuleCacheEvent.CacheStepStarted cacheStepStarted =
        BuildRuleCacheEvent.started(fakeRule, BuildRuleCacheEvent.CacheStepType.INPUT_BASED);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(cacheStepStarted, 701L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
        listener,
        701L,
        ImmutableList.of(
            parsingLine,
            DOWNLOAD_STRING,
            "[+] BUILDING...0.3s",
            " |=> //banana:stand...  0.1s (running checking_cache_input_based[0.0s])"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleCacheEvent.finished(cacheStepStarted),
            702L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
        listener,
        702L,
        ImmutableList.of(
            parsingLine,
            DOWNLOAD_STRING,
            "[+] BUILDING...0.3s",
            " |=> //banana:stand...  0.1s (checking_cache)"));

    ArtifactCompressionEvent.Started compressStarted =
        ArtifactCompressionEvent.started(
            ArtifactCompressionEvent.Operation.COMPRESS, ImmutableSet.of());
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(compressStarted, 703L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
        listener,
        703L,
        ImmutableList.of(
            parsingLine,
            DOWNLOAD_STRING,
            "[+] BUILDING...0.3s",
            " |=> //banana:stand...  0.1s (running artifact_compress[0.0s])"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ArtifactCompressionEvent.finished(compressStarted),
            704L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
        listener,
        705L,
        ImmutableList.of(
            parsingLine,
            DOWNLOAD_STRING,
            "[+] BUILDING...0.3s",
            " |=> //banana:stand...  0.1s (checking_cache)"));

    DirArtifactCacheEvent.DirArtifactCacheEventFactory dirArtifactCacheEventFactory =
        new DirArtifactCacheEvent.DirArtifactCacheEventFactory();

    ArtifactCacheEvent.Started dirFetchStarted =
        dirArtifactCacheEventFactory.newFetchStartedEvent(ImmutableSet.of());

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(dirFetchStarted, 740L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
        listener,
        741L,
        ImmutableList.of(
            parsingLine,
            DOWNLOAD_STRING,
            "[+] BUILDING...0.3s",
            " |=> //banana:stand...  0.1s (running dir_artifact_fetch[0.0s])"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            dirArtifactCacheEventFactory.newFetchFinishedEvent(
                dirFetchStarted, CacheResult.hit("dir", ArtifactCacheMode.dir)),
            742L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
        listener,
        800L,
        ImmutableList.of(
            parsingLine,
            DOWNLOAD_STRING,
            "[+] BUILDING...0.4s",
            " |=> //banana:stand...  0.2s (checking_cache)"));

    String stepShortName = "doing_something";
    String stepDescription = "working hard";
    UUID stepUuid = UUID.randomUUID();
    StepEvent.Started stepEventStarted =
        StepEvent.started(stepShortName, stepDescription, stepUuid);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(stepEventStarted, 800L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
        listener,
        900L,
        ImmutableList.of(
            parsingLine,
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
                started,
                BuildRuleKeys.of(new RuleKey("aaaa")),
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.empty(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            1000L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
        listener,
        1000L,
        ImmutableList.of(parsingLine, DOWNLOAD_STRING, "[+] BUILDING...0.6s", " |=> IDLE"));

    BuildRuleEvent.Started startedCached = BuildRuleEvent.started(cachedRule, durationTracker);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(startedCached, 1010L, TimeUnit.MILLISECONDS, /* threadId */ 2L));

    validateConsole(
        listener,
        1100L,
        ImmutableList.of(
            parsingLine,
            DOWNLOAD_STRING,
            "[+] BUILDING...0.7s",
            " |=> IDLE",
            " |=> //chicken:dance...  0.1s (checking_cache)"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                startedCached,
                BuildRuleKeys.of(new RuleKey("aaaa")),
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.empty(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
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

    validateConsole(
        listener, 1300L, ImmutableList.of(parsingLine, FINISHED_DOWNLOAD_STRING, buildingLine));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ConsoleEvent.severe(SEVERE_MESSAGE), 1500L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsoleWithLogLines(
        listener,
        1600L,
        ImmutableList.of(parsingLine, FINISHED_DOWNLOAD_STRING, buildingLine),
        ImmutableList.of(SEVERE_MESSAGE));

    InstallEvent.Started installEventStarted = InstallEvent.started(fakeTarget);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            installEventStarted, 2500L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
        listener,
        3000L,
        ImmutableList.of(
            parsingLine, FINISHED_DOWNLOAD_STRING, buildingLine, "[+] INSTALLING...0.5s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            InstallEvent.finished(installEventStarted, true, Optional.empty(), Optional.empty()),
            4000L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String installingFinished = "[-] INSTALLING...FINISHED 1.5s";

    validateConsole(
        listener,
        5000L,
        ImmutableList.of(parsingLine, FINISHED_DOWNLOAD_STRING, buildingLine, installingFinished));

    HttpArtifactCacheEvent.Scheduled storeScheduledOne =
        ArtifactCacheTestUtils.postStoreScheduled(eventBus, 0L, TARGET_ONE, 6000L);

    HttpArtifactCacheEvent.Scheduled storeScheduledTwo =
        ArtifactCacheTestUtils.postStoreScheduled(eventBus, 0L, TARGET_TWO, 6010L);

    HttpArtifactCacheEvent.Scheduled storeScheduledThree =
        ArtifactCacheTestUtils.postStoreScheduled(eventBus, 0L, TARGET_THREE, 6020L);

    validateConsole(
        listener,
        6021L,
        ImmutableList.of(
            parsingLine,
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            installingFinished,
            "[+] HTTP CACHE UPLOAD...0.00 B (0 COMPLETE/0 FAILED/0 UPLOADING/3 PENDING)"));

    HttpArtifactCacheEvent.Started storeStartedOne =
        ArtifactCacheTestUtils.postStoreStarted(eventBus, 0, 6025L, storeScheduledOne);

    validateConsole(
        listener,
        7000,
        ImmutableList.of(
            parsingLine,
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            installingFinished,
            "[+] HTTP CACHE UPLOAD...0.00 B (0 COMPLETE/0 FAILED/1 UPLOADING/2 PENDING)"));

    long artifactSizeOne = SizeUnit.KILOBYTES.toBytes(1.5);
    ArtifactCacheTestUtils.postStoreFinished(
        eventBus, 0, artifactSizeOne, 7020L, true, storeStartedOne);

    validateConsole(
        listener,
        7020,
        ImmutableList.of(
            parsingLine,
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            installingFinished,
            "[+] HTTP CACHE UPLOAD...1.50 KB (1 COMPLETE/0 FAILED/0 UPLOADING/2 PENDING)"));

    HttpArtifactCacheEvent.Started storeStartedTwo =
        ArtifactCacheTestUtils.postStoreStarted(eventBus, 0, 7030L, storeScheduledTwo);
    long artifactSizeTwo = SizeUnit.KILOBYTES.toBytes(1.6);
    ArtifactCacheTestUtils.postStoreFinished(
        eventBus, 0, artifactSizeTwo, 7030L, false, storeStartedTwo);

    validateConsole(
        listener,
        7040,
        ImmutableList.of(
            parsingLine,
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            installingFinished,
            "[+] HTTP CACHE UPLOAD...1.50 KB (1 COMPLETE/1 FAILED/0 UPLOADING/1 PENDING)"));

    HttpArtifactCacheEvent.Started storeStartedThree =
        ArtifactCacheTestUtils.postStoreStarted(eventBus, 0, 7040L, storeScheduledThree);
    long artifactSizeThree = SizeUnit.KILOBYTES.toBytes(0.6);
    ArtifactCacheTestUtils.postStoreFinished(
        eventBus, 0, artifactSizeThree, 7040L, true, storeStartedThree);

    validateConsole(
        listener,
        7040,
        ImmutableList.of(
            parsingLine,
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            installingFinished,
            "[+] HTTP CACHE UPLOAD...2.10 KB (2 COMPLETE/1 FAILED/0 UPLOADING/0 PENDING)"));

    listener.render();
    TestConsole console = (TestConsole) listener.console;
    String beforeStderrWrite = console.getTextWrittenToStdErr();
    console.getStdErr().print("ROFLCOPTER");
    listener.render();
    assertEquals(
        "After stderr is written to by someone other than SuperConsole, rendering "
            + "should be a noop.",
        beforeStderrWrite + "ROFLCOPTER",
        console.getTextWrittenToStdErr());
  }

  @Test
  public void testSimpleBuildWithProgress() throws IOException {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    SourcePathResolver pathResolver =
        new SourcePathResolver(
            new SourcePathRuleFinder(
                new BuildRuleResolver(
                    TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())));

    BuildTarget fakeTarget = BuildTargetFactory.newInstance("//banana:stand");
    BuildTarget cachedTarget = BuildTargetFactory.newInstance("//chicken:dance");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(fakeTarget, cachedTarget);
    Iterable<String> buildArgs = Iterables.transform(buildTargets, Object::toString);
    FakeBuildRule fakeRule = new FakeBuildRule(fakeTarget, pathResolver, ImmutableSortedSet.of());
    FakeBuildRule cachedRule =
        new FakeBuildRule(cachedTarget, pathResolver, ImmutableSortedSet.of());

    ProgressEstimator e = new ProgressEstimator(getStorageForTest(), eventBus);
    listener.setProgressEstimator(e);
    eventBus.register(listener);

    BuildEvent.RuleCountCalculated ruleCountCalculated =
        BuildEvent.ruleCountCalculated(ImmutableSet.of(), 10);
    eventBus.post(ruleCountCalculated);

    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted, 200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    ParseEvent.Started parseStarted = ParseEvent.started(buildTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(parseStarted, 200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(listener, 300L, ImmutableList.of("[+] PROCESSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ParseEvent.finished(parseStarted, 10, Optional.empty()),
            300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    ActionGraphEvent.Started actionGraphStarted = ActionGraphEvent.started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            actionGraphStarted, 300L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ActionGraphEvent.finished(actionGraphStarted),
            400L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    final String parsingLine = "[-] PROCESSING BUCK FILES...FINISHED 0.2s";

    validateConsole(
        listener,
        540L,
        ImmutableList.of(
            parsingLine,
            DOWNLOAD_STRING,
            "[+] BUILDING...0.1s" + " [0%] (0/10 JOBS, 0 UPDATED, " + "0 [0.0%] CACHE MISS)"));

    BuildRuleEvent.Started started = BuildRuleEvent.started(fakeRule, durationTracker);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(started, 600L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
        listener,
        800L,
        ImmutableList.of(
            parsingLine,
            DOWNLOAD_STRING,
            "[+] BUILDING...0.4s" + " [0%] (0/10 JOBS, 0 UPDATED, " + "0 [0.0%] CACHE MISS)",
            " |=> //banana:stand...  0.2s (checking_cache)"));

    String stepShortName = "doing_something";
    String stepDescription = "working hard";
    UUID stepUuid = UUID.randomUUID();
    StepEvent.Started stepEventStarted =
        StepEvent.started(stepShortName, stepDescription, stepUuid);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(stepEventStarted, 800L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
        listener,
        900L,
        ImmutableList.of(
            parsingLine,
            DOWNLOAD_STRING,
            "[+] BUILDING...0.5s" + " [0%] (0/10 JOBS, 0 UPDATED, " + "0 [0.0%] CACHE MISS)",
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
                started,
                BuildRuleKeys.of(new RuleKey("aaaa")),
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.empty(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            1000L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
        listener,
        1000L,
        ImmutableList.of(
            parsingLine,
            DOWNLOAD_STRING,
            "[+] BUILDING...0.6s [10%] (1/10 JOBS, 1 UPDATED, 1 [10.0%] CACHE MISS)",
            " |=> IDLE"));

    BuildRuleEvent.Started startedCached = BuildRuleEvent.started(cachedRule, durationTracker);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(startedCached, 1010L, TimeUnit.MILLISECONDS, /* threadId */ 2L));

    validateConsole(
        listener,
        1100L,
        ImmutableList.of(
            parsingLine,
            DOWNLOAD_STRING,
            "[+] BUILDING...0.7s [10%] (1/10 JOBS, 1 UPDATED, 1 [10.0%] CACHE MISS)",
            " |=> IDLE",
            " |=> //chicken:dance...  0.1s (checking_cache)"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                startedCached,
                BuildRuleKeys.of(new RuleKey("aaaa")),
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.empty(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            1120L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 2L));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.finished(buildEventStarted, 0),
            1234L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String buildingLine =
        "[-] BUILDING...FINISHED 0.8s" + " [100%] (2/10 JOBS, 2 UPDATED, 2 [20.0%] CACHE MISS)";

    validateConsole(
        listener, 1300L, ImmutableList.of(parsingLine, FINISHED_DOWNLOAD_STRING, buildingLine));
  }

  @Test
  public void testSimpleDistBuildWithProgress() throws IOException {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    BuildTarget fakeTarget = BuildTargetFactory.newInstance("//banana:stand");
    BuildTarget cachedTarget = BuildTargetFactory.newInstance("//chicken:dance");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(fakeTarget, cachedTarget);
    Iterable<String> buildArgs = Iterables.transform(buildTargets, Object::toString);

    ProgressEstimator e = new ProgressEstimator(getStorageForTest(), eventBus);
    listener.setProgressEstimator(e);
    eventBus.register(listener);

    long timeMillis = 0;

    ProjectBuildFileParseEvents.Started parseEventStarted =
        new ProjectBuildFileParseEvents.Started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            parseEventStarted, timeMillis, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    validateConsole(listener, timeMillis, ImmutableList.of("[+] PARSING BUCK FILES...0.0s"));

    timeMillis += 100;
    validateConsole(listener, timeMillis, ImmutableList.of("[+] PARSING BUCK FILES...0.1s"));

    timeMillis += 100;
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectBuildFileParseEvents.Finished(parseEventStarted),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(
        listener, timeMillis, ImmutableList.of("[-] PARSING BUCK FILES...FINISHED 0.2s"));

    // trigger a distributed build instead of a local build
    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted, timeMillis, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    ParseEvent.Started parseStarted = ParseEvent.started(buildTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            parseStarted, timeMillis, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    timeMillis += 100;
    validateConsole(listener, timeMillis, ImmutableList.of("[+] PROCESSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ParseEvent.finished(parseStarted, 10, Optional.empty()),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    BuildEvent.DistBuildStarted distBuildStartedEvent = BuildEvent.distBuildStarted();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            distBuildStartedEvent, timeMillis, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    timeMillis += 100;
    ActionGraphEvent.Started actionGraphStarted = ActionGraphEvent.started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            actionGraphStarted, timeMillis, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    timeMillis += 100;
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ActionGraphEvent.finished(actionGraphStarted),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    timeMillis += 150;
    final String parsingLine = "[-] PROCESSING BUCK FILES...FINISHED 0.2s";

    validateConsole(
        listener, timeMillis, ImmutableList.of(parsingLine, "[+] DISTBUILD...0.3s (STATUS: INIT)"));

    timeMillis += 250;
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildStatusEvent(
                DistBuildStatus.builder()
                    .setStatus(BuildStatus.QUEUED.toString())
                    .setMessage("step 1")
                    .setLogBook(Optional.empty())
                    .build()),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    timeMillis += 100;
    validateConsole(
        listener,
        timeMillis,
        ImmutableList.of(parsingLine, "[+] DISTBUILD...0.7s (STATUS: QUEUED, [step 1])"));

    timeMillis += 100;
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildStatusEvent(
                DistBuildStatus.builder()
                    .setStatus(BuildStatus.BUILDING.toString())
                    .setMessage("step 2")
                    .build()),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    timeMillis += 100;
    validateConsole(
        listener,
        timeMillis,
        ImmutableList.of(parsingLine, "[+] DISTBUILD...0.9s (STATUS: BUILDING, [step 2])"));

    RunId runId1 = new RunId();
    runId1.setId("slave1");
    BuildSlaveStatus slave1 = new BuildSlaveStatus();
    slave1.setRunId(runId1);

    RunId runId2 = new RunId();
    runId2.setId("slave2");
    BuildSlaveStatus slave2 = new BuildSlaveStatus();
    slave2.setRunId(runId2);

    timeMillis += 100;
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildStatusEvent(
                DistBuildStatus.builder()
                    .setStatus(BuildStatus.BUILDING.toString())
                    .setMessage("step 2")
                    .setSlaveStatuses(ImmutableList.of(slave1, slave2))
                    .build()),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    timeMillis += 100;
    validateConsole(
        listener,
        timeMillis,
        ImmutableList.of(
            parsingLine,
            "[+] DISTBUILD...1.1s (STATUS: BUILDING, [step 2])",
            " SERVER 0)=> PROCESSING BUILD GRAPH...",
            " SERVER 1)=> PROCESSING BUILD GRAPH..."));

    timeMillis += 100;
    slave1.setTotalRulesCount(10);
    slave1.setRulesFinishedCount(5);
    slave1.setRulesSuccessCount(5);
    CacheRateStats cacheRateStatsForSlave1 = new CacheRateStats();
    slave1.setCacheRateStats(cacheRateStatsForSlave1);
    cacheRateStatsForSlave1.setTotalRulesCount(10);
    cacheRateStatsForSlave1.setUpdatedRulesCount(5);
    cacheRateStatsForSlave1.setCacheHitsCount(4);
    cacheRateStatsForSlave1.setCacheMissesCount(1);

    slave2.setTotalRulesCount(20);
    slave2.setRulesStartedCount(5);
    slave2.setRulesFinishedCount(5);
    slave2.setRulesSuccessCount(4);
    slave2.setRulesFailureCount(1);
    CacheRateStats cacheRateStatsForSlave2 = new CacheRateStats();
    slave2.setCacheRateStats(cacheRateStatsForSlave2);
    cacheRateStatsForSlave2.setTotalRulesCount(20);
    cacheRateStatsForSlave2.setUpdatedRulesCount(5);
    cacheRateStatsForSlave2.setCacheHitsCount(5);
    cacheRateStatsForSlave2.setCacheMissesCount(0);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildStatusEvent(
                DistBuildStatus.builder()
                    .setStatus(BuildStatus.BUILDING.toString())
                    .setMessage("step 2")
                    .setSlaveStatuses(ImmutableList.of(slave1, slave2))
                    .build()),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    timeMillis += 100;
    validateConsole(
        listener,
        timeMillis,
        ImmutableList.of(
            parsingLine,
            "[+] DISTBUILD...1.3s [33%] (STATUS: BUILDING, 1 [3.3%] CACHE MISS, [step 2])",
            " SERVER 0)=> IDLE... (BUILT 5/10 JOBS, 1 [10.0%] CACHE MISS)",
            " SERVER 1)=> WORKING ON 5 JOBS... (BUILT 5/20 JOBS, 1 JOBS FAILED, 0 [0.0%] CACHE MISS)"));

    timeMillis += 100;
    slave1.setRulesStartedCount(1);
    slave1.setRulesFinishedCount(9);
    slave1.setRulesSuccessCount(9);
    cacheRateStatsForSlave1.setUpdatedRulesCount(9);
    cacheRateStatsForSlave1.setCacheHitsCount(8);
    cacheRateStatsForSlave1.setCacheMissesCount(1);

    slave2.setRulesStartedCount(0);
    slave2.setRulesFinishedCount(20);
    slave2.setRulesSuccessCount(19);
    slave2.setHttpArtifactUploadsScheduledCount(3);
    slave2.setHttpArtifactUploadsOngoingCount(1);
    slave2.setHttpArtifactUploadsSuccessCount(1);
    slave2.setHttpArtifactUploadsFailureCount(1);
    cacheRateStatsForSlave2.setUpdatedRulesCount(20);
    cacheRateStatsForSlave2.setCacheHitsCount(19);
    cacheRateStatsForSlave2.setCacheMissesCount(0);
    cacheRateStatsForSlave2.setCacheErrorsCount(1);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildStatusEvent(
                DistBuildStatus.builder()
                    .setStatus("CUSTOM")
                    .setMessage("step 2")
                    .setSlaveStatuses(ImmutableList.of(slave1, slave2))
                    .build()),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    timeMillis += 100;
    validateConsole(
        listener,
        timeMillis,
        ImmutableList.of(
            parsingLine,
            "[+] DISTBUILD...1.5s [96%] (STATUS: CUSTOM,"
                + " 1 [3.3%] CACHE MISS, 1 [3.4%] CACHE ERRORS, 1 UPLOAD ERRORS, [step 2])",
            " SERVER 0)=> WORKING ON 1 JOBS... (BUILT 9/10 JOBS, 1 [10.0%] CACHE MISS)",
            " SERVER 1)=> IDLE... (BUILT 20/20 JOBS, 1 JOBS FAILED, 0 [0.0%] CACHE MISS, "
                + "1 [5.0%] CACHE ERRORS, 1/3 UPLOADED, 1 UPLOAD ERRORS)"));

    timeMillis += 100;
    slave1.setRulesStartedCount(0);
    slave1.setRulesFinishedCount(10);
    slave1.setRulesSuccessCount(10);
    cacheRateStatsForSlave1.setUpdatedRulesCount(10);
    cacheRateStatsForSlave1.setCacheHitsCount(9);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildStatusEvent(
                DistBuildStatus.builder()
                    .setStatus(BuildStatus.FINISHED_SUCCESSFULLY.toString())
                    .setMessage("step 3")
                    .setSlaveStatuses(ImmutableList.of(slave1, slave2))
                    .build()),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.distBuildFinished(distBuildStartedEvent, 0),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    timeMillis += 100;
    final String distbuildLine =
        "[-] DISTBUILD...FINISHED 1.6s [100%] (STATUS: FINISHED_SUCCESSFULLY,"
            + " 1 [3.3%] CACHE MISS, 1 [3.3%] CACHE ERRORS, 1 UPLOAD ERRORS, [step 3])";
    validateConsole(
        listener,
        timeMillis,
        ImmutableList.of(parsingLine, distbuildLine, DOWNLOAD_STRING, "[+] BUILDING...1.6s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.finished(buildEventStarted, 0),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String buildingLine = "[-] BUILDING...FINISHED 1.6s";
    timeMillis += 100;
    validateConsole(
        listener,
        timeMillis,
        ImmutableList.of(parsingLine, distbuildLine, FINISHED_DOWNLOAD_STRING, buildingLine));

    timeMillis += 100;
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ConsoleEvent.severe(SEVERE_MESSAGE),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    timeMillis += 50;
    validateConsoleWithLogLines(
        listener,
        timeMillis,
        ImmutableList.of(parsingLine, distbuildLine, FINISHED_DOWNLOAD_STRING, buildingLine),
        ImmutableList.of(SEVERE_MESSAGE));
  }

  @Test
  public void testSimpleTest() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    SourcePathResolver pathResolver =
        new SourcePathResolver(
            new SourcePathRuleFinder(
                new BuildRuleResolver(
                    TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())));

    BuildTarget testTarget = BuildTargetFactory.newInstance("//:test");
    ImmutableSet<BuildTarget> testTargets = ImmutableSet.of(testTarget);
    Iterable<String> testArgs = Iterables.transform(testTargets, Object::toString);
    FakeBuildRule testBuildRule =
        new FakeBuildRule(testTarget, pathResolver, ImmutableSortedSet.of());

    ProjectBuildFileParseEvents.Started parseEventStarted =
        new ProjectBuildFileParseEvents.Started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(parseEventStarted, 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    validateConsole(listener, 0L, ImmutableList.of("[+] PARSING BUCK FILES...0.0s"));

    validateConsole(listener, 100L, ImmutableList.of("[+] PARSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectBuildFileParseEvents.Finished(parseEventStarted),
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(listener, 200L, ImmutableList.of("[-] PARSING BUCK FILES...FINISHED 0.2s"));

    BuildEvent.Started buildEventStarted = BuildEvent.started(testArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted, 200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    ParseEvent.Started parseStarted = ParseEvent.started(testTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(parseStarted, 200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(listener, 300L, ImmutableList.of("[+] PROCESSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ParseEvent.finished(parseStarted, 10, Optional.empty()),
            300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    ActionGraphEvent.Started actionGraphStarted = ActionGraphEvent.started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            actionGraphStarted, 300L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ActionGraphEvent.finished(actionGraphStarted),
            400L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String parsingLine = "[-] PROCESSING BUCK FILES...FINISHED 0.2s";

    validateConsole(
        listener, 540L, ImmutableList.of(parsingLine, DOWNLOAD_STRING, "[+] BUILDING...0.1s"));

    BuildRuleEvent.Started started = BuildRuleEvent.started(testBuildRule, durationTracker);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(started, 600L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
        listener,
        800L,
        ImmutableList.of(
            parsingLine,
            DOWNLOAD_STRING,
            "[+] BUILDING...0.4s",
            " |=> //:test...  0.2s (checking_cache)"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                started,
                BuildRuleKeys.of(new RuleKey("aaaa")),
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.empty(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
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

    validateConsole(
        listener, 1300L, ImmutableList.of(parsingLine, FINISHED_DOWNLOAD_STRING, buildingLine));

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
        listener,
        3000L,
        ImmutableList.of(
            parsingLine, FINISHED_DOWNLOAD_STRING, buildingLine, "[+] TESTING...0.5s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            TestRuleEvent.started(testTarget), 3100L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
        listener,
        3200L,
        ImmutableList.of(
            parsingLine,
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            "[+] TESTING...0.7s",
            " |=> //:test...  0.1s"));

    UUID stepUuid = new UUID(0, 1);
    StepEvent.Started stepEventStarted = StepEvent.started("step_name", "step_desc", stepUuid);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            stepEventStarted, 3300L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
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
            TestSummaryEvent.finished(testUUID, testResultSummary),
            3900L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsole(
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
                            new TestCaseSummary("TestClass", ImmutableList.of(testResultSummary))),
                        ImmutableSet.of(), // contacts
                        ImmutableSet.of()))), // labels
            4100L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String testingLine = "[-] TESTING...FINISHED 1.6s (1 PASS/0 FAIL)";

    validateConsoleWithStdOutAndErr(
        listener,
        4200L,
        ImmutableList.of(parsingLine, FINISHED_DOWNLOAD_STRING, buildingLine, testingLine),
        ImmutableList.of(),
        Optional.of(
            Joiner.on('\n')
                .join(
                    "RESULTS FOR ALL TESTS",
                    "PASS    <100ms  1 Passed   0 Skipped   0 Failed   TestClass",
                    "TESTS PASSED",
                    "")),
        // We don't care about stderr, since the last frame will be flushed there.
        Optional.empty());
  }

  @Test
  public void testSkippedTest() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    SourcePathResolver pathResolver =
        new SourcePathResolver(
            new SourcePathRuleFinder(
                new BuildRuleResolver(
                    TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())));

    BuildTarget testTarget = BuildTargetFactory.newInstance("//:test");
    ImmutableSet<BuildTarget> testTargets = ImmutableSet.of(testTarget);
    Iterable<String> testArgs = Iterables.transform(testTargets, Object::toString);
    FakeBuildRule testBuildRule =
        new FakeBuildRule(testTarget, pathResolver, ImmutableSortedSet.of());

    ProjectBuildFileParseEvents.Started parseEventStarted =
        new ProjectBuildFileParseEvents.Started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(parseEventStarted, 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    validateConsole(listener, 0L, ImmutableList.of("[+] PARSING BUCK FILES...0.0s"));

    validateConsole(listener, 100L, ImmutableList.of("[+] PARSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectBuildFileParseEvents.Finished(parseEventStarted),
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(listener, 200L, ImmutableList.of("[-] PARSING BUCK FILES...FINISHED 0.2s"));

    BuildEvent.Started buildEventStarted = BuildEvent.started(testArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted, 200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    ParseEvent.Started parseStarted = ParseEvent.started(testTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(parseStarted, 200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(listener, 300L, ImmutableList.of("[+] PROCESSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ParseEvent.finished(parseStarted, 10, Optional.empty()),
            300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    ActionGraphEvent.Started actionGraphStarted = ActionGraphEvent.started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            actionGraphStarted, 300L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ActionGraphEvent.finished(actionGraphStarted),
            400L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String parsingLine = "[-] PROCESSING BUCK FILES...FINISHED 0.2s";

    validateConsole(
        listener, 540L, ImmutableList.of(parsingLine, DOWNLOAD_STRING, "[+] BUILDING...0.1s"));

    BuildRuleEvent.Started started = BuildRuleEvent.started(testBuildRule, durationTracker);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(started, 600L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
        listener,
        800L,
        ImmutableList.of(
            parsingLine,
            DOWNLOAD_STRING,
            "[+] BUILDING...0.4s",
            " |=> //:test...  0.2s (checking_cache)"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                started,
                BuildRuleKeys.of(new RuleKey("aaaa")),
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.empty(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
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

    validateConsole(
        listener, 1300L, ImmutableList.of(parsingLine, FINISHED_DOWNLOAD_STRING, buildingLine));

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
        listener,
        3000L,
        ImmutableList.of(
            parsingLine, FINISHED_DOWNLOAD_STRING, buildingLine, "[+] TESTING...0.5s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            TestRuleEvent.started(testTarget), 3100L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
        listener,
        3200L,
        ImmutableList.of(
            parsingLine,
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            "[+] TESTING...0.7s",
            " |=> //:test...  0.1s"));

    UUID stepUuid = new UUID(0, 1);
    StepEvent.Started stepEventStarted = StepEvent.started("step_name", "step_desc", stepUuid);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            stepEventStarted, 3300L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
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
                        ImmutableSet.of(), // contacts
                        ImmutableSet.of()))), // labels
            4100L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String testingLine = "[-] TESTING...FINISHED 1.6s (0 PASS/1 SKIP/0 FAIL)";

    validateConsoleWithStdOutAndErr(
        listener,
        4200L,
        ImmutableList.of(parsingLine, FINISHED_DOWNLOAD_STRING, buildingLine, testingLine),
        ImmutableList.of(),
        Optional.of(
            Joiner.on('\n')
                .join(
                    "RESULTS FOR ALL TESTS",
                    "ASSUME  <100ms  0 Passed   1 Skipped   0 Failed   TestClass",
                    "NO TESTS RAN (assumption violations)",
                    "")),
        // We don't care about stderr, since the last frame will be flushed there.
        Optional.empty());
  }

  @Test
  public void testFailingTest() {
    SourcePathResolver pathResolver =
        new SourcePathResolver(
            new SourcePathRuleFinder(
                new BuildRuleResolver(
                    TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())));
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    TestConsole console = new TestConsole();

    BuildTarget testTarget = BuildTargetFactory.newInstance("//:test");
    ImmutableSet<BuildTarget> testTargets = ImmutableSet.of(testTarget);
    Iterable<String> testArgs = Iterables.transform(testTargets, Object::toString);
    FakeBuildRule testBuildRule =
        new FakeBuildRule(testTarget, pathResolver, ImmutableSortedSet.of());

    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            emptySuperConsoleConfig,
            console,
            fakeClock,
            noisySummaryVerbosity,
            new DefaultExecutionEnvironment(
                ImmutableMap.copyOf(System.getenv()), System.getProperties()),
            Optional.empty(),
            Locale.US,
            logPath,
            timeZone);
    eventBus.register(listener);

    ProjectBuildFileParseEvents.Started parseEventStarted =
        new ProjectBuildFileParseEvents.Started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(parseEventStarted, 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    validateConsole(listener, 0L, ImmutableList.of("[+] PARSING BUCK FILES...0.0s"));

    validateConsole(listener, 100L, ImmutableList.of("[+] PARSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectBuildFileParseEvents.Finished(parseEventStarted),
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(listener, 200L, ImmutableList.of("[-] PARSING BUCK FILES...FINISHED 0.2s"));

    BuildEvent.Started buildEventStarted = BuildEvent.started(testArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted, 200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    ParseEvent.Started parseStarted = ParseEvent.started(testTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(parseStarted, 200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(listener, 300L, ImmutableList.of("[+] PROCESSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ParseEvent.finished(parseStarted, 10, Optional.empty()),
            300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    ActionGraphEvent.Started actionGraphStarted = ActionGraphEvent.started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            actionGraphStarted, 300L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ActionGraphEvent.finished(actionGraphStarted),
            400L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String parsingLine = "[-] PROCESSING BUCK FILES...FINISHED 0.2s";

    validateConsole(
        listener, 540L, ImmutableList.of(parsingLine, DOWNLOAD_STRING, "[+] BUILDING...0.1s"));

    BuildRuleEvent.Started started = BuildRuleEvent.started(testBuildRule, durationTracker);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(started, 600L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
        listener,
        800L,
        ImmutableList.of(
            parsingLine,
            DOWNLOAD_STRING,
            "[+] BUILDING...0.4s",
            " |=> //:test...  0.2s (checking_cache)"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                started,
                BuildRuleKeys.of(new RuleKey("aaaa")),
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.empty(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
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

    validateConsole(
        listener, 1300L, ImmutableList.of(parsingLine, FINISHED_DOWNLOAD_STRING, buildingLine));

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
        listener,
        3000L,
        ImmutableList.of(
            parsingLine, FINISHED_DOWNLOAD_STRING, buildingLine, "[+] TESTING...0.5s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            TestRuleEvent.started(testTarget), 3100L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
        listener,
        3200L,
        ImmutableList.of(
            parsingLine,
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            "[+] TESTING...0.7s",
            " |=> //:test...  0.1s"));

    UUID stepUuid = new UUID(0, 1);
    StepEvent.Started stepEventStarted = StepEvent.started("step_name", "step_desc", stepUuid);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            stepEventStarted, 3300L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
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
            TestSummaryEvent.finished(testUUID, testResultSummary),
            3900L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateConsoleWithLogLines(
        listener,
        4000L,
        ImmutableList.of(
            parsingLine,
            FINISHED_DOWNLOAD_STRING,
            buildingLine,
            "[+] TESTING...1.5s (0 PASS/1 FAIL)",
            " |=> //:test...  0.9s"),
        ImmutableList.of("FAILURE TestClass Foo: Foo.java:47: Assertion failure: 'foo' != 'bar'"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            TestRunEvent.finished(
                ImmutableSet.copyOf(testArgs),
                ImmutableList.of(
                    TestResults.of(
                        testTarget,
                        ImmutableList.of(
                            new TestCaseSummary("TestClass", ImmutableList.of(testResultSummary))),
                        ImmutableSet.of(), // contacts
                        ImmutableSet.of()))), // labels
            4100L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    final String testingLine = "[-] TESTING...FINISHED 1.6s (0 PASS/1 FAIL)";

    validateConsoleWithStdOutAndErr(
        listener,
        4200L,
        ImmutableList.of(parsingLine, FINISHED_DOWNLOAD_STRING, buildingLine, testingLine),
        ImmutableList.of(),
        Optional.of(
            Joiner.on('\n')
                .join(
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
        Optional.empty());
  }

  @Test
  public void testBuildRuleSuspendResumeEvents() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    SourcePathResolver pathResolver =
        new SourcePathResolver(
            new SourcePathRuleFinder(
                new BuildRuleResolver(
                    TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())));

    BuildTarget fakeTarget = BuildTargetFactory.newInstance("//banana:stand");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(fakeTarget);
    Iterable<String> buildArgs = Iterables.transform(buildTargets, Object::toString);
    FakeBuildRule fakeRule = new FakeBuildRule(fakeTarget, pathResolver, ImmutableSortedSet.of());
    String stepShortName = "doing_something";
    String stepDescription = "working hard";
    UUID stepUuid = UUID.randomUUID();

    FakeRuleKeyFactory ruleKeyFactory =
        new FakeRuleKeyFactory(ImmutableMap.of(fakeTarget, new RuleKey("aaaa")));

    // Start the build.
    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(buildEventStarted, 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    // Start and stop parsing.
    String parsingLine = "[-] PROCESSING BUCK FILES...FINISHED 0.0s";
    ParseEvent.Started parseStarted = ParseEvent.started(buildTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(parseStarted, 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ParseEvent.finished(parseStarted, 10, Optional.empty()),
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
    BuildRuleEvent.Started started = BuildRuleEvent.started(fakeRule, durationTracker);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(started, 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    // Post events that run a step for 100ms.
    StepEvent.Started stepEventStarted =
        StepEvent.started(stepShortName, stepDescription, stepUuid);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(stepEventStarted, 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            StepEvent.finished(stepEventStarted, /* exitCode */ 0),
            100L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    // Suspend the rule.
    BuildRuleEvent.Suspended suspended = BuildRuleEvent.suspended(started, ruleKeyFactory);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(suspended, 100L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    // Verify that the rule isn't printed now that it's suspended.
    validateConsole(
        listener,
        200L,
        ImmutableList.of(parsingLine, DOWNLOAD_STRING, "[+] BUILDING...0.2s", " |=> IDLE"));

    // Resume the rule.
    BuildRuleEvent.Resumed resumed =
        BuildRuleEvent.resumed(fakeRule, durationTracker, ruleKeyFactory);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(resumed, 300L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    // Verify that we print "checking local..." now that we've resumed, and that we're accounting
    // for previous running time.
    validateConsole(
        listener,
        300L,
        ImmutableList.of(
            parsingLine,
            DOWNLOAD_STRING,
            "[+] BUILDING...0.3s",
            " |=> //banana:stand...  0.1s (checking_cache)"));

    // Post events that run another step.
    StepEvent.Started step2EventStarted =
        StepEvent.started(stepShortName, stepDescription, stepUuid);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            step2EventStarted, 400L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    // Verify the current console now accounts for the step.
    validateConsole(
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
                resumed,
                BuildRuleKeys.of(new RuleKey("aaaa")),
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.empty(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            600L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    // Verify that the rule isn't printed now that it's finally finished..
    validateConsole(
        listener,
        700L,
        ImmutableList.of(parsingLine, DOWNLOAD_STRING, "[+] BUILDING...0.7s", " |=> IDLE"));
  }

  @Test
  public void debugConsoleEventShouldNotPrintLogLineToConsole() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ConsoleEvent.fine("I'll get you Bluths - Hel-loh"),
            0L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(listener, 0L, ImmutableList.of());
  }

  @Test
  public void testParsingStatus() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    // new daemon instance & action graph cache miss
    eventBus.post(DaemonEvent.newDaemonInstance());
    assertEquals(NEW_DAEMON_INSTANCE_MSG, listener.getParsingStatus());
    eventBus.post(ActionGraphEvent.Cache.miss(/* cacheWasEmpty */ true));
    assertEquals(NEW_DAEMON_INSTANCE_MSG, listener.getParsingStatus());

    // overflow scenario
    String overflowMessage = "and if you go chasing rabbits";
    eventBus.post(WatchmanStatusEvent.overflow(overflowMessage));
    assertEquals(createParsingMessage(EMOJI_SNAIL, overflowMessage), listener.getParsingStatus());

    // file added scenario
    eventBus.post(WatchmanStatusEvent.fileCreation("and you know you're going to fall"));
    assertEquals(
        createParsingMessage(EMOJI_SNAIL, "File added: and you know you're going to fall"),
        listener.getParsingStatus());

    // file removed scenario
    eventBus.post(WatchmanStatusEvent.fileDeletion("Tell 'em a hookah-smoking"));
    assertEquals(
        createParsingMessage(EMOJI_SNAIL, "File removed: Tell 'em a hookah-smoking"),
        listener.getParsingStatus());

    // symlink invalidation scenario
    eventBus.post(ParsingEvent.symlinkInvalidation("caterpillar has given you the call"));
    assertEquals(
        createParsingMessage(EMOJI_WHALE, "Symlink caused cache invalidation"),
        listener.getParsingStatus());

    // environmental change scenario
    eventBus.post(ParsingEvent.environmentalChange("WHITE_RABBIT=1"));
    assertEquals(
        createParsingMessage(EMOJI_SNAIL, "Environment variable changes: WHITE_RABBIT=1"),
        listener.getParsingStatus());

    // action graph cache hit scenario
    eventBus.post(ActionGraphEvent.Cache.hit());
    assertEquals(createParsingMessage(EMOJI_BUNNY, ""), listener.getParsingStatus());
  }

  @Test
  public void testAutoSparseUpdates() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    AutoSparseStateEvents.SparseRefreshStarted sparseRefreshStarted =
        new AutoSparseStateEvents.SparseRefreshStarted();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            sparseRefreshStarted, 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    validateConsole(listener, 0L, ImmutableList.of("[+] REFRESHING SPARSE CHECKOUT...0.0s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new AutoSparseStateEvents.SparseRefreshFinished(
                sparseRefreshStarted, SparseSummary.of()),
            500L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(
        listener,
        500L,
        ImmutableList.of(
            "[-] REFRESHING SPARSE CHECKOUT...FINISHED 0.5s "
                + createParsingMessage(EMOJI_DESERT, "Working copy size unchanged").get()));

    // starting a new refresh adds on to running time
    sparseRefreshStarted = new AutoSparseStateEvents.SparseRefreshStarted();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            sparseRefreshStarted, 1000L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    validateConsole(
        listener,
        1000L,
        ImmutableList.of(
            "[+] REFRESHING SPARSE CHECKOUT...0.5s "
                + createParsingMessage(EMOJI_DESERT, "Working copy size unchanged").get()));

    // ending a new refresh shows the total running time for both events
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new AutoSparseStateEvents.SparseRefreshFinished(
                sparseRefreshStarted, SparseSummary.of(0, 10, 0, 42, 0, 0)),
            1500L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(
        listener,
        1500L,
        ImmutableList.of(
            "[-] REFRESHING SPARSE CHECKOUT...FINISHED 1.0s "
                + createParsingMessage(
                        EMOJI_ROLODEX,
                        "10 new sparse rules imported, 42 files added to the working copy")
                    .get()));
  }

  @Test
  public void testProjectGeneration() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ProjectGenerationEvent.started(), 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(listener, 0L, ImmutableList.of("[+] GENERATING PROJECT...0.0s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectGenerationEvent.Finished(), 0L, TimeUnit.MILLISECONDS, 0L));

    validateConsole(listener, 0L, ImmutableList.of("[-] GENERATING PROJECT...FINISHED 0.0s"));
  }

  @Test
  public void testProjectGenerationWithProgress() throws IOException {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    Path storagePath = getStorageForTest();
    Map<String, Object> storageContents =
        ImmutableSortedMap.<String, Object>naturalOrder()
            .put(
                "project arg1 arg2",
                ImmutableSortedMap.<String, Number>naturalOrder()
                    .put(ProgressEstimator.EXPECTED_NUMBER_OF_GENERATED_PROJECT_FILES, 10)
                    .build())
            .build();
    String contents = new Gson().toJson(storageContents);
    Files.createDirectories(storagePath.getParent());
    Files.write(storagePath, contents.getBytes(StandardCharsets.UTF_8));

    ProgressEstimator e = new ProgressEstimator(storagePath, eventBus);
    listener.setProgressEstimator(e);

    eventBus.post(CommandEvent.started("project", ImmutableList.of("arg1", "arg2"), false, 23L));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ProjectGenerationEvent.started(), 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(listener, 0L, ImmutableList.of("[+] GENERATING PROJECT...0.0s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ProjectGenerationEvent.processed(), 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ProjectGenerationEvent.processed(), 100L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(listener, 100L, ImmutableList.of("[+] GENERATING PROJECT...0.1s [20%]"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectGenerationEvent.Finished(), 200L, TimeUnit.MILLISECONDS, 0L));

    validateConsole(
        listener, 0L, ImmutableList.of("[-] GENERATING PROJECT...FINISHED 0.2s [100%]"));
  }

  @Test
  public void testPostingEventBeforeAnyLines() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    eventBus.post(ConsoleEvent.info("Hello world!"));

    validateConsoleWithLogLines(listener, 0L, ImmutableList.of(), ImmutableList.of("Hello world!"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ProjectGenerationEvent.started(), 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(listener, 0L, ImmutableList.of("[+] GENERATING PROJECT...0.0s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectGenerationEvent.Finished(), 0L, TimeUnit.MILLISECONDS, 0L));

    validateConsole(listener, 0L, ImmutableList.of("[-] GENERATING PROJECT...FINISHED 0.0s"));
  }

  @Test
  public void renderLinesWithLineLimit() throws IOException {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    try (SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus)) {

      FakeMultiStateRenderer fakeRenderer =
          new FakeMultiStateRenderer(ImmutableList.of(2L, 1L, 4L, 8L, 5L));
      ImmutableList.Builder<String> lines;

      ImmutableList<String> fullOutput =
          ImmutableList.of(
              " |=> Status of thread 2",
              " |=> Status of thread 1",
              " |=> Status of thread 4",
              " |=> Status of thread 8",
              " |=> Status of thread 5");

      compareOutput(listener, fakeRenderer, fullOutput, 10000);
      compareOutput(listener, fakeRenderer, fullOutput, 10000);
      compareOutput(listener, fakeRenderer, fullOutput, 6);
      compareOutput(listener, fakeRenderer, fullOutput, 5);

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
          equalTo(ImmutableList.of(" |=> Status of thread 2", " |=> 4 MORE THREADS: t1 t4 t8 t5")));
      assertThat(fakeRenderer.lastSortWasByTime(), is(true));

      lines = ImmutableList.builder();
      listener.renderLines(fakeRenderer, lines, 1, false);
      assertThat(lines.build(), equalTo(ImmutableList.of(" |=> 5 THREADS: t2 t1 t4 t8 t5")));
      assertThat(fakeRenderer.lastSortWasByTime(), is(true));
    }
  }

  private void validateConsole(
      SuperConsoleEventBusListener listener, long timeMs, ImmutableList<String> lines) {
    validateConsoleWithLogLines(listener, timeMs, lines, ImmutableList.of());
  }

  private void validateConsoleWithLogLines(
      SuperConsoleEventBusListener listener,
      long timeMs,
      ImmutableList<String> lines,
      ImmutableList<String> logLines) {
    validateConsoleWithStdOutAndErr(
        listener, timeMs, lines, logLines, Optional.of(""), Optional.of(""));
  }

  private void validateConsoleWithStdOutAndErr(
      SuperConsoleEventBusListener listener,
      long timeMs,
      ImmutableList<String> lines,
      ImmutableList<String> logLines,
      Optional<String> stdout,
      Optional<String> stderr) {

    if (stdout.isPresent()) {
      assertThat(((TestConsole) listener.console).getTextWrittenToStdOut(), equalTo(stdout.get()));
    }
    if (stderr.isPresent()) {
      assertThat(((TestConsole) listener.console).getTextWrittenToStdErr(), equalTo(stderr.get()));
    }
    assertThat(listener.createRenderLinesAtTime(timeMs), equalTo(lines));
    assertThat(listener.createLogRenderLines(), equalTo(logLines));
  }

  @Test
  public void timestampsInLocaleWithDecimalCommaFormatCorrectly() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            new SuperConsoleConfig(FakeBuckConfig.builder().build()),
            new TestConsole(),
            fakeClock,
            silentSummaryVerbosity,
            new DefaultExecutionEnvironment(
                ImmutableMap.copyOf(System.getenv()), System.getProperties()),
            Optional.empty(),
            // Note we use de_DE to ensure we get a decimal comma in the output.
            Locale.GERMAN,
            logPath,
            timeZone);
    eventBus.register(listener);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ProjectGenerationEvent.started(), 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(listener, 0L, ImmutableList.of("[+] GENERATING PROJECT...0,0s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectGenerationEvent.Finished(), 0L, TimeUnit.MILLISECONDS, 0L));

    validateConsole(listener, 0L, ImmutableList.of("[-] GENERATING PROJECT...FINISHED 0,0s"));
  }

  @Test
  public void testBuildTimeDoesNotDisplayNegativeOffset() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

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
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    ActionGraphEvent.Started actionGraphStarted = ActionGraphEvent.started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            actionGraphStarted, 200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(listener, 200L, ImmutableList.of("[+] PROCESSING BUCK FILES...0.1s"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ActionGraphEvent.finished(actionGraphStarted),
            300L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted, 300L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    final String parsingLine = "[-] PROCESSING BUCK FILES...FINISHED 0.2s";

    validateConsole(
        listener, 433L, ImmutableList.of(parsingLine, DOWNLOAD_STRING, "[+] BUILDING...0.1s"));
  }

  private SuperConsoleEventBusListener createSuperConsole(Clock clock, BuckEventBus eventBus) {
    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            emptySuperConsoleConfig,
            new TestConsole(),
            clock,
            silentSummaryVerbosity,
            new DefaultExecutionEnvironment(
                ImmutableMap.copyOf(System.getenv()), System.getProperties()),
            Optional.empty(),
            Locale.US,
            logPath,
            timeZone);
    eventBus.register(listener);
    return listener;
  }

  private Path getStorageForTest() throws IOException {
    return tmp.newFile();
  }

  private void compareOutput(
      SuperConsoleEventBusListener listener,
      FakeMultiStateRenderer fakeRenderer,
      ImmutableList<String> fullOutput,
      int maxLines) {
    ImmutableList.Builder<String> lines;
    lines = ImmutableList.builder();
    listener.renderLines(fakeRenderer, lines, maxLines, false);
    assertThat(lines.build(), equalTo(fullOutput));
    assertThat(fakeRenderer.lastSortWasByTime(), is(false));
  }
}
