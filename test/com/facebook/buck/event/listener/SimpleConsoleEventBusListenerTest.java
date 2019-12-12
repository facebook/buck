/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.event.listener;

import static com.facebook.buck.event.TestEventConfigurator.configureTestEventAtTime;
import static com.facebook.buck.util.string.MoreStrings.linesToText;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.type.UploadToCacheResultType;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.build.stats.BuildRuleDurationTracker;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.BuildRuleKeys;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.InstallEvent;
import com.facebook.buck.event.listener.interfaces.AdditionalConsoleLineProvider;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.test.config.TestResultSummaryVerbosity;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.IncrementingFakeClock;
import com.facebook.buck.util.unit.SizeUnit;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class SimpleConsoleEventBusListenerTest {
  private static final String TARGET_ONE = "//target:one";
  private static final String TARGET_TWO = "//target:two";
  private static final String SEVERE_MESSAGE = "This is a sample severe message.";
  private static final String ADDITIONAL_LINE_PROVIDER_TEXT = "[additional line from the provider]";

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

  @Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return Arrays.asList(
        new Object[][] {
          {
            "no_build_id_and_no_build_url",
            false,
            Optional.empty(),
            ImmutableSet.of("build", "test", "install"),
            Optional.empty(),
            false,
          },
          {
            "build_id_and_no_build_url",
            true,
            Optional.empty(),
            ImmutableSet.of("build", "test", "install"),
            Optional.empty(),
            false,
          },
          {
            "build_id_and_build_url",
            true,
            Optional.of("View details at https://example.com/build/{build_id}"),
            ImmutableSet.of("build", "test", "install"),
            Optional.empty(),
            false,
          },
          {
            "no_build_id_and_build_url",
            false,
            Optional.of("View details at https://example.com/build/{build_id}"),
            ImmutableSet.of("build", "test", "install"),
            Optional.empty(),
            false,
          },
          {
            "no_build_id_and_build_url_but_no_build_command",
            false,
            Optional.of("View details at https://example.com/build/{build_id}"),
            ImmutableSet.of(),
            Optional.empty(),
            false,
          },
          {
            "with_re_session_id",
            false,
            Optional.empty(),
            ImmutableSet.of(),
            Optional.of("super cool remote execution session id."),
            false,
          },
          {
            "with_additional_line_provider",
            false,
            Optional.empty(),
            ImmutableSet.of(),
            Optional.of("super cool remote execution session id."),
            true,
          },
        });
  }

  private final BuildId buildId = new BuildId("1234-5678");

  @Parameterized.Parameter(0)
  public String _ignoredName;

  @Parameterized.Parameter(1)
  public boolean printBuildId;

  @Parameterized.Parameter(2)
  public Optional<String> buildDetailsTemplate;

  @Parameterized.Parameter(3)
  public ImmutableSet<String> buildDetailsCommands;

  @Parameterized.Parameter(4)
  public Optional<String> reSessionIdDetails;

  @Parameterized.Parameter(5)
  public boolean enableAdditionalLineProviders;

  @Test
  public void testSimpleBuild() {
    setupSimpleConsole(
        false, printBuildId, buildDetailsTemplate, reSessionIdDetails, buildDetailsCommands);
    String expectedOutput = "";
    if (printBuildId) {
      expectedOutput = "Build UUID: 1234-5678" + System.lineSeparator();
    }
    if (reSessionIdDetails.isPresent()) {
      expectedOutput +=
          "[RE] SessionInfo: [super cool remote execution session id.]." + System.lineSeparator();
    }
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

    expectedOutput += "PARSING BUCK FILES: FINISHED IN 0.4s" + System.lineSeparator();
    assertOutput(expectedOutput, console);

    BuildRuleEvent.Started started = BuildRuleEvent.started(fakeRule, durationTracker);
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(started, 600L, TimeUnit.MILLISECONDS, threadId));

    HttpArtifactCacheEvent.Scheduled storeScheduledOne =
        ArtifactCacheTestUtils.postStoreScheduled(
            eventBus, threadId, BuildTargetFactory.newInstance(TARGET_ONE), 700L);

    HttpArtifactCacheEvent.Scheduled storeScheduledTwo =
        ArtifactCacheTestUtils.postStoreScheduled(
            eventBus, threadId, BuildTargetFactory.newInstance(TARGET_TWO), 700L);

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
                UploadToCacheResultType.UNCACHEABLE,
                Optional.empty(),
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
            threadId));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.finished(buildEventStarted, ExitCode.SUCCESS),
            1234L,
            TimeUnit.MILLISECONDS,
            threadId));

    expectedOutput += "BUILT  0.4s //banana:stand" + System.lineSeparator();

    expectedOutput += getAdditionalLineProviderText();

    expectedOutput +=
        linesToText(
            FINISHED_DOWNLOAD_STRING + ", 100.0% CACHE MISS",
            "BUILDING: FINISHED IN 1.2s",
            "WAITING FOR HTTP CACHE UPLOADS 0.00 BYTES (0 COMPLETE/0 FAILED/1 UPLOADING/1 PENDING)",
            "BUILD SUCCEEDED",
            "");
    assertOutput(expectedOutput, console);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            ConsoleEvent.severe(SEVERE_MESSAGE), 1500L, TimeUnit.MILLISECONDS, threadId));

    expectedOutput += SEVERE_MESSAGE + System.lineSeparator();
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

    expectedOutput += "INSTALLING: FINISHED IN 1.5s" + System.lineSeparator();
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
        "HTTP CACHE UPLOAD: FINISHED 1.50 MBYTES (1 COMPLETE/1 FAILED/0 UPLOADING/0 PENDING)"
            + System.lineSeparator();
    assertOutput(expectedOutput, console);

    CommandEvent.Started commandStarted =
        CommandEvent.started(
            "build", ImmutableList.of(), Paths.get(""), OptionalLong.of(100), 1234);
    eventBus.post(CommandEvent.finished(commandStarted, ExitCode.SUCCESS));
    if (buildDetailsCommands.contains("build") && buildDetailsTemplate.isPresent()) {
      expectedOutput +=
          "View details at https://example.com/build/1234-5678" + System.lineSeparator();
    }
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

    expectedOutput += getAdditionalLineProviderText();
    expectedOutput += FINISHED_DOWNLOAD_STRING + ", 0.0% CACHE MISS" + System.lineSeparator();
    expectedOutput +=
        linesToText("BUILDING: FINISHED IN 1.0s 0/10 JOBS, 0 UPDATED", "BUILD SUCCEEDED", "");

    assertOutput(expectedOutput, console);
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

    expectedOutput += "PARSING BUCK FILES: FINISHED IN 0.2s" + System.lineSeparator();
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
    expectedOutput += "CREATING ACTION GRAPH: FINISHED IN 0.2s" + System.lineSeparator();
    expectedOutput += getAdditionalLineProviderText();
    expectedOutput +=
        linesToText(
            FINISHED_DOWNLOAD_STRING + ", 0.0% CACHE MISS",
            "BUILDING: FINISHED IN 0.1s",
            "BUILD SUCCEEDED",
            "");

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

    expectedOutput += "PARSING BUCK FILES: FINISHED IN 0.4s" + System.lineSeparator();
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
                UploadToCacheResultType.UNCACHEABLE,
                Optional.empty(),
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
            threadId));
    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            BuildEvent.finished(buildEventStarted, ExitCode.SUCCESS),
            1234L,
            TimeUnit.MILLISECONDS,
            threadId));

    expectedOutput += getAdditionalLineProviderText();

    expectedOutput +=
        linesToText(
            FINISHED_DOWNLOAD_STRING + ", 100.0% CACHE MISS",
            "BUILDING: FINISHED IN 1.2s",
            "BUILD SUCCEEDED",
            "");
    assertOutput(expectedOutput, console);
  }

  private void assertOutput(String expectedOutput, TestConsole console) {
    assertEquals("", console.getTextWrittenToStdOut());
    assertEquals(expectedOutput, console.getTextWrittenToStdErr());
  }

  private void setupSimpleConsole(boolean hideSucceededRules) {
    setupSimpleConsole(
        hideSucceededRules, false, Optional.empty(), Optional.empty(), ImmutableSet.of());
  }

  private void setupSimpleConsole(
      boolean hideSucceededRules,
      boolean printBuildId,
      Optional<String> buildDetailsTemplate,
      Optional<String> reSessionIdInfo,
      ImmutableSet<String> buildDetailsCommands) {
    SimpleConsoleEventBusListener listener =
        new SimpleConsoleEventBusListener(
            new RenderingConsole(fakeClock, console),
            fakeClock,
            TestResultSummaryVerbosity.of(false, false),
            hideSucceededRules,
            /* numberOfSlowRulesToShow */ 0,
            false,
            Locale.US,
            logPath,
            new DefaultExecutionEnvironment(
                EnvVariablesProvider.getSystemEnv(), System.getProperties()),
            buildId,
            printBuildId,
            buildDetailsTemplate,
            buildDetailsCommands,
            reSessionIdInfo,
            enableAdditionalLineProviders ? createAdditionalLineProvider() : ImmutableList.of());

    listener.register(eventBus);
  }

  private ImmutableList<AdditionalConsoleLineProvider> createAdditionalLineProvider() {
    AdditionalConsoleLineProvider provider =
        currentTimeMillis -> ImmutableList.of(ADDITIONAL_LINE_PROVIDER_TEXT);
    return ImmutableList.of(provider);
  }

  private String getAdditionalLineProviderText() {
    if (enableAdditionalLineProviders) {
      return ADDITIONAL_LINE_PROVIDER_TEXT + System.lineSeparator();
    } else {
      return "";
    }
  }
}
