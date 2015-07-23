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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.CommandEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.ChromeTraceEvent;
import com.facebook.buck.event.CompilerPluginDurationEvent;
import com.facebook.buck.event.TraceEvent;
import com.facebook.buck.event.TraceEventLogger;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.AnnotationProcessingEvent;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.ArtifactCacheConnectEvent;
import com.facebook.buck.rules.ArtifactCacheEvent;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleStatus;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.rules.CacheResult;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.FakeClock;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.gson.Gson;
import com.google.common.hash.HashCode;

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
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;


public class ChromeTraceBuildListenerTest {
  @Rule
  public TemporaryFolder tmpDir = new TemporaryFolder();

  @Test
  public void testDeleteFiles() throws IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmpDir.getRoot().toPath());

    String tracePath = String.format("%s/build.trace", BuckConstant.BUCK_TRACE_DIR);
    File traceFile = new File(tmpDir.getRoot(), tracePath);
    projectFilesystem.createParentDirs(tracePath);
    traceFile.createNewFile();
    traceFile.setLastModified(0);

    for (int i = 0; i < 10; ++i) {
      File oldResult = new File(tmpDir.getRoot(),
          String.format("%s/build.100%d.trace", BuckConstant.BUCK_TRACE_DIR, i));
      oldResult.createNewFile();
      oldResult.setLastModified(TimeUnit.SECONDS.toMillis(i));
    }

    ChromeTraceBuildListener listener = new ChromeTraceBuildListener(
        projectFilesystem,
        new FakeClock(1409702151000000000L),
        new ObjectMapper(),
        Locale.US,
        TimeZone.getTimeZone("America/Los_Angeles"),
        /* tracesToKeep */ 3,
        false);

    listener.deleteOldTraces();

    ImmutableList<String> files = FluentIterable.
        from(Arrays.asList(projectFilesystem.listFiles(BuckConstant.BUCK_TRACE_DIR))).
        transform(new Function<File, String>() {
          @Override
          public String apply(File input) {
            return input.getName();
          }
        }).toList();
    assertEquals(4, files.size());
    assertEquals(ImmutableSortedSet.of("build.trace",
                                       "build.1009.trace",
                                       "build.1008.trace",
                                       "build.1007.trace"),
        ImmutableSortedSet.copyOf(files));
  }

  @Test
  public void testBuildJson() throws IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmpDir.getRoot().toPath());

    ObjectMapper mapper = new ObjectMapper();

    ChromeTraceBuildListener listener = new ChromeTraceBuildListener(
        projectFilesystem,
        new FakeClock(1409702151000000000L),
        mapper,
        Locale.US,
        TimeZone.getTimeZone("America/Los_Angeles"),
        /* tracesToKeep */ 42,
        false);

    BuildTarget target = BuildTargetFactory.newInstance("//fake:rule");

    FakeBuildRule rule = new FakeBuildRule(
        target,
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSortedSet.<BuildRule>of());
    RuleKey ruleKey = new RuleKey("abc123");
    rule.setRuleKey(ruleKey);
    String stepShortName = "fakeStep";
    String stepDescription = "I'm a Fake Step!";
    UUID stepUuid = UUID.randomUUID();

    ExecutionContext context = createMock(ExecutionContext.class);
    replay(context);

    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(target);
    Iterable<String> buildArgs = Iterables.transform(buildTargets, Functions.toStringFunction());
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.MILLISECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock,
        new BuildId("ChromeTraceBuildListenerTestBuildId"));
    eventBus.register(listener);

    CommandEvent.Started commandEventStarted = CommandEvent.started(
        "party",
        ImmutableList.of("arg1", "arg2"),
        /* isDaemon */ true);
    eventBus.post(commandEventStarted);
    ArtifactCacheConnectEvent.Started artifactCacheConnectEventStarted =
        ArtifactCacheConnectEvent.started();
    eventBus.post(artifactCacheConnectEventStarted);
    eventBus.post(ArtifactCacheConnectEvent.finished(artifactCacheConnectEventStarted));
    BuildEvent.Started buildEventStarted = BuildEvent.started(buildArgs);
    eventBus.post(buildEventStarted);
    ArtifactCacheEvent.Started artifactCacheEventStarted = ArtifactCacheEvent.started(
        ArtifactCacheEvent.Operation.FETCH,
        ImmutableSet.of(ruleKey));
    eventBus.post(artifactCacheEventStarted);
    eventBus.post(
        ArtifactCacheEvent.finished(
            artifactCacheEventStarted,
            CacheResult.hit("http")));
    eventBus.post(BuildRuleEvent.started(rule));
    eventBus.post(StepEvent.started(stepShortName, stepDescription, stepUuid));

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

    final CompilerPluginDurationEvent.Started processingPartOneStarted =
        CompilerPluginDurationEvent.started(
            target,
            annotationProcessorName,
            "processingPartOne",
            ImmutableMap.<String, String>of());
    eventBus.post(processingPartOneStarted);
    eventBus.post(
        CompilerPluginDurationEvent.finished(
            processingPartOneStarted,
            ImmutableMap.<String, String>of()));

    eventBus.post(AnnotationProcessingEvent.finished(annotationProcessingEventStarted));

    eventBus.post(StepEvent.finished(
            StepEvent.started(stepShortName, stepDescription, stepUuid),
            0));
    eventBus.post(
        BuildRuleEvent.finished(
            rule,
            BuildRuleStatus.SUCCESS,
            CacheResult.miss(),
            Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
            Optional.<HashCode>absent(),
            Optional.<Long>absent()));

    try (TraceEventLogger ignored = TraceEventLogger.start(
        eventBus, "planning", ImmutableMap.of("nefarious", "true")
    )) {
      eventBus.post(new TraceEvent("scheming", ChromeTraceEvent.Phase.BEGIN));
      eventBus.post(new TraceEvent("scheming", ChromeTraceEvent.Phase.END,
          ImmutableMap.of("success", "false")));
    }

    eventBus.post(BuildEvent.finished(buildEventStarted, 0));
    eventBus.post(CommandEvent.finished(commandEventStarted, /* exitCode */ 0));
    listener.outputTrace(new BuildId("BUILD_ID"));

    File resultFile = new File(tmpDir.getRoot(), BuckConstant.BUCK_TRACE_DIR + "/build.trace");

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
        "artifact_fetch",
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.of("rule_key", "abc123"));

    assertNextResult(
        resultListCopy,
        "artifact_fetch",
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of(
            "rule_key", "abc123",
            "success", "true",
            "cache_result", "HTTP_HIT"));

    // BuildRuleEvent.Started
    assertNextResult(
        resultListCopy,
        "//fake:rule",
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.of("rule_key", "abc123"));

    assertNextResult(
        resultListCopy,
        "fakeStep",
        ChromeTraceEvent.Phase.BEGIN,
        emptyArgs);

    assertNextResult(
        resultListCopy,
        "com.facebook.FakeProcessor.process",
        ChromeTraceEvent.Phase.BEGIN,
        emptyArgs);

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

    verify(context);
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

    ChromeTraceBuildListener listener = new ChromeTraceBuildListener(
        projectFilesystem,
        new FakeClock(1409702151000000000L),
        new ObjectMapper(),
        Locale.US,
        TimeZone.getTimeZone("America/Los_Angeles"),
        /* tracesToKeep */ 3,
        false);
    try {
      assumeTrue("Can make the root directory read-only", tmpDir.getRoot().setReadOnly());
      listener.outputTrace(new BuildId("BUILD_ID"));
      fail("Expected an exception.");
    } catch (HumanReadableException e) {
      assertEquals(
          "Unable to write trace file: java.nio.file.AccessDeniedException: " +
              projectFilesystem.resolve(BuckConstant.BUCK_OUTPUT_PATH),
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
        new FakeClock(1409702151000000000L),
        new ObjectMapper(),
        Locale.US,
        TimeZone.getTimeZone("America/Los_Angeles"),
        /* tracesToKeep */ 1,
        false);
    listener.outputTrace(new BuildId("BUILD_ID"));
    assertTrue(
        projectFilesystem.exists(
            Paths.get("buck-out/log/traces/build.2014-09-02.16-55-51.BUILD_ID.trace")));
  }

  @Test
  public void canCompressTraces() throws IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmpDir.getRoot().toPath());

    ChromeTraceBuildListener listener = new ChromeTraceBuildListener(
        projectFilesystem,
        new FakeClock(1409702151000000000L),
        new ObjectMapper(),
        Locale.US,
        TimeZone.getTimeZone("America/Los_Angeles"),
        /* tracesToKeep */ 1,
        true);
    listener.outputTrace(new BuildId("BUILD_ID"));

    Path tracePath = Paths.get("buck-out/log/traces/build.2014-09-02.16-55-51.BUILD_ID.trace.gz");

    assertTrue(projectFilesystem.exists(tracePath));

    BufferedReader reader = new BufferedReader(
        new InputStreamReader(
            new GZIPInputStream(projectFilesystem.newFileInputStream(tracePath))));

    List<?> elements = new Gson().fromJson(reader, List.class);
    assertThat(elements, notNullValue());
  }
}
