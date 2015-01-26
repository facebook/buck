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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.cli.CommandEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.ChromeTraceEvent;
import com.facebook.buck.event.TraceEvent;
import com.facebook.buck.event.TraceEventLogger;
import com.facebook.buck.io.ProjectFilesystem;
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
import com.facebook.buck.rules.BuildRuleSuccess;
import com.facebook.buck.rules.CacheResult;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.ImmutableBuildRuleType;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.FakeStep;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.FakeClock;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;


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
        /* tracesToKeep */ 3);

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
        /* tracesToKeep */ 42);

    BuildTarget target = BuildTargetFactory.newInstance("//fake:rule");

    FakeBuildRule rule = new FakeBuildRule(
        ImmutableBuildRuleType.of("fake_rule"),
        target,
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSortedSet.<BuildRule>of()
    );
    RuleKey ruleKey = new RuleKey("abc123");
    rule.setRuleKey(ruleKey);
    FakeStep step = new FakeStep("fakeStep", "I'm a Fake Step!", 0);

    ExecutionContext context = createMock(ExecutionContext.class);
    replay(context);

    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(target);
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.MILLISECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock,
        new BuildId("ChromeTraceBuildListenerTestBuildId"));
    eventBus.register(listener);

    eventBus.post(CommandEvent.started("party",
        ImmutableList.of("arg1", "arg2"),
        /* isDaemon */ true));
    eventBus.post(ArtifactCacheConnectEvent.started());
    eventBus.post(ArtifactCacheConnectEvent.finished());
    eventBus.post(BuildEvent.started(buildTargets));
    eventBus.post(ArtifactCacheEvent.started(ArtifactCacheEvent.Operation.FETCH, ruleKey));
    eventBus.post(ArtifactCacheEvent.finished(ArtifactCacheEvent.Operation.FETCH,
        ruleKey,
        CacheResult.CASSANDRA_HIT));
    eventBus.post(BuildRuleEvent.started(rule));
    eventBus.post(StepEvent.started(step, "I'm a Fake Step!"));

    eventBus.post(StepEvent.finished(step, "I'm a Fake Step!", 0));
    eventBus.post(BuildRuleEvent.finished(
        rule,
        BuildRuleStatus.SUCCESS,
        CacheResult.MISS,
        Optional.of(BuildRuleSuccess.Type.BUILT_LOCALLY)));

    try (TraceEventLogger ignored = TraceEventLogger.start(
        eventBus, "planning", ImmutableMap.of("nefarious", "true")
    )) {
      eventBus.post(new TraceEvent("scheming", ChromeTraceEvent.Phase.BEGIN));
      eventBus.post(new TraceEvent("scheming", ChromeTraceEvent.Phase.END,
          ImmutableMap.<String, String>of("success", "false")));
    }

    eventBus.post(BuildEvent.finished(buildTargets, 0));
    eventBus.post(CommandEvent.finished("party",
        ImmutableList.of("arg1", "arg2"),
        /* isDaemon */ true,
        /* exitCode */ 0));
    listener.outputTrace(new BuildId("BUILD_ID"));

    File resultFile = new File(tmpDir.getRoot(), BuckConstant.BUCK_TRACE_DIR + "/build.trace");

    List<ChromeTraceEvent> resultMap = mapper.readValue(
        resultFile,
        new TypeReference<List<ChromeTraceEvent>>() {});

    assertEquals(17, resultMap.size());

    assertEquals("process_name", resultMap.get(0).getName());
    assertEquals(ChromeTraceEvent.Phase.METADATA, resultMap.get(0).getPhase());
    assertEquals(
        ImmutableMap.of(
            "name", "buck"
            ),
        resultMap.get(0).getArgs());

    assertEquals("party", resultMap.get(1).getName());
    assertEquals(ChromeTraceEvent.Phase.BEGIN, resultMap.get(1).getPhase());
    assertEquals(
        ImmutableMap.of(
            "command_args", "arg1 arg2"
            ),
        resultMap.get(1).getArgs());

    assertEquals("artifact_connect", resultMap.get(2).getName());
    assertEquals(ChromeTraceEvent.Phase.BEGIN, resultMap.get(2).getPhase());

    assertEquals("artifact_connect", resultMap.get(3).getName());
    assertEquals(ChromeTraceEvent.Phase.END, resultMap.get(3).getPhase());

    assertEquals("build", resultMap.get(4).getName());
    assertEquals(ChromeTraceEvent.Phase.BEGIN, resultMap.get(4).getPhase());

    assertEquals("artifact_fetch", resultMap.get(5).getName());
    assertEquals(ChromeTraceEvent.Phase.BEGIN, resultMap.get(5).getPhase());

    assertEquals("artifact_fetch", resultMap.get(6).getName());
    assertEquals(ChromeTraceEvent.Phase.END, resultMap.get(6).getPhase());

    // BuildRuleEvent.Started
    assertEquals("//fake:rule", resultMap.get(7).getName());
    assertEquals(ChromeTraceEvent.Phase.BEGIN, resultMap.get(7).getPhase());
    assertEquals(ImmutableMap.of("rule_key", "abc123"), resultMap.get(7).getArgs());

    assertEquals("fakeStep", resultMap.get(8).getName());
    assertEquals(ChromeTraceEvent.Phase.BEGIN, resultMap.get(8).getPhase());

    assertEquals("fakeStep", resultMap.get(9).getName());
    assertEquals(
        ImmutableMap.of(
            "description", "I'm a Fake Step!",
            "exit_code", "0"),
        resultMap.get(9).getArgs());
    assertEquals(ChromeTraceEvent.Phase.END, resultMap.get(9).getPhase());

    // BuildRuleEvent.Finished
    assertEquals("//fake:rule", resultMap.get(10).getName());
    assertEquals(ChromeTraceEvent.Phase.END, resultMap.get(10).getPhase());
    assertEquals(
        ImmutableMap.of(
            "cache_result", "miss",
            "success_type", "BUILT_LOCALLY"),
        resultMap.get(10).getArgs());

    assertEquals("planning", resultMap.get(11).getName());
    assertEquals(ChromeTraceEvent.Phase.BEGIN, resultMap.get(11).getPhase());
    assertEquals(ImmutableMap.of("nefarious", "true"), resultMap.get(11).getArgs());

    assertEquals("scheming", resultMap.get(12).getName());
    assertEquals(ChromeTraceEvent.Phase.BEGIN, resultMap.get(12).getPhase());
    assertEquals(ImmutableMap.of(), resultMap.get(12).getArgs());

    assertEquals("scheming", resultMap.get(13).getName());
    assertEquals(ChromeTraceEvent.Phase.END, resultMap.get(13).getPhase());
    assertEquals(ImmutableMap.of("success", "false"), resultMap.get(13).getArgs());

    assertEquals("planning", resultMap.get(14).getName());
    assertEquals(ChromeTraceEvent.Phase.END, resultMap.get(14).getPhase());
    assertEquals(ImmutableMap.of(), resultMap.get(14).getArgs());

    assertEquals("build", resultMap.get(15).getName());
    assertEquals(ChromeTraceEvent.Phase.END, resultMap.get(15).getPhase());

    assertEquals("party", resultMap.get(16).getName());
    assertEquals(ChromeTraceEvent.Phase.END, resultMap.get(16).getPhase());
    assertEquals(
        ImmutableMap.of(
            "command_args", "arg1 arg2",
            "daemon", "true"
            ),
        resultMap.get(16).getArgs());

    verify(context);
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
        /* tracesToKeep */ 3);
    try {
      tmpDir.getRoot().setReadOnly();
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
        /* tracesToKeep */ 1);
    listener.outputTrace(new BuildId("BUILD_ID"));
    assertTrue(
        projectFilesystem.exists(
            Paths.get("buck-out/log/traces/build.2014-09-02.16-55-51.BUILD_ID.trace")));
  }
}
