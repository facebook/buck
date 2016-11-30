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

import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.artifact_cache.ArtifactCacheConnectEvent;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEventFetchData;
import com.facebook.buck.event.ArtifactCompressionEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.ChromeTraceEvent;
import com.facebook.buck.event.CompilerPluginDurationEvent;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.AnnotationProcessingEvent;
import com.facebook.buck.jvm.java.tracing.JavacPhaseEvent;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.BuildRuleKeys;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleStatus;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.FakeClock;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.perf.PerfStatsTracking;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.gson.Gson;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

public class ChromeTraceBuildListenerTest {
  private static final long TIMESTAMP_NANOS = 1409702151000000000L;
  private static final String EXPECTED_DIR =
      "buck-out/log/2014-09-02_23h55m51s_no_sub_command_BUILD_ID/";

  @Rule
  public TemporaryFolder tmpDir = new TemporaryFolder();

  private InvocationInfo invocationInfo;

  @Before
  public void setUp() throws IOException {
    invocationInfo = InvocationInfo.builder()
        .setTimestampMillis(TimeUnit.NANOSECONDS.toMillis(TIMESTAMP_NANOS))
        .setBuckLogDir(tmpDir.getRoot().toPath().resolve("buck-out/log"))
        .setBuildId(new BuildId("BUILD_ID"))
        .setSubCommand("no_sub_command")
        .setIsDaemon(false)
        .setSuperConsoleEnabled(false)
        .build();
  }

