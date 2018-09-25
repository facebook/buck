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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.artifact_cache.ArtifactCacheEvent;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.DirArtifactCacheEvent;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEventFetchData;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.type.UploadToCacheResultType;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.build.stats.BuildRuleDurationTracker;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.BuildRuleKeys;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.test.event.TestRunEvent;
import com.facebook.buck.core.test.event.TestSummaryEvent;
import com.facebook.buck.distributed.DistBuildStatus;
import com.facebook.buck.distributed.DistBuildStatusEvent;
import com.facebook.buck.distributed.DistributedExitCode;
import com.facebook.buck.distributed.StampedeLocalBuildStatusEvent;
import com.facebook.buck.distributed.build_client.DistBuildRemoteProgressEvent;
import com.facebook.buck.distributed.build_client.DistBuildSuperConsoleEvent;
import com.facebook.buck.distributed.build_client.StampedeConsoleEvent;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildSlaveInfo;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.CacheRateStats;
import com.facebook.buck.distributed.thrift.CoordinatorBuildProgress;
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.ArtifactCompressionEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.DaemonEvent;
import com.facebook.buck.event.InstallEvent;
import com.facebook.buck.event.ParsingEvent;
import com.facebook.buck.event.ProgressEvent;
import com.facebook.buck.event.ProjectGenerationEvent;
import com.facebook.buck.event.WatchmanStatusEvent;
import com.facebook.buck.json.ProjectBuildFileParseEvents;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.rules.keys.FakeRuleKeyFactory;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResultSummaryVerbosity;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRuleEvent;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.IncrementingFakeClock;
import com.facebook.buck.util.unit.SizeUnit;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.Subscribe;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class SuperConsoleEventBusListenerTest {
  private static final String TARGET_ONE = "TARGET_ONE";
  private static final String TARGET_TWO = "TARGET_TWO";
  private static final String TARGET_THREE = "TARGET_THREE";
  private static final String SEVERE_MESSAGE = "This is a sample severe message.";
  private static final TestResultSummaryVerbosity noisySummaryVerbosity =
      TestResultSummaryVerbosity.of(true, true);

  private static final TestResultSummaryVerbosity silentSummaryVerbosity =
      TestResultSummaryVerbosity.of(false, false);

  @Rule public final TemporaryPaths tmp = new TemporaryPaths();
  @Rule public final Timeout timeout = Timeout.seconds(10);

  private FileSystem vfs;
  private Path logPath;
  private BuildRuleDurationTracker durationTracker;
  private SuperConsoleConfig emptySuperConsoleConfig =
      new SuperConsoleConfig(FakeBuckConfig.builder().build());

  private final TimeZone timeZone = TimeZone.getTimeZone("UTC");

  private String formatCacheStatsLine(boolean running, int artifacts, float size, float ratio) {
    String operationString = running ? "Downloading..." : "Downloaded";
    String sizeString = size == 0 ? "0.00 bytes" : String.format("%.2f Mbytes", size);
    return String.format(
        "%s %d artifacts, %s, %.1f%% cache miss", operationString, artifacts, sizeString, ratio);
  }

  @Before
  public void setUp() {
    vfs = Jimfs.newFileSystem(Configuration.unix());
    logPath = vfs.getPath("log.txt");
    durationTracker = new BuildRuleDurationTracker();
  }

  @Parameters(name = "{2}")
  public static Collection<Object[]> data() {
    return Arrays.asList(
        new Object[][] {
          {false, Optional.empty(), "no_build_id_and_no_build_url"},
          {true, Optional.empty(), "build_id_and_no_build_url"},
          {
            true,
            Optional.of("View details at https://example.com/build/{build_id}"),
            "build_id_and_build_url"
          },
          {
            false,
            Optional.of("View details at https://example.com/build/{build_id}"),
            "no_build_id_and_build_url"
          }
        });
  }

  private final BuildId buildId = new BuildId("1234-5678");

  @Parameterized.Parameter(0)
  public boolean printBuildId;

  @Parameterized.Parameter(1)
  public Optional<String> buildDetailsTemplate;

  @Parameterized.Parameter(2)
  public String _ignoredName;

  @Test
  public void testSimpleBuild() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusForTests.newInstance(fakeClock);
    SuperConsoleEventBusListener listener =
        createSuperConsole(fakeClock, eventBus, printBuildId, buildDetailsTemplate);

    BuildTarget fakeTarget = BuildTargetFactory.newInstance("//banana:stand");
    BuildTarget dirCachedTarget = BuildTargetFactory.newInstance("//chicken:dance");
    BuildTarget remoteCachedTarget = BuildTargetFactory.newInstance("//chicken:noodles");
    RuleKey remoteCachedRuleKey = new RuleKey("deadbeef");
    ImmutableSet<BuildTarget> buildTargets =
        ImmutableSet.of(fakeTarget, dirCachedTarget, remoteCachedTarget);
    Iterable<String> buildArgs = Iterables.transform(buildTargets, Object::toString);
    FakeBuildRule fakeRule = new FakeBuildRule(fakeTarget, ImmutableSortedSet.of());
    FakeBuildRule cachedDirRule = new FakeBuildRule(dirCachedTarget, ImmutableSortedSet.of());

    ProjectBuildFileParseEvents.Started parseEventStarted =
        new ProjectBuildFileParseEvents.Started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(parseEventStarted, 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    validateBuildIdConsole(listener, 0L, ImmutableList.of("Parsing buck files... 0.0 sec"));

    validateBuildIdConsole(listener, 100L, ImmutableList.of("Parsing buck files... 0.1 sec"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectBuildFileParseEvents.Finished(parseEventStarted),
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateBuildIdConsole(
        listener, 200L, ImmutableList.of("Parsing buck files: finished in 0.2 sec"));

    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted, 200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    ParseEvent.Started parseStarted = ParseEvent.started(buildTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(parseStarted, 200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateBuildIdConsole(listener, 300L, ImmutableList.of("Parsing buck files... 0.3 sec"));

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

    String parsingLine = "Parsing buck files: finished in 0.3 sec";
    String actionGraphLine = "Creating action graph: finished in 0.1 sec";

    validateBuildIdConsole(
        listener,
        540L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 0f),
            "Building... 0.1 sec"));

    BuildRuleEvent.Started started = BuildRuleEvent.started(fakeRule, durationTracker);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(started, 600L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateBuildIdConsole(
        listener,
        700L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 0f),
            "Building... 0.3 sec",
            " - //banana:stand... 0.1 sec (preparing)"));

    validateBuildIdConsole(
        listener,
        702L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 0f),
            "Building... 0.3 sec",
            " - //banana:stand... 0.1 sec (preparing)"));

    ArtifactCompressionEvent.Started compressStarted =
        ArtifactCompressionEvent.started(
            ArtifactCompressionEvent.Operation.COMPRESS, ImmutableSet.of());
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(compressStarted, 703L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateBuildIdConsole(
        listener,
        703L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 0f),
            "Building... 0.3 sec",
            " - //banana:stand... 0.1 sec (running artifact_compress[0.0 sec])"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ArtifactCompressionEvent.finished(compressStarted),
            704L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateBuildIdConsole(
        listener,
        705L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 0f),
            "Building... 0.3 sec",
            " - //banana:stand... 0.1 sec (preparing)"));

    DirArtifactCacheEvent.DirArtifactCacheEventFactory dirArtifactCacheEventFactory =
        new DirArtifactCacheEvent.DirArtifactCacheEventFactory();

    ArtifactCacheEvent.Started dirFetchStarted =
        dirArtifactCacheEventFactory.newFetchStartedEvent(ImmutableSet.of());

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(dirFetchStarted, 740L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateBuildIdConsole(
        listener,
        741L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 0f),
            "Building... 0.3 sec",
            " - //banana:stand... 0.1 sec (running dir_artifact_fetch[0.0 sec])"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            dirArtifactCacheEventFactory.newFetchFinishedEvent(
                dirFetchStarted, CacheResult.hit("dir", ArtifactCacheMode.dir)),
            742L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    // Test a remote cache request.
    HttpArtifactCacheEvent.Started remoteFetchStarted =
        HttpArtifactCacheEvent.newFetchStartedEvent(null, remoteCachedRuleKey);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            remoteFetchStarted, 745L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    ArtifactCacheMode remoteCacheMode = ArtifactCacheMode.thrift_over_http;
    long remoteArtifactSizeBytes = 23 * 1024 * 1024;
    CacheResult remoteCacheResult =
        CacheResult.hit(
            remoteCacheMode.name(), remoteCacheMode, ImmutableMap.of(), remoteArtifactSizeBytes);
    HttpArtifactCacheEventFetchData.Builder fetchDataBuilder =
        HttpArtifactCacheEventFetchData.builder()
            .setFetchResult(remoteCacheResult)
            .setArtifactSizeBytes(remoteArtifactSizeBytes);

    HttpArtifactCacheEvent.Finished remoteFetchFinished =
        HttpArtifactCacheEvent.newFinishedEventBuilder(remoteFetchStarted)
            .setFetchDataBuilder(fetchDataBuilder)
            .build();

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            remoteFetchFinished, 780L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateBuildIdConsole(
        listener,
        790L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 1, 23, 0f),
            "Building... 0.3 sec",
            " - //banana:stand... 0.1 sec (preparing)"));

    validateBuildIdConsole(
        listener,
        800L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 1, 23, 0f),
            "Building... 0.4 sec",
            " - //banana:stand... 0.2 sec (preparing)"));

    String stepShortName = "doing_something";
    String stepDescription = "working hard";
    UUID stepUuid = UUID.randomUUID();
    StepEvent.Started stepEventStarted =
        StepEvent.started(stepShortName, stepDescription, stepUuid);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(stepEventStarted, 800L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateBuildIdConsole(
        listener,
        900L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 1, 23f, 0f),
            "Building... 0.5 sec",
            " - //banana:stand... 0.3 sec (running doing_something[0.1 sec])"));

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
                CacheResult.ignored(),
                Optional.empty(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                UploadToCacheResultType.UNCACHEABLE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            1000L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    validateBuildIdConsole(
        listener,
        1000L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 1, 23f, 0f),
            "Building... 0.6 sec",
            " - IDLE"));

    BuildRuleEvent.Started startedCached = BuildRuleEvent.started(cachedDirRule, durationTracker);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(startedCached, 1010L, TimeUnit.MILLISECONDS, /* threadId */ 2L));

    validateBuildIdConsole(
        listener,
        1100L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 1, 23f, 0f),
            "Building... 0.7 sec",
            " - IDLE",
            " - //chicken:dance... 0.0 sec (preparing)"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                startedCached,
                BuildRuleKeys.of(new RuleKey("aaaa")),
                BuildRuleStatus.SUCCESS,
                CacheResult.hit(ArtifactCacheMode.dir.name(), ArtifactCacheMode.dir),
                Optional.empty(),
                Optional.of(BuildRuleSuccessType.FETCHED_FROM_CACHE),
                UploadToCacheResultType.UNCACHEABLE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            1120L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 2L));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.finished(buildEventStarted, ExitCode.SUCCESS),
            1234L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    String buildingLine = "Building: finished in 0.8 sec";
    String totalLine = "  Total time: 1.0 sec";

    validateBuildIdConsole(
        listener,
        1300L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(false, 1, 23f, 0f),
            buildingLine,
            totalLine));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ConsoleEvent.severe(SEVERE_MESSAGE), 1500L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateBuildIdConsoleWithLogLines(
        listener,
        1600L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(false, 1, 23f, 0f),
            buildingLine,
            totalLine),
        ImmutableList.of(SEVERE_MESSAGE));

    InstallEvent.Started installEventStarted = InstallEvent.started(fakeTarget);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            installEventStarted, 2500L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateBuildIdConsole(
        listener,
        3000L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(false, 1, 23f, 0f),
            buildingLine,
            totalLine,
            "Installing... 0.5 sec"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            InstallEvent.finished(installEventStarted, true, Optional.empty(), Optional.empty()),
            4000L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    String installingFinished = "Installing: finished in 1.5 sec";

    validateBuildIdConsole(
        listener,
        5000L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(false, 1, 23f, 0f),
            buildingLine,
            totalLine,
            installingFinished));

    HttpArtifactCacheEvent.Scheduled storeScheduledOne =
        ArtifactCacheTestUtils.postStoreScheduled(eventBus, 0L, TARGET_ONE, 6000L);

    HttpArtifactCacheEvent.Scheduled storeScheduledTwo =
        ArtifactCacheTestUtils.postStoreScheduled(eventBus, 0L, TARGET_TWO, 6010L);

    HttpArtifactCacheEvent.Scheduled storeScheduledThree =
        ArtifactCacheTestUtils.postStoreScheduled(eventBus, 0L, TARGET_THREE, 6020L);

    validateBuildIdConsole(
        listener,
        6021L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(false, 1, 23f, 0f),
            buildingLine,
            totalLine,
            installingFinished,
            "HTTP CACHE UPLOAD... 0.00 bytes (0 COMPLETE/0 FAILED/0 UPLOADING/3 PENDING)"));

    HttpArtifactCacheEvent.Started storeStartedOne =
        ArtifactCacheTestUtils.postStoreStarted(eventBus, 0, 6025L, storeScheduledOne);

    validateBuildIdConsole(
        listener,
        7000,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(false, 1, 23f, 0f),
            buildingLine,
            totalLine,
            installingFinished,
            "HTTP CACHE UPLOAD... 0.00 bytes (0 COMPLETE/0 FAILED/1 UPLOADING/2 PENDING)"));

    long artifactSizeOne = SizeUnit.KILOBYTES.toBytes(1.5);
    ArtifactCacheTestUtils.postStoreFinished(
        eventBus, 0, artifactSizeOne, 7020L, true, storeStartedOne);

    validateBuildIdConsole(
        listener,
        7020,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(false, 1, 23f, 0f),
            buildingLine,
            totalLine,
            installingFinished,
            "HTTP CACHE UPLOAD... 1.50 Kbytes (1 COMPLETE/0 FAILED/0 UPLOADING/2 PENDING)"));

    HttpArtifactCacheEvent.Started storeStartedTwo =
        ArtifactCacheTestUtils.postStoreStarted(eventBus, 0, 7030L, storeScheduledTwo);
    long artifactSizeTwo = SizeUnit.KILOBYTES.toBytes(1.6);
    ArtifactCacheTestUtils.postStoreFinished(
        eventBus, 0, artifactSizeTwo, 7030L, false, storeStartedTwo);

    validateBuildIdConsole(
        listener,
        7040,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(false, 1, 23f, 0f),
            buildingLine,
            totalLine,
            installingFinished,
            "HTTP CACHE UPLOAD... 1.50 Kbytes (1 COMPLETE/1 FAILED/0 UPLOADING/1 PENDING)"));

    HttpArtifactCacheEvent.Started storeStartedThree =
        ArtifactCacheTestUtils.postStoreStarted(eventBus, 0, 7040L, storeScheduledThree);
    long artifactSizeThree = SizeUnit.KILOBYTES.toBytes(0.6);
    ArtifactCacheTestUtils.postStoreFinished(
        eventBus, 0, artifactSizeThree, 7040L, true, storeStartedThree);

    validateBuildIdConsole(
        listener,
        7040,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(false, 1, 23f, 0f),
            buildingLine,
            totalLine,
            installingFinished,
            "HTTP CACHE UPLOAD... 2.10 Kbytes (2 COMPLETE/1 FAILED/0 UPLOADING/0 PENDING)"));

    CommandEvent.Started commandStarted =
        CommandEvent.started("build", ImmutableList.of(), OptionalLong.of(100), 1234);
    eventBus.post(CommandEvent.finished(commandStarted, ExitCode.SUCCESS));
    if (buildDetailsTemplate.isPresent()) {
      validateBuildIdConsole(
          listener,
          7040,
          ImmutableList.of(
              parsingLine,
              actionGraphLine,
              formatCacheStatsLine(false, 1, 23f, 0f),
              buildingLine,
              totalLine,
              installingFinished,
              "HTTP CACHE UPLOAD... 2.10 Kbytes (2 COMPLETE/1 FAILED/0 UPLOADING/0 PENDING)",
              "View details at https://example.com/build/1234-5678"));
    }

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
    BuckEventBus eventBus = BuckEventBusForTests.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    BuildTarget fakeTarget = BuildTargetFactory.newInstance("//banana:stand");
    BuildTarget cachedTarget = BuildTargetFactory.newInstance("//chicken:dance");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(fakeTarget, cachedTarget);
    Iterable<String> buildArgs = Iterables.transform(buildTargets, Object::toString);
    FakeBuildRule fakeRule = new FakeBuildRule(fakeTarget, ImmutableSortedSet.of());
    FakeBuildRule cachedRule = new FakeBuildRule(cachedTarget, ImmutableSortedSet.of());

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

    validateConsole(
        listener,
        300L,
        ImmutableList.of(
            "Parsing buck files: finished in 0.1 sec", "Creating action graph... 0.0 sec"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ActionGraphEvent.finished(actionGraphStarted),
            400L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    String parsingLine = "Parsing buck files: finished in 0.1 sec";
    String actionGraphLine = "Creating action graph: finished in 0.1 sec";
    validateConsole(
        listener,
        540L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 0f),
            "Building... 0.1 sec" + " (0%) 0/10 jobs, 0 updated"));

    BuildRuleEvent.Started started = BuildRuleEvent.started(fakeRule, durationTracker);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(started, 600L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
        listener,
        800L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 0f),
            "Building... 0.4 sec" + " (0%) 0/10 jobs, 0 updated",
            " - //banana:stand... 0.2 sec (preparing)"));

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
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 0f),
            "Building... 0.5 sec" + " (0%) 0/10 jobs, 0 updated",
            " - //banana:stand... 0.3 sec (running doing_something[0.1 sec])"));

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
                UploadToCacheResultType.UNCACHEABLE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
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
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 100f),
            "Building... 0.6 sec (10%) 1/10 jobs, 1 updated",
            " - IDLE"));

    BuildRuleEvent.Started startedCached = BuildRuleEvent.started(cachedRule, durationTracker);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(startedCached, 1010L, TimeUnit.MILLISECONDS, /* threadId */ 2L));

    validateConsole(
        listener,
        1100L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 100f),
            "Building... 0.7 sec (10%) 1/10 jobs, 1 updated",
            " - IDLE",
            " - //chicken:dance... 0.0 sec (preparing)"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                startedCached,
                BuildRuleKeys.of(new RuleKey("aaaa")),
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.empty(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                UploadToCacheResultType.UNCACHEABLE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            1120L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 2L));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.finished(buildEventStarted, ExitCode.SUCCESS),
            1234L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    String buildingLine = "Building: finished in 0.8 sec" + " (100%) 2/10 jobs, 2 updated";
    String totalTime = "  Total time: 1.0 sec";

    validateConsole(
        listener,
        1300L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalTime));
  }

  @Test
  public void testDistBuildWithProgress() throws IOException {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusForTests.newInstance(fakeClock);
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
    validateConsole(listener, timeMillis, ImmutableList.of("Parsing buck files... 0.0 sec"));

    timeMillis += 100;
    validateConsole(listener, timeMillis, ImmutableList.of("Parsing buck files... 0.1 sec"));
    timeMillis += 100;
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectBuildFileParseEvents.Finished(parseEventStarted),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(
        listener, timeMillis, ImmutableList.of("Parsing buck files: finished in 0.2 sec"));

    // trigger a distributed build instead of a local build
    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted, timeMillis, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildSuperConsoleEvent(), timeMillis, TimeUnit.MILLISECONDS, 0L));
    ParseEvent.Started parseStarted = ParseEvent.started(buildTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            parseStarted, timeMillis, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    timeMillis += 100;
    validateConsole(listener, timeMillis, ImmutableList.of("Parsing buck files... 0.3 sec"));

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
    String parsingLine = "Parsing buck files: finished in 0.3 sec";
    String actionGraphLine = "Creating action graph: finished in 0.1 sec";

    validateConsole(
        listener,
        timeMillis,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            "Distributed Build... 0.3 sec (0%) remote status: init; local status: init",
            "Downloading... 0 artifacts, 0.00 bytes, 0.0% cache miss",
            "Local Steps... 0.2 sec"));

    BuildSlaveRunId buildSlaveRunId1 = new BuildSlaveRunId();
    buildSlaveRunId1.setId("slave1");
    BuildSlaveStatus slave1 = new BuildSlaveStatus();
    slave1.setBuildSlaveRunId(buildSlaveRunId1);
    BuildSlaveInfo slaveInfo1 = new BuildSlaveInfo();
    slaveInfo1.setBuildSlaveRunId(buildSlaveRunId1);

    BuildSlaveRunId buildSlaveRunId2 = new BuildSlaveRunId();
    buildSlaveRunId2.setId("slave2");
    BuildSlaveStatus slave2 = new BuildSlaveStatus();
    slave2.setBuildSlaveRunId(buildSlaveRunId2);
    BuildSlaveInfo slaveInfo2 = new BuildSlaveInfo();
    slaveInfo2.setBuildSlaveRunId(buildSlaveRunId2);

    BuildSlaveRunId buildSlaveRunId3 = new BuildSlaveRunId();
    buildSlaveRunId3.setId("slave3");
    BuildSlaveStatus slave3 = new BuildSlaveStatus();
    slave3.setBuildSlaveRunId(buildSlaveRunId3);
    BuildSlaveInfo slaveInfo3 = new BuildSlaveInfo();
    slaveInfo3.setBuildSlaveRunId(buildSlaveRunId3);

    BuildJob job = new BuildJob();
    job.setBuildSlaves(ImmutableList.of(slaveInfo1, slaveInfo2, slaveInfo3));

    timeMillis += 250;
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildStatusEvent(
                job, DistBuildStatus.builder().setStatus(BuildStatus.QUEUED.toString()).build()),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    BuildEvent.RuleCountCalculated ruleCountCalculated =
        BuildEvent.ruleCountCalculated(ImmutableSet.of(), 10);
    eventBus.post(ruleCountCalculated);

    FakeBuildRule fakeRule = new FakeBuildRule(fakeTarget, ImmutableSortedSet.of());
    BuildRuleEvent.Started fakeRuleStarted = BuildRuleEvent.started(fakeRule, durationTracker);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            fakeRuleStarted, timeMillis, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    timeMillis += 100;
    validateConsole(
        listener,
        timeMillis,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            "Distributed Build... 0.7 sec (0%) remote status: queued; local status: init",
            "Downloading... 0 artifacts, 0.00 bytes, 0.0% cache miss",
            "Local Steps... 0.6 sec (0%) 0/10 jobs, 0 updated",
            " - //banana:stand... 0.1 sec (preparing)"));

    timeMillis += 100;
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildStatusEvent(job, DistBuildStatus.builder().build()),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                fakeRuleStarted,
                BuildRuleKeys.of(new RuleKey("aaaa")),
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.empty(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                UploadToCacheResultType.UNCACHEABLE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    timeMillis += 100;
    validateConsole(
        listener,
        timeMillis,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            "Distributed Build... 0.9 sec (0%) local status: init",
            "Downloading... 0 artifacts, 0.00 bytes, 100.0% cache miss",
            "Local Steps... 0.8 sec (10%) 1/10 jobs, 1 updated",
            " - IDLE"));

    slave2.setFilesMaterializedCount(128);

    timeMillis += 100;
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildStatusEvent(
                job,
                DistBuildStatus.builder()
                    .setStatus(BuildStatus.BUILDING.toString())
                    .setSlaveStatuses(ImmutableList.of(slave1, slave2, slave3))
                    .build()),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    FakeBuildRule cachedRule = new FakeBuildRule(cachedTarget, ImmutableSortedSet.of());
    BuildRuleEvent.Started cachedRuleStarted = BuildRuleEvent.started(cachedRule, durationTracker);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            cachedRuleStarted, timeMillis, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    timeMillis += 100;
    validateConsole(
        listener,
        timeMillis,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            "Distributed Build... 1.1 sec (0%) remote status: building; local status: init",
            " - Preparing: creating action graph, materializing source files [128] ...",
            " - Preparing: creating action graph ...",
            " - Preparing: creating action graph ...",
            "Downloading... 0 artifacts, 0.00 bytes, 100.0% cache miss",
            "Local Steps... 1.0 sec (10%) 1/10 jobs, 1 updated",
            " - //chicken:dance... 0.1 sec (preparing)"));

    timeMillis += 100;
    slave1.setTotalRulesCount(10);
    slave1.setRulesFinishedCount(5);
    slave1.setRulesBuildingCount(1);
    CacheRateStats cacheRateStatsForSlave1 = new CacheRateStats();
    slave1.setCacheRateStats(cacheRateStatsForSlave1);
    cacheRateStatsForSlave1.setTotalRulesCount(10);
    cacheRateStatsForSlave1.setUpdatedRulesCount(5);
    cacheRateStatsForSlave1.setCacheHitsCount(4);
    cacheRateStatsForSlave1.setCacheMissesCount(1);

    slave2.setTotalRulesCount(20);
    slave2.setRulesBuildingCount(5);
    slave2.setRulesFinishedCount(5);
    slave2.setRulesFailureCount(1);
    CacheRateStats cacheRateStatsForSlave2 = new CacheRateStats();
    slave2.setCacheRateStats(cacheRateStatsForSlave2);
    cacheRateStatsForSlave2.setTotalRulesCount(20);
    cacheRateStatsForSlave2.setUpdatedRulesCount(5);
    cacheRateStatsForSlave2.setCacheHitsCount(5);
    cacheRateStatsForSlave2.setCacheMissesCount(0);

    slaveInfo3.setStatus(BuildStatus.FAILED);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildStatusEvent(
                job,
                DistBuildStatus.builder()
                    .setStatus(BuildStatus.BUILDING.toString())
                    .setSlaveStatuses(ImmutableList.of(slave1, slave2, slave3))
                    .build()),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildRemoteProgressEvent(
                new CoordinatorBuildProgress()
                    .setTotalRulesCount(100)
                    .setSkippedRulesCount(20)
                    .setBuiltRulesCount(10)),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    eventBus.post(BuildEvent.reset());
    eventBus.post(BuildEvent.ruleCountCalculated(ImmutableSet.of(), 5));

    timeMillis += 100;
    validateConsole(
        listener,
        timeMillis,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            "Distributed Build... 1.3 sec (12%) "
                + "remote status: building, 10/80 jobs, 10.0% cache miss; local status: init",
            " - Building 5 jobs... built 5/20 jobs, 1 jobs failed, 0.0% cache miss",
            " - Building 1 jobs... built 5/10 jobs, 20.0% cache miss",
            formatCacheStatsLine(true, 0, 0f, 100f),
            "Local Steps... 1.2 sec (0%) 0/5 jobs, 1 updated"));

    timeMillis += 100;
    slave1.setRulesBuildingCount(1);
    slave1.setRulesFinishedCount(9);
    cacheRateStatsForSlave1.setUpdatedRulesCount(9);
    cacheRateStatsForSlave1.setCacheHitsCount(8);
    cacheRateStatsForSlave1.setCacheMissesCount(1);

    slave2.setRulesBuildingCount(1);
    slave2.setRulesFinishedCount(19);
    slave2.setHttpArtifactUploadsScheduledCount(3);
    slave2.setHttpArtifactUploadsOngoingCount(1);
    slave2.setHttpArtifactUploadsSuccessCount(1);
    slave2.setHttpArtifactUploadsFailureCount(1);
    cacheRateStatsForSlave2.setUpdatedRulesCount(19);
    cacheRateStatsForSlave2.setCacheHitsCount(18);
    cacheRateStatsForSlave2.setCacheMissesCount(0);
    cacheRateStatsForSlave2.setCacheErrorsCount(1);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildStatusEvent(
                job,
                DistBuildStatus.builder()
                    .setStatus("custom")
                    .setSlaveStatuses(ImmutableList.of(slave1, slave2, slave3))
                    .build()),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new StampedeLocalBuildStatusEvent("building"),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildRemoteProgressEvent(
                new CoordinatorBuildProgress()
                    .setTotalRulesCount(100)
                    .setSkippedRulesCount(20)
                    .setBuiltRulesCount(50)),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                cachedRuleStarted,
                BuildRuleKeys.of(new RuleKey("bbbb")),
                BuildRuleStatus.SUCCESS,
                CacheResult.hit(
                    ArtifactCacheMode.thrift_over_http.name(), ArtifactCacheMode.thrift_over_http),
                Optional.empty(),
                Optional.of(BuildRuleSuccessType.FETCHED_FROM_CACHE),
                UploadToCacheResultType.UNCACHEABLE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    timeMillis += 100;
    validateConsole(
        listener,
        timeMillis,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            "Distributed Build... 1.5 sec (62%) remote status: custom, 50/80 jobs,"
                + " 3.6% cache miss, 1 [3.6%] cache errors, 1 upload errors"
                + "; local status: building",
            " - Building 1 jobs... built 19/20 jobs, 1 jobs failed, 0.0% cache miss, "
                + "1 [5.3%] cache errors, 1/3 uploaded, 1 upload errors",
            " - Building 1 jobs... built 9/10 jobs, 11.1% cache miss",
            formatCacheStatsLine(true, 0, 0f, 100f),
            "Local Steps... 1.4 sec (20%) 1/5 jobs, 1 updated",
            " - IDLE"));

    slave2.setRulesBuildingCount(0);
    slave2.setRulesFinishedCount(20);
    cacheRateStatsForSlave2.setUpdatedRulesCount(20);
    cacheRateStatsForSlave2.setCacheHitsCount(19);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildStatusEvent(
                job,
                DistBuildStatus.builder()
                    .setStatus("custom")
                    .setSlaveStatuses(ImmutableList.of(slave1, slave2, slave3))
                    .build()),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(
        listener,
        timeMillis,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            "Distributed Build... 1.5 sec (62%) remote status: custom, 50/80 jobs,"
                + " 3.4% cache miss, 1 [3.4%] cache errors, 1 upload errors"
                + "; local status: building",
            " - Building 1 jobs... built 9/10 jobs, 11.1% cache miss",
            formatCacheStatsLine(true, 0, 0f, 100f),
            "Local Steps... 1.4 sec (20%) 1/5 jobs, 1 updated",
            " - IDLE"));

    timeMillis += 100;
    slave1.setRulesBuildingCount(0);
    slave1.setRulesFinishedCount(10);
    cacheRateStatsForSlave1.setUpdatedRulesCount(10);
    cacheRateStatsForSlave1.setCacheHitsCount(9);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildStatusEvent(
                job,
                DistBuildStatus.builder()
                    .setStatus(BuildStatus.FINISHED_SUCCESSFULLY.toString())
                    .setSlaveStatuses(ImmutableList.of(slave1, slave2, slave3))
                    .build()),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildRemoteProgressEvent(
                new CoordinatorBuildProgress()
                    .setTotalRulesCount(100)
                    .setSkippedRulesCount(20)
                    .setBuiltRulesCount(80)),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.distBuildFinished(
                distBuildStartedEvent, DistributedExitCode.SUCCESSFUL.getCode()),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new StampedeLocalBuildStatusEvent("downloading", "Sync Build"),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    timeMillis += 100;
    validateConsole(
        listener,
        timeMillis,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            "Distributed Build: finished in 1.6 sec (100%) remote status: finished_successfully, "
                + "80/80 jobs, 3.3% cache miss, 1 [3.3%] cache errors, 1 upload errors"
                + "; local status: downloading",
            formatCacheStatsLine(true, 0, 0f, 100f),
            "Sync Build... 1.6 sec (20%) 1/5 jobs, 1 updated",
            " - IDLE"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.finished(buildEventStarted, ExitCode.SUCCESS),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    String distbuildLine =
        "Distributed Build: finished in 1.6 sec (100%) remote status: finished_successfully, "
            + "80/80 jobs, 3.3% cache miss, 1 [3.3%] cache errors, 1 upload errors"
            + "; local status: downloading";
    String buildingLine = "Sync Build: finished in 1.6 sec (100%) 1/5 jobs, 1 updated";
    String totalLine = "  Total time: 1.8 sec. Build successful.";
    timeMillis += 100;
    validateConsole(
        listener,
        timeMillis,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            distbuildLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine));

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
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            distbuildLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine),
        ImmutableList.of(SEVERE_MESSAGE));
  }

  @Test
  public void testDistBuildConsoleCanBeDisabled() throws IOException {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusForTests.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    BuildTarget fakeTarget = BuildTargetFactory.newInstance("//banana:stand");
    BuildTarget cachedTarget = BuildTargetFactory.newInstance("//chicken:dance");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(fakeTarget, cachedTarget);
    Iterable<String> buildArgs = Iterables.transform(buildTargets, Object::toString);

    ProgressEstimator e = new ProgressEstimator(getStorageForTest(), eventBus);
    listener.setProgressEstimator(e);
    eventBus.register(listener);

    long timeMillis = 0;

    // Parse events.
    ProjectBuildFileParseEvents.Started parseEventStarted =
        new ProjectBuildFileParseEvents.Started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            parseEventStarted, timeMillis, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    timeMillis += 200;
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectBuildFileParseEvents.Finished(parseEventStarted),
            timeMillis,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    // Action graph events.
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
    String parsingLine = "Parsing buck files: finished in 0.2 sec";
    String actionGraphLine = "Creating action graph: finished in 0.1 sec";
    validateConsole(listener, timeMillis, ImmutableList.of(parsingLine, actionGraphLine));

    // Start build, and distbuild -- but don't enable Stampede SuperConsole yet.
    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted, timeMillis, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    timeMillis += 100;
    BuildEvent.DistBuildStarted distBuildStartedEvent = BuildEvent.distBuildStarted();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            distBuildStartedEvent, timeMillis, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new StampedeConsoleEvent(
                ConsoleEvent.warning("Message before activating stampede console.")),
            timeMillis,
            TimeUnit.MILLISECONDS, /* threadId */
            0L));
    timeMillis += 100;
    validateConsoleWithLogLines(
        listener,
        timeMillis,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 0f),
            "Building... 0.2 sec"),
        ImmutableList.of());

    // Now enable the stampede superconsole.
    timeMillis += 100;
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildSuperConsoleEvent(), timeMillis, TimeUnit.MILLISECONDS, 0L));
    timeMillis += 100;
    String consoleMessage = "Message after activating stampede console.";
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new StampedeConsoleEvent(ConsoleEvent.warning(consoleMessage)),
            timeMillis,
            TimeUnit.MILLISECONDS, /* threadId */
            0L));
    timeMillis += 100;
    validateConsoleWithLogLines(
        listener,
        timeMillis,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            "Distributed Build... 0.4 sec (0%) remote status: init; local status: init",
            formatCacheStatsLine(true, 0, 0f, 0f),
            "Local Steps... 0.5 sec"),
        ImmutableList.of(consoleMessage));

    String stickyMessage = "Hello world from Stampede.";
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new DistBuildSuperConsoleEvent(Optional.of(stickyMessage)),
            timeMillis,
            TimeUnit.MILLISECONDS,
            0L));
    validateConsole(
        listener,
        timeMillis,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            stickyMessage,
            "Distributed Build... 0.4 sec (0%) remote status: init; local status: init",
            formatCacheStatsLine(true, 0, 0f, 0f),
            "Local Steps... 0.5 sec"));
  }

  @Test
  public void testWatchman() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusForTests.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(WatchmanStatusEvent.started(), 0L, TimeUnit.MILLISECONDS, 0L));
    validateConsole(listener, 0L, ImmutableList.of());

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(WatchmanStatusEvent.finished(), 1000L, TimeUnit.MILLISECONDS, 0L));
    validateConsole(
        listener, 1000L, ImmutableList.of("Processing filesystem changes: finished in 1.0 sec"));
  }

  @Test
  public void testQuickWatchman() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusForTests.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(WatchmanStatusEvent.started(), 0L, TimeUnit.MILLISECONDS, 0L));
    validateConsole(listener, 0L, ImmutableList.of());

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(WatchmanStatusEvent.finished(), 500L, TimeUnit.MILLISECONDS, 0L));
    validateConsole(listener, 500L, ImmutableList.of());
  }

  @Test
  public void testSimpleTest() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusForTests.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    BuildTarget testTarget = BuildTargetFactory.newInstance("//:test");
    ImmutableSet<BuildTarget> testTargets = ImmutableSet.of(testTarget);
    Iterable<String> testArgs = Iterables.transform(testTargets, Object::toString);
    FakeBuildRule testBuildRule = new FakeBuildRule(testTarget, ImmutableSortedSet.of());

    ProjectBuildFileParseEvents.Started parseEventStarted =
        new ProjectBuildFileParseEvents.Started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(parseEventStarted, 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    validateConsole(listener, 0L, ImmutableList.of("Parsing buck files... 0.0 sec"));

    validateConsole(listener, 100L, ImmutableList.of("Parsing buck files... 0.1 sec"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectBuildFileParseEvents.Finished(parseEventStarted),
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(listener, 200L, ImmutableList.of("Parsing buck files: finished in 0.2 sec"));

    BuildEvent.Started buildEventStarted = BuildEvent.started(testArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted, 200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    ParseEvent.Started parseStarted = ParseEvent.started(testTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(parseStarted, 200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(listener, 300L, ImmutableList.of("Parsing buck files... 0.3 sec"));

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

    String parsingLine = "Parsing buck files: finished in 0.3 sec";
    String actionGraphLine = "Creating action graph: finished in 0.1 sec";

    validateConsole(
        listener,
        540L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 0f),
            "Building... 0.1 sec"));

    BuildRuleEvent.Started started = BuildRuleEvent.started(testBuildRule, durationTracker);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(started, 600L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
        listener,
        800L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 0f),
            "Building... 0.4 sec",
            " - //:test... 0.2 sec (preparing)"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                started,
                BuildRuleKeys.of(new RuleKey("aaaa")),
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.empty(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                UploadToCacheResultType.UNCACHEABLE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            1000L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.finished(buildEventStarted, ExitCode.SUCCESS),
            1234L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    String buildingLine = "Building: finished in 0.8 sec";
    String totalLine = "  Total time: 1.0 sec";

    validateConsole(
        listener,
        1300L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine));

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
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine,
            "Testing... 0.5 sec"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            TestRuleEvent.started(testTarget), 3100L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
        listener,
        3200L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine,
            "Testing... 0.7 sec",
            " - //:test... 0.1 sec"));

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
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine,
            "Testing... 0.9 sec",
            " - //:test... 0.3 sec (running step_name[0.1 sec])"));

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
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine,
            "Testing... 1.1 sec",
            " - //:test... 0.5 sec"));

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
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine,
            "Testing... 1.3 sec",
            " - //:test... 0.7 sec (running Foo[0.1 sec])"));

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
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine,
            "Testing... 1.5 sec (1 PASS/0 FAIL)",
            " - //:test... 0.9 sec"));

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

    String testingLine = "Testing: finished in 1.6 sec (1 PASS/0 FAIL)";

    validateConsoleWithStdOutAndErr(
        listener,
        4200L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine,
            testingLine),
        ImmutableList.of(),
        Optional.of(
            Joiner.on(System.lineSeparator())
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
    BuckEventBus eventBus = BuckEventBusForTests.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    BuildTarget testTarget = BuildTargetFactory.newInstance("//:test");
    ImmutableSet<BuildTarget> testTargets = ImmutableSet.of(testTarget);
    Iterable<String> testArgs = Iterables.transform(testTargets, Object::toString);
    FakeBuildRule testBuildRule = new FakeBuildRule(testTarget, ImmutableSortedSet.of());

    ProjectBuildFileParseEvents.Started parseEventStarted =
        new ProjectBuildFileParseEvents.Started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(parseEventStarted, 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    validateConsole(listener, 0L, ImmutableList.of("Parsing buck files... 0.0 sec"));

    validateConsole(listener, 100L, ImmutableList.of("Parsing buck files... 0.1 sec"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectBuildFileParseEvents.Finished(parseEventStarted),
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(listener, 200L, ImmutableList.of("Parsing buck files: finished in 0.2 sec"));

    BuildEvent.Started buildEventStarted = BuildEvent.started(testArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted, 200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    ParseEvent.Started parseStarted = ParseEvent.started(testTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(parseStarted, 200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(listener, 300L, ImmutableList.of("Parsing buck files... 0.3 sec"));

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

    String parsingLine = "Parsing buck files: finished in 0.3 sec";
    String actionGraphLine = "Creating action graph: finished in 0.1 sec";

    validateConsole(
        listener,
        540L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 0f),
            "Building... 0.1 sec"));

    BuildRuleEvent.Started started = BuildRuleEvent.started(testBuildRule, durationTracker);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(started, 600L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
        listener,
        800L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 0f),
            "Building... 0.4 sec",
            " - //:test... 0.2 sec (preparing)"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                started,
                BuildRuleKeys.of(new RuleKey("aaaa")),
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.empty(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                UploadToCacheResultType.UNCACHEABLE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            1000L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.finished(buildEventStarted, ExitCode.SUCCESS),
            1234L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    String buildingLine = "Building: finished in 0.8 sec";
    String totalLine = "  Total time: 1.0 sec";

    validateConsole(
        listener,
        1300L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine));

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
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine,
            "Testing... 0.5 sec"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            TestRuleEvent.started(testTarget), 3100L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
        listener,
        3200L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine,
            "Testing... 0.7 sec",
            " - //:test... 0.1 sec"));

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
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine,
            "Testing... 0.9 sec",
            " - //:test... 0.3 sec (running step_name[0.1 sec])"));

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
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine,
            "Testing... 1.1 sec",
            " - //:test... 0.5 sec"));

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
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine,
            "Testing... 1.3 sec",
            " - //:test... 0.7 sec (running Foo[0.1 sec])"));

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
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine,
            "Testing... 1.5 sec (0 PASS/1 SKIP/0 FAIL)",
            " - //:test... 0.9 sec"));

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

    String testingLine = "Testing: finished in 1.6 sec (0 PASS/1 SKIP/0 FAIL)";

    validateConsoleWithStdOutAndErr(
        listener,
        4200L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine,
            testingLine),
        ImmutableList.of(),
        Optional.of(
            Joiner.on(System.lineSeparator())
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
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusForTests.newInstance(fakeClock);
    TestConsole console = new TestConsole();

    BuildTarget testTarget = BuildTargetFactory.newInstance("//:test");
    ImmutableSet<BuildTarget> testTargets = ImmutableSet.of(testTarget);
    Iterable<String> testArgs = Iterables.transform(testTargets, Object::toString);
    FakeBuildRule testBuildRule = new FakeBuildRule(testTarget, ImmutableSortedSet.of());

    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            emptySuperConsoleConfig,
            console,
            fakeClock,
            noisySummaryVerbosity,
            new DefaultExecutionEnvironment(
                ImmutableMap.copyOf(System.getenv()), System.getProperties()),
            Locale.US,
            logPath,
            timeZone,
            0L,
            0L,
            1000L,
            false,
            buildId,
            false,
            Optional.empty(),
            ImmutableList.of());
    eventBus.register(listener);

    ProjectBuildFileParseEvents.Started parseEventStarted =
        new ProjectBuildFileParseEvents.Started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(parseEventStarted, 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    validateConsole(listener, 0L, ImmutableList.of("Parsing buck files... 0.0 sec"));

    validateConsole(listener, 100L, ImmutableList.of("Parsing buck files... 0.1 sec"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectBuildFileParseEvents.Finished(parseEventStarted),
            200L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));
    validateConsole(listener, 200L, ImmutableList.of("Parsing buck files: finished in 0.2 sec"));

    BuildEvent.Started buildEventStarted = BuildEvent.started(testArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted, 200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    ParseEvent.Started parseStarted = ParseEvent.started(testTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(parseStarted, 200L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(listener, 300L, ImmutableList.of("Parsing buck files... 0.3 sec"));

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

    String parsingLine = "Parsing buck files: finished in 0.3 sec";
    String actionGraphLine = "Creating action graph: finished in 0.1 sec";

    validateConsole(
        listener,
        540L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 0f),
            "Building... 0.1 sec"));

    BuildRuleEvent.Started started = BuildRuleEvent.started(testBuildRule, durationTracker);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(started, 600L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
        listener,
        800L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 0f),
            "Building... 0.4 sec",
            " - //:test... 0.2 sec (preparing)"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildRuleEvent.finished(
                started,
                BuildRuleKeys.of(new RuleKey("aaaa")),
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.empty(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                UploadToCacheResultType.UNCACHEABLE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            1000L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.finished(buildEventStarted, ExitCode.SUCCESS),
            1234L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    String buildingLine = "Building: finished in 0.8 sec";
    String totalLine = "  Total time: 1.0 sec";

    validateConsole(
        listener,
        1300L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine));

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
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine,
            "Testing... 0.5 sec"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            TestRuleEvent.started(testTarget), 3100L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
        listener,
        3200L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine,
            "Testing... 0.7 sec",
            " - //:test... 0.1 sec"));

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
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine,
            "Testing... 0.9 sec",
            " - //:test... 0.3 sec (running step_name[0.1 sec])"));

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
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine,
            "Testing... 1.1 sec",
            " - //:test... 0.5 sec"));

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
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine,
            "Testing... 1.3 sec",
            " - //:test... 0.7 sec (running Foo[0.1 sec])"));

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
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine,
            "Testing... 1.5 sec (0 PASS/1 FAIL)",
            " - //:test... 0.9 sec"),
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

    String testingLine = "Testing: finished in 1.6 sec (0 PASS/1 FAIL)";

    validateConsoleWithStdOutAndErr(
        listener,
        4200L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(false, 0, 0f, 100f),
            buildingLine,
            totalLine,
            testingLine),
        ImmutableList.of(),
        Optional.of(
            Joiner.on(System.lineSeparator())
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
    BuckEventBus eventBus = BuckEventBusForTests.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    BuildTarget fakeTarget = BuildTargetFactory.newInstance("//banana:stand");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(fakeTarget);
    Iterable<String> buildArgs = Iterables.transform(buildTargets, Object::toString);
    FakeBuildRule fakeRule = new FakeBuildRule(fakeTarget, ImmutableSortedSet.of());
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
    String parsingLine = "Parsing buck files: finished in 0.0 sec";
    String actionGraphLine = "Creating action graph: finished in 0.0 sec";
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
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 0f),
            "Building... 0.2 sec",
            " - IDLE"));

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
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 0f),
            "Building... 0.3 sec",
            " - //banana:stand... 0.1 sec (preparing)"));

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
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 0f),
            "Building... 0.5 sec",
            " - //banana:stand... 0.3 sec (running doing_something[0.1 sec])"));

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
                UploadToCacheResultType.UNCACHEABLE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
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
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 100f),
            "Building... 0.7 sec",
            " - IDLE"));
  }

  @Test
  public void debugConsoleEventShouldNotPrintLogLineToConsole() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusForTests.newInstance(fakeClock);
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
    BuckEventBus eventBus = BuckEventBusForTests.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    // new daemon instance & action graph cache miss
    eventBus.post(DaemonEvent.newDaemonInstance());
    assertEquals("daemonNewInstance", listener.getParsingStatus().get());
    eventBus.post(ActionGraphEvent.Cache.miss(/* cacheWasEmpty */ true));
    assertEquals("daemonNewInstance", listener.getParsingStatus().get());

    // overflow scenario
    String overflowMessage = "and if you go chasing rabbits";
    eventBus.post(WatchmanStatusEvent.overflow(overflowMessage));
    assertEquals("watchmanOverflow: " + overflowMessage, listener.getParsingStatus().get());

    // file added scenario
    eventBus.post(WatchmanStatusEvent.fileCreation("and you know you're going to fall"));
    assertEquals("watchmanFileCreation", listener.getParsingStatus().get());

    // file removed scenario
    eventBus.post(WatchmanStatusEvent.fileDeletion("Tell 'em a hookah-smoking"));
    assertEquals("watchmanFileDeletion", listener.getParsingStatus().get());

    // symlink invalidation scenario
    eventBus.post(ParsingEvent.symlinkInvalidation("caterpillar has given you the call"));
    assertEquals("symlinkInvalidation", listener.getParsingStatus().get());

    // environmental change scenario
    eventBus.post(ParsingEvent.environmentalChange("WHITE_RABBIT=1"));
    assertEquals("envVariableChange", listener.getParsingStatus().get());

    // action graph cache hit scenario
    eventBus.post(ActionGraphEvent.Cache.hit());
    assertEquals("actionGraphCacheHit", listener.getParsingStatus().get());
  }

  @Test
  public void testProjectGeneration() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusForTests.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ProjectGenerationEvent.started(), 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(listener, 0L, ImmutableList.of("Generating project... 0.0 sec"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectGenerationEvent.Finished(), 0L, TimeUnit.MILLISECONDS, 0L));

    validateConsole(listener, 0L, ImmutableList.of("Generating project: finished in 0.0 sec"));
  }

  @Test
  public void testProjectGenerationWithProgress() throws IOException {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusForTests.newInstance(fakeClock);
    ProgressEstimatorSynchronization progressEstimatorSynchronization =
        new ProgressEstimatorSynchronization(eventBus);

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
    String contents = ObjectMappers.WRITER.writeValueAsString(storageContents);
    Files.createDirectories(storagePath.getParent());
    Files.write(storagePath, contents.getBytes(StandardCharsets.UTF_8));

    ProgressEstimator e = new ProgressEstimator(storagePath, eventBus);
    listener.setProgressEstimator(e);

    eventBus.post(
        CommandEvent.started(
            "project", ImmutableList.of("arg1", "arg2"), OptionalLong.empty(), 23L));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ProjectGenerationEvent.started(), 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(listener, 0L, ImmutableList.of("Generating project... 0.0 sec"));

    progressEstimatorSynchronization.expectCalculation();
    progressEstimatorSynchronization.expectCalculation();

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ProjectGenerationEvent.processed(), 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ProjectGenerationEvent.processed(), 100L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    progressEstimatorSynchronization.awaitCalculation();

    validateConsole(listener, 100L, ImmutableList.of("Generating project... 0.1 sec (20%)"));

    progressEstimatorSynchronization.expectCalculation();

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectGenerationEvent.Finished(), 200L, TimeUnit.MILLISECONDS, 0L));

    progressEstimatorSynchronization.awaitCalculation();

    validateConsole(
        listener, 0L, ImmutableList.of("Generating project: finished in 0.2 sec (100%)"));
  }

  @Test
  public void testProjectGenerationAndBuildWithProgress() throws IOException {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusForTests.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    ProgressEstimatorSynchronization progressEstimatorSynchronization =
        new ProgressEstimatorSynchronization(eventBus);

    Path storagePath = getStorageForTest();
    Map<String, Object> storageContents =
        ImmutableSortedMap.<String, Object>naturalOrder()
            .put(
                "project arg1 arg2",
                ImmutableSortedMap.<String, Number>naturalOrder()
                    .put(ProgressEstimator.EXPECTED_NUMBER_OF_GENERATED_PROJECT_FILES, 10)
                    .build())
            .build();
    String contents = ObjectMappers.WRITER.writeValueAsString(storageContents);
    Files.createDirectories(storagePath.getParent());
    Files.write(storagePath, contents.getBytes(StandardCharsets.UTF_8));

    ProgressEstimator e = new ProgressEstimator(storagePath, eventBus);
    listener.setProgressEstimator(e);

    eventBus.post(
        CommandEvent.started(
            "project", ImmutableList.of("arg1", "arg2"), OptionalLong.empty(), 23L));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ProjectGenerationEvent.started(), 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(listener, 0L, ImmutableList.of("Generating project... 0.0 sec"));

    progressEstimatorSynchronization.expectCalculation();
    progressEstimatorSynchronization.expectCalculation();

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ProjectGenerationEvent.processed(), 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ProjectGenerationEvent.processed(), 100L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    progressEstimatorSynchronization.awaitCalculation();

    validateConsole(listener, 100L, ImmutableList.of("Generating project... 0.1 sec (20%)"));

    BuildTarget fakeTarget = BuildTargetFactory.newInstance("//banana:stand");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(fakeTarget);

    // no need to validate the output for parsing and action graph, since they're tested elsewhere
    ParseEvent.Started parseStarted = ParseEvent.started(buildTargets);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(parseStarted, 400L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ParseEvent.finished(parseStarted, 10, Optional.empty()),
            600L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    ActionGraphEvent.Started actionGraphStarted = ActionGraphEvent.started();
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            actionGraphStarted, 600L, TimeUnit.MILLISECONDS, /* threadId */ 0L));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ActionGraphEvent.finished(actionGraphStarted),
            700L,
            TimeUnit.MILLISECONDS,
            /* threadId */ 0L));

    Iterable<String> buildArgs = Iterables.transform(buildTargets, Object::toString);
    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            buildEventStarted, 800L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(
        listener,
        943L,
        ImmutableList.of(
            "Parsing buck files: finished in 0.2 sec",
            "Creating action graph: finished in 0.1 sec",
            "Generating project... 0.9 sec (20%)",
            formatCacheStatsLine(true, 0, 0, 0),
            "Building... 0.1 sec"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.finished(buildEventStarted, ExitCode.SUCCESS),
            1000L,
            TimeUnit.MILLISECONDS, /* threadId */
            0L));

    validateConsole(
        listener,
        1000L,
        ImmutableList.of(
            "Parsing buck files: finished in 0.2 sec",
            "Creating action graph: finished in 0.1 sec",
            "Generating project... 1.0 sec (20%)",
            formatCacheStatsLine(false, 0, 0, 0),
            "Building: finished in 0.2 sec"));

    progressEstimatorSynchronization.expectCalculation();

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectGenerationEvent.Finished(), 1200L, TimeUnit.MILLISECONDS, 0L));

    progressEstimatorSynchronization.awaitCalculation();

    validateConsole(
        listener,
        0L,
        ImmutableList.of(
            "Parsing buck files: finished in 0.2 sec",
            "Creating action graph: finished in 0.1 sec",
            "Generating project: finished in 1.2 sec (100%)",
            formatCacheStatsLine(false, 0, 0, 0),
            "Building: finished in 0.2 sec",
            "  Total time: 1.2 sec"));
  }

  @Test
  public void testPostingEventBeforeAnyLines() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusForTests.newInstance(fakeClock);
    SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus);

    eventBus.post(ConsoleEvent.info("Hello world!"));

    validateConsoleWithLogLines(listener, 0L, ImmutableList.of(), ImmutableList.of("Hello world!"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ProjectGenerationEvent.started(), 0L, TimeUnit.MILLISECONDS, /* threadId */ 0L));

    validateConsole(listener, 0L, ImmutableList.of("Generating project... 0.0 sec"));

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            new ProjectGenerationEvent.Finished(), 0L, TimeUnit.MILLISECONDS, 0L));

    validateConsole(listener, 0L, ImmutableList.of("Generating project: finished in 0.0 sec"));
  }

  @Test
  public void renderLinesWithLineLimit() throws IOException {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusForTests.newInstance(fakeClock);
    try (SuperConsoleEventBusListener listener = createSuperConsole(fakeClock, eventBus)) {

      FakeMultiStateRenderer fakeRenderer =
          new FakeMultiStateRenderer(ImmutableList.of(2L, 1L, 4L, 8L, 5L));
      ImmutableList.Builder<String> lines;

      ImmutableList<String> fullOutput =
          ImmutableList.of(
              " - Status of thread 2",
              " - Status of thread 1",
              " - Status of thread 4",
              " - Status of thread 8",
              " - Status of thread 5");

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
                  " - Status of thread 2",
                  " - Status of thread 1",
                  " - Status of thread 4",
                  " - 2 MORE THREADS: t8 t5")));
      assertThat(fakeRenderer.lastSortWasByTime(), is(true));

      lines = ImmutableList.builder();
      listener.renderLines(fakeRenderer, lines, 2, false);
      assertThat(
          lines.build(),
          equalTo(ImmutableList.of(" - Status of thread 2", " - 4 MORE THREADS: t1 t4 t8 t5")));
      assertThat(fakeRenderer.lastSortWasByTime(), is(true));

      lines = ImmutableList.builder();
      listener.renderLines(fakeRenderer, lines, 1, false);
      assertThat(lines.build(), equalTo(ImmutableList.of(" - 5 THREADS: t2 t1 t4 t8 t5")));
      assertThat(fakeRenderer.lastSortWasByTime(), is(true));
    }
  }

  private void validateBuildIdConsole(
      SuperConsoleEventBusListener listener, long timeMs, ImmutableList<String> lines) {
    validateBuildIdConsoleWithLogLines(listener, timeMs, lines, ImmutableList.of());
  }

  private void validateBuildIdConsoleWithLogLines(
      SuperConsoleEventBusListener listener,
      long timeMs,
      ImmutableList<String> lines,
      ImmutableList<String> logLines) {

    Builder<String> builder =
        ImmutableList.builderWithExpectedSize(lines.size() + (printBuildId ? 1 : 0));
    if (printBuildId) {
      builder.add("Build UUID: 1234-5678");
    }
    builder.addAll(lines);

    validateConsoleWithStdOutAndErr(
        listener, timeMs, builder.build(), logLines, Optional.of(""), Optional.of(""));
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
  public void testBuildTimeDoesNotDisplayNegativeOffset() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusForTests.newInstance(fakeClock);
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

    validateConsole(
        listener,
        200L,
        ImmutableList.of(
            "Parsing buck files: finished in 0.1 sec", "Creating action graph... 0.0 sec"));

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

    String parsingLine = "Parsing buck files: finished in 0.1 sec";
    String actionGraphLine = "Creating action graph: finished in 0.1 sec";

    validateConsole(
        listener,
        433L,
        ImmutableList.of(
            parsingLine,
            actionGraphLine,
            formatCacheStatsLine(true, 0, 0f, 0f),
            "Building... 0.1 sec"));
  }

  private SuperConsoleEventBusListener createSuperConsole(Clock clock, BuckEventBus eventBus) {
    return createSuperConsole(clock, eventBus, false, Optional.empty());
  }

  private SuperConsoleEventBusListener createSuperConsole(
      Clock clock,
      BuckEventBus eventBus,
      boolean printBuildId,
      Optional<String> buildDetailsTemplate) {
    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            emptySuperConsoleConfig,
            new TestConsole(),
            clock,
            silentSummaryVerbosity,
            new DefaultExecutionEnvironment(
                ImmutableMap.copyOf(System.getenv()), System.getProperties()),
            Locale.US,
            logPath,
            timeZone,
            0L,
            0L,
            1000L,
            false,
            buildId,
            printBuildId,
            buildDetailsTemplate,
            ImmutableList.of());
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

  private static class ProgressEstimatorSynchronization {
    /**
     * ProgressEstimator calculates progress asynchronously, indicating that it's down with
     * ProgressEvent.ProjectGenerationProgressUpdated. We create synchronization around the event so
     * that we can properly wait until progress calculation is completed to validate the
     * calculation.
     */
    private final Phaser phaser;

    ProgressEstimatorSynchronization(BuckEventBus eventBus) {
      phaser = new Phaser();
      phaser.register();

      eventBus.register(this);
    }

    /**
     * Every event should have been previous expected via {@link #expectCalculation()}
     *
     * @param e
     */
    @Subscribe
    public void progressCalculationDoneListener(ProgressEvent.ProjectGenerationProgressUpdated e) {
      phaser.arriveAndDeregister();
    }

    /** Indicates that we expect one progress calculation event to be received */
    public void expectCalculation() {
      phaser.register();
    }

    public void awaitCalculation() {
      phaser.arriveAndAwaitAdvance();
    }
  }
}