  @Test
  public void testDeleteFiles() throws IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmpDir.getRoot().toPath());

    String tracePath = invocationInfo.getLogDirectoryPath().resolve("build.trace").toString();

    File traceFile = new File(tracePath);
    projectFilesystem.createParentDirs(tracePath);
    traceFile.createNewFile();
    traceFile.setLastModified(0);

    for (int i = 0; i < 10; ++i) {
      File oldResult = new File(
          String.format("%s/build.100%d.trace", invocationInfo.getLogDirectoryPath(), i));
      oldResult.createNewFile();
      oldResult.setLastModified(TimeUnit.SECONDS.toMillis(i));
    }

    ChromeTraceBuildListener listener = new ChromeTraceBuildListener(
        projectFilesystem,
        invocationInfo,
        new FakeClock(TIMESTAMP_NANOS),
        ObjectMappers.newDefaultInstance(),
        Locale.US,
        TimeZone.getTimeZone("America/Los_Angeles"),
        /* tracesToKeep */ 3,
        false);

    listener.outputTrace(invocationInfo.getBuildId());

    ImmutableList<String> files = FluentIterable.
        from(Arrays.asList(projectFilesystem.listFiles(invocationInfo.getLogDirectoryPath()))).
        filter(input -> input.toString().endsWith(".trace")).
        transform(File::getName).
        toList();
    assertEquals(4, files.size());
    assertEquals(
        ImmutableSortedSet.of(
            "build.trace",
            "build.1009.trace",
            "build.1008.trace",
            "build.2014-09-02.16-55-51.BUILD_ID.trace"),
        ImmutableSortedSet.copyOf(files));
  }

  @Test
  public void testBuildJson() throws IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmpDir.getRoot().toPath());

    ObjectMapper mapper = ObjectMappers.newDefaultInstance();

    BuildId buildId = new BuildId("ChromeTraceBuildListenerTestBuildId");
    ChromeTraceBuildListener listener = new ChromeTraceBuildListener(
        projectFilesystem,
        invocationInfo,
        new FakeClock(TIMESTAMP_NANOS),
        mapper,
        Locale.US,
        TimeZone.getTimeZone("America/Los_Angeles"),
        /* tracesToKeep */ 42,
        false);

    BuildTarget target = BuildTargetFactory.newInstance("//fake:rule");

    FakeBuildRule rule = new FakeBuildRule(
        target,
        new SourcePathResolver(
            new BuildRuleResolver(
                TargetGraph.EMPTY,
                new DefaultTargetNodeToBuildRuleTransformer())
        ),
        ImmutableSortedSet.of());
    RuleKey ruleKey = new RuleKey("abc123");
    String stepShortName = "fakeStep";
    String stepDescription = "I'm a Fake Step!";
    UUID stepUuid = UUID.randomUUID();

    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(target);
    Iterable<String> buildArgs = Iterables.transform(buildTargets, Object::toString);
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.MILLISECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock, buildId);
    eventBus.register(listener);

    CommandEvent.Started commandEventStarted = CommandEvent.started(
        "party",
        ImmutableList.of("arg1", "arg2"),
        /* isDaemon */ true);
    eventBus.post(commandEventStarted);
    eventBus.post(new PerfStatsTracking.MemoryPerfStatsEvent(
        /* freeMemoryBytes */ 1024 * 1024L,
        /* totalMemoryBytes */ 3 * 1024 * 1024L,
        /* timeSpentInGcMs */ -1,
        /* currentMemoryBytesUsageByPool */ ImmutableMap.of("flower", 42L * 1024 * 1024)));
    ArtifactCacheConnectEvent.Started artifactCacheConnectEventStarted =
        ArtifactCacheConnectEvent.started();
    eventBus.post(artifactCacheConnectEventStarted);
    eventBus.post(ArtifactCacheConnectEvent.finished(artifactCacheConnectEventStarted));
    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs);
    eventBus.post(buildEventStarted);

    HttpArtifactCacheEvent.Started artifactCacheEventStarted =
        HttpArtifactCacheEvent.newFetchStartedEvent(ruleKey);
    eventBus.post(artifactCacheEventStarted);
    eventBus.post(
        HttpArtifactCacheEvent.newFinishedEventBuilder(artifactCacheEventStarted)
            .setFetchDataBuilder(
                HttpArtifactCacheEventFetchData.builder()
                    .setFetchResult(CacheResult.hit("http")))
            .build());

    ArtifactCompressionEvent.Started artifactCompressionStartedEvent =
        ArtifactCompressionEvent.started(
            ArtifactCompressionEvent.Operation.COMPRESS, ImmutableSet.of(ruleKey));
    eventBus.post(artifactCompressionStartedEvent);
    eventBus.post(ArtifactCompressionEvent.finished(artifactCompressionStartedEvent));

    eventBus.post(BuildRuleEvent.started(rule));
    eventBus.post(StepEvent.started(stepShortName, stepDescription, stepUuid));


    JavacPhaseEvent.Started runProcessorsStartedEvent = JavacPhaseEvent.started(
        target,
        JavacPhaseEvent.Phase.RUN_ANNOTATION_PROCESSORS,
        ImmutableMap.of());
    eventBus.post(runProcessorsStartedEvent);

    String annotationProcessorName = "com.facebook.FakeProcessor";
    AnnotationProcessingEvent.Operation operation = AnnotationProcessingEvent.Operation.PROCESS;
    int annotationRound = 1;
    boolean isLastRound = false;
    AnnotationProcessingEvent.Started annotationProcessingEventStarted =
        AnnotationProcessingEvent.started(
            target,
            annotationProcessorName,
            operation,
            annotationRound,
            isLastRound);
    eventBus.post(annotationProcessingEventStarted);

    HttpArtifactCacheEvent.Scheduled httpScheduled = HttpArtifactCacheEvent.newStoreScheduledEvent(
        Optional.of("TARGET_ONE"), ImmutableSet.of(ruleKey));
    HttpArtifactCacheEvent.Started httpStarted =
        HttpArtifactCacheEvent.newStoreStartedEvent(httpScheduled);

    eventBus.post(httpStarted);

    HttpArtifactCacheEvent.Finished httpFinished =
        HttpArtifactCacheEvent.newFinishedEventBuilder(httpStarted).build();

    eventBus.post(httpFinished);

    final CompilerPluginDurationEvent.Started processingPartOneStarted =
        CompilerPluginDurationEvent.started(
            target,
            annotationProcessorName,
            "processingPartOne",
            ImmutableMap.of());
    eventBus.post(processingPartOneStarted);
    eventBus.post(
        CompilerPluginDurationEvent.finished(
            processingPartOneStarted,
            ImmutableMap.of()));

    eventBus.post(AnnotationProcessingEvent.finished(annotationProcessingEventStarted));

    eventBus.post(
        JavacPhaseEvent.finished(runProcessorsStartedEvent, ImmutableMap.of()));

    eventBus.post(StepEvent.finished(
        StepEvent.started(stepShortName, stepDescription, stepUuid),
        0));
    eventBus.post(
        BuildRuleEvent.finished(
            rule,
            BuildRuleKeys.of(ruleKey),
            BuildRuleStatus.SUCCESS,
            CacheResult.miss(),
            Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));

    try (final SimplePerfEvent.Scope scope1 = SimplePerfEvent.scope(
        eventBus,
        PerfEventId.of("planning"),
        ImmutableMap.<String, Object>of("nefarious", "true"))) {
      try (final SimplePerfEvent.Scope scope2 = SimplePerfEvent.scope(
          eventBus,
          PerfEventId.of("scheming"))) {
        scope2.appendFinishedInfo("success", "false");
      }
    }

    eventBus.post(BuildEvent.finished(buildEventStarted, 0));
    eventBus.post(CommandEvent.finished(commandEventStarted, /* exitCode */ 0));
    listener.outputTrace(new BuildId("BUILD_ID"));

    File resultFile = new File(tmpDir.getRoot(), "buck-out/log/build.trace");

    List<ChromeTraceEvent> originalResultList = mapper.readValue(
        resultFile,
        new TypeReference<List<ChromeTraceEvent>>() {});
    List<ChromeTraceEvent> resultListCopy = new ArrayList<>();
    resultListCopy.addAll(originalResultList);
    ImmutableMap<String, String> emptyArgs = ImmutableMap.of();

    assertNextResult(
        resultListCopy,
        "process_name",
        ChromeTraceEvent.Phase.METADATA,
        ImmutableMap.of("name", "buck"));

    assertNextResult(
        resultListCopy,
        "party",
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.of("command_args", "arg1 arg2"));

    assertNextResult(
        resultListCopy,
        "memory",
        ChromeTraceEvent.Phase.COUNTER,
        ImmutableMap.of(
            "used_memory_mb", "2",
            "free_memory_mb", "1",
            "total_memory_mb", "3",
            "time_spent_in_gc_sec", "0",
            "pool_flower_mb", "42"));

    assertNextResult(
        resultListCopy,
        "artifact_connect",
        ChromeTraceEvent.Phase.BEGIN,
        emptyArgs);

    assertNextResult(
        resultListCopy,
        "artifact_connect",
        ChromeTraceEvent.Phase.END,
        emptyArgs);

    assertNextResult(
        resultListCopy,
        "build",
        ChromeTraceEvent.Phase.BEGIN,
        emptyArgs);

    assertNextResult(
        resultListCopy,
        "http_artifact_fetch",
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.of("rule_key", "abc123"));

    assertNextResult(
        resultListCopy,
        "http_artifact_fetch",
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of(
            "rule_key", "abc123",
            "success", "true",
            "cache_result", "HTTP_HIT"));

    assertNextResult(
        resultListCopy,
        "artifact_compress",
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.of("rule_key", "abc123"));

    assertNextResult(
        resultListCopy,
        "artifact_compress",
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of("rule_key", "abc123"));

    // BuildRuleEvent.Started
    assertNextResult(
        resultListCopy,
        "//fake:rule",
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.of());

    assertNextResult(
        resultListCopy,
        "fakeStep",
        ChromeTraceEvent.Phase.BEGIN,
        emptyArgs);

    assertNextResult(
        resultListCopy,
        "run annotation processors",
        ChromeTraceEvent.Phase.BEGIN,
        emptyArgs);

    assertNextResult(
        resultListCopy,
        "com.facebook.FakeProcessor.process",
        ChromeTraceEvent.Phase.BEGIN,
        emptyArgs);

    assertNextResult(
        resultListCopy,
        "http_artifact_store",
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.of(
            "rule_key", "abc123"));

    assertNextResult(
        resultListCopy,
        "http_artifact_store",
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of(
            "success", "true",
            "rule_key", "abc123"));

    assertNextResult(
        resultListCopy,
        "processingPartOne",
        ChromeTraceEvent.Phase.BEGIN,
        emptyArgs);

    assertNextResult(
        resultListCopy,
        "processingPartOne",
        ChromeTraceEvent.Phase.END,
        emptyArgs);

    assertNextResult(
        resultListCopy,
        "com.facebook.FakeProcessor.process",
        ChromeTraceEvent.Phase.END,
        emptyArgs);

    assertNextResult(
        resultListCopy,
        "run annotation processors",
        ChromeTraceEvent.Phase.END,
        emptyArgs);

    assertNextResult(
        resultListCopy,
        "fakeStep",
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of(
            "description", "I'm a Fake Step!",
            "exit_code", "0"));

    assertNextResult(
        resultListCopy,
        "//fake:rule",
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of(
            "cache_result", "miss",
            "success_type", "BUILT_LOCALLY"));

    assertNextResult(
        resultListCopy,
        "planning",
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.of("nefarious", "true"));

    assertNextResult(
        resultListCopy,
        "scheming",
        ChromeTraceEvent.Phase.BEGIN,
        emptyArgs);

    assertNextResult(
        resultListCopy,
        "scheming",
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of("success", "false"));

    assertNextResult(
        resultListCopy,
        "planning",
        ChromeTraceEvent.Phase.END,
        emptyArgs);

    assertNextResult(
        resultListCopy,
        "build",
        ChromeTraceEvent.Phase.END,
        emptyArgs);

    assertNextResult(
        resultListCopy,
        "party",
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of(
            "command_args", "arg1 arg2",
            "daemon", "true"));

    assertEquals(0, resultListCopy.size());
  }

  private static void assertNextResult(
      List<ChromeTraceEvent> resultList,
      String expectedName,
      ChromeTraceEvent.Phase expectedPhase,
      ImmutableMap<String, String> expectedArgs) {
    assertTrue(resultList.size() > 0);
    assertEquals(expectedName, resultList.get(0).getName());
    assertEquals(expectedPhase, resultList.get(0).getPhase());
    assertEquals(expectedArgs, resultList.get(0).getArgs());
    resultList.remove(0);
  }

  @Test
  public void testOutputFailed() throws IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmpDir.getRoot().toPath());
    assumeTrue("Can make the root directory read-only", tmpDir.getRoot().setReadOnly());

    try {
      ChromeTraceBuildListener listener = new ChromeTraceBuildListener(
          projectFilesystem,
          invocationInfo,
          new FakeClock(TIMESTAMP_NANOS),
          ObjectMappers.newDefaultInstance(),
          Locale.US,
          TimeZone.getTimeZone("America/Los_Angeles"),
        /* tracesToKeep */ 3,
          false);
      listener.outputTrace(invocationInfo.getBuildId());
      fail("Expected an exception.");
    } catch (HumanReadableException e) {
      assertEquals(
          "Unable to write trace file: java.nio.file.AccessDeniedException: " +
              projectFilesystem.resolve(projectFilesystem.getBuckPaths().getBuckOut()),
          e.getMessage());
    }  finally {
      tmpDir.getRoot().setWritable(true);
    }
  }

  @Test
  public void outputFileUsesCurrentTime() throws IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmpDir.getRoot().toPath());

    ChromeTraceBuildListener listener = new ChromeTraceBuildListener(
        projectFilesystem,
        invocationInfo,
        new FakeClock(TIMESTAMP_NANOS),
        ObjectMappers.newDefaultInstance(),
        Locale.US,
        TimeZone.getTimeZone("America/Los_Angeles"),
        /* tracesToKeep */ 1,
        false);
    listener.outputTrace(invocationInfo.getBuildId());
    assertTrue(
        projectFilesystem.exists(
            Paths.get(EXPECTED_DIR + "build.2014-09-02.16-55-51.BUILD_ID.trace")));
  }

  @Test
  public void canCompressTraces() throws IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmpDir.getRoot().toPath());

    ChromeTraceBuildListener listener = new ChromeTraceBuildListener(
        projectFilesystem,
        invocationInfo,
        new FakeClock(TIMESTAMP_NANOS),
        ObjectMappers.newDefaultInstance(),
        Locale.US,
        TimeZone.getTimeZone("America/Los_Angeles"),
        /* tracesToKeep */ 1,
        true);
    listener.outputTrace(invocationInfo.getBuildId());

    Path tracePath = Paths.get(EXPECTED_DIR + "build.2014-09-02.16-55-51.BUILD_ID.trace.gz");

    assertTrue(projectFilesystem.exists(tracePath));

    BufferedReader reader = new BufferedReader(
        new InputStreamReader(
            new GZIPInputStream(projectFilesystem.newFileInputStream(tracePath))));

    List<?> elements = new Gson().fromJson(reader, List.class);
    assertThat(elements, notNullValue());
  }
}
