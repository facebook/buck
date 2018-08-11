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

package com.facebook.buck.event;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.type.UploadToCacheResultType;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.build.stats.BuildRuleDurationTracker;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.BuildRuleKeys;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.test.event.IndividualTestEvent;
import com.facebook.buck.core.test.event.TestRunEvent;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.test.FakeTestResults;
import com.facebook.buck.test.external.ExternalTestRunEvent;
import com.facebook.buck.test.external.ExternalTestSpecCalculationEvent;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.testutil.JsonMatcher;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Optional;
import java.util.Random;
import org.junit.Before;
import org.junit.Test;

/**
 * While it may seem that the main purpose of this test is to annoy anyone making changes to the
 * Events under test, the purpose of this test is to make sure we're not making accidental changes
 * to the serialized JSON representation of this events, as that JSON is the public API exposed via
 * the websocket to the IntelliJ plugin and anyone else who might be listening.
 */
public class EventSerializationTest {

  private long timestamp;
  private long nanoTime;
  private long threadUserNanoTime;
  private long threadId;
  private BuildId buildId;
  private BuildRuleDurationTracker durationTracker;

  @Before
  public void setUp() {
    Clock clock = new DefaultClock();
    timestamp = clock.currentTimeMillis();
    nanoTime = clock.nanoTime();
    // Not using real value as not all JVMs will support thread user time.
    threadUserNanoTime = new Random().nextLong();
    threadId = 0;
    buildId = new BuildId("Test");
    durationTracker = new BuildRuleDurationTracker();
    EventKey.setSequenceValueForTest(4242L);
  }

  @Test
  public void testConsoleEvent() throws IOException {
    ConsoleEvent event = ConsoleEvent.severe("Something happened");
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = ObjectMappers.WRITER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,\"type\":\"ConsoleEvent\",\"eventKey\":{\"value\":4242},"
            + "\"level\": {\"name\":\"SEVERE\",\"resourceBundleName\":"
            + "\"sun.util.logging.resources.logging\",\"localizedName\":\"SEVERE\"},"
            + " \"message\":\"Something happened\"}",
        message);
  }

  @Test
  public void testProjectGenerationEventFinished() throws IOException {
    ProjectGenerationEvent.Finished event = ProjectGenerationEvent.finished();
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = ObjectMappers.WRITER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,\"type\":\"ProjectGenerationFinished\"," + "\"eventKey\":{\"value\":4242}}", message);
  }

  @Test
  public void testProjectGenerationEventStarted() throws IOException {
    ProjectGenerationEvent.Started event = ProjectGenerationEvent.started();
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = ObjectMappers.WRITER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,\"type\":\"ProjectGenerationStarted\"," + "\"eventKey\":{\"value\":4242}}", message);
  }

  @Test
  public void testParseEventStarted() throws IOException {
    ParseEvent.Started event = ParseEvent.started(ImmutableList.of());
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = ObjectMappers.WRITER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,\"buildTargets\":[],\"type\":\"ParseStarted\"," + "\"eventKey\":{\"value\":4242}}",
        message);
  }

  @Test
  public void testParseEventFinished() throws IOException {
    ParseEvent.Started started =
        ParseEvent.started(ImmutableList.of(BuildTargetFactory.newInstance("//base:short#flv")));
    ParseEvent.Finished event = ParseEvent.finished(started, 10, Optional.empty());
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = ObjectMappers.WRITER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,"
            + "\"buildTargets\":[{\"baseName\":\"//base\","
            + "\"shortName\":\"short\",\"flavor\":\"flv\"}],\"type\":\"ParseFinished\","
            + "\"eventKey\":{\"value\":4242}, \"processedBytes\": 10}",
        message);
  }

  @Test
  public void testBuildEventStarted() throws IOException {
    BuildEvent.Started event = BuildEvent.started(ImmutableSet.of("//base:short"));
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = ObjectMappers.WRITER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,\"eventKey\":{\"value\":4242},"
            + "\"buildArgs\":[\"//base:short\"],"
            + "\"type\":\"BuildStarted\"}",
        message);
  }

  @Test
  public void testBuildEventFinished() throws IOException {
    BuildEvent.Finished event =
        BuildEvent.finished(BuildEvent.started(ImmutableSet.of("//base:short")), ExitCode.SUCCESS);
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = ObjectMappers.WRITER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,\"eventKey\":{\"value\":4242},"
            + "\"buildArgs\":[\"//base:short\"], \"exitCode\":0,\"type\":\"BuildFinished\"}",
        message);
  }

  @Test
  public void testBuildRuleEventStarted() throws IOException {
    BuildRule rule = new FakeBuildRule("//fake:rule");
    BuildRuleEvent.Started event = BuildRuleEvent.started(rule, durationTracker);
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = ObjectMappers.WRITER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,\"buildRule\":{\"type\":\"fake_build_rule\",\"name\":\"//fake:rule\"},"
            + "\"type\":\"BuildRuleStarted\",\"duration\":{\"wallMillisDuration\":0,\"nanoDuration\":0,"
            + "\"threadUserNanoDuration\":0},\"ruleRunningAfterThisEvent\":true,"
            + "\"eventKey\":{\"value\":1024186770}}",
        message);
  }

  @Test
  public void testBuildRuleEventFinished() throws IOException {
    BuildRule rule = new FakeBuildRule("//fake:rule");
    BuildRuleEvent.Started started = BuildRuleEvent.started(rule, durationTracker);
    started.configure(timestamp - 11, nanoTime - 12, threadUserNanoTime - 13, threadId, buildId);
    BuildRuleEvent.Finished event =
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
            Optional.empty());
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = ObjectMappers.WRITER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,\"status\":\"SUCCESS\",\"cacheResult\":{\"type\":\"MISS\"},"
            + "\"buildRule\":{\"type\":"
            + "\"fake_build_rule\",\"name\":\"//fake:rule\"},"
            + "\"type\":\"BuildRuleFinished\","
            + "\"duration\":{"
            + "\"wallMillisDuration\":11,\"nanoDuration\":12,\"threadUserNanoDuration\":13},"
            + "\"ruleRunningAfterThisEvent\":false,"
            + "\"eventKey\":{\"value\":1024186770},"
            + "\"successTypeName\":\"BUILT_LOCALLY\","
            + "\"ruleKeys\":{\"ruleKey\":{\"hashCode\":\"aaaa\"}}}",
        message);
  }

  @Test
  public void testTestRunEventStarted() throws IOException {
    TestRunEvent.Started event =
        TestRunEvent.started(true, TestSelectorList.empty(), false, ImmutableSet.of());
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = ObjectMappers.WRITER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,\"runAllTests\":true,"
            + "\"eventKey\":{\"value\":256329280},"
            + "\"targetNames\":[],\"type\":\"RunStarted\"}",
        message);
  }

  @Test
  public void testTestRunEventFinished() throws IOException {
    TestRunEvent.Finished event =
        TestRunEvent.finished(
            ImmutableSet.of("target"),
            ImmutableList.of(FakeTestResults.newFailedInstance("Test1")));
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = ObjectMappers.WRITER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,"
            + "\"results\":[{\"testCases\":[{\"testCaseName\":\"Test1\",\"testResults\":[{"
            + "\"testCaseName\":\"Test1\",\"type\":\"FAILURE\",\"time\":0}],"
            + "\"failureCount\":1,\"skippedCount\":0,\"totalTime\":0,"
            + "\"success\":false}],"
            + "\"failureCount\":1,\"contacts\":[],\"labels\":[],"
            + "\"dependenciesPassTheirTests\":true,\"sequenceNumber\":0,\"totalNumberOfTests\":0,"
            + "\"buildTarget\":{\"shortName\":\"baz\",\"baseName\":\"//foo/bar\","
            + "\"flavor\":\"\"},"
            + "\"success\":false}],\"type\":\"RunComplete\", \"eventKey\":"
            + "{\"value\":-624576559}}",
        message);
  }

  @Test
  public void testExternalTestRunEventStarted() throws Exception {
    ExternalTestRunEvent.Started event =
        ExternalTestRunEvent.started(
            /* isRunAllTests */ true,
            TestSelectorList.empty(), /* shouldExplainTestSelectorList */
            false,
            ImmutableSet.of());
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = ObjectMappers.WRITER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,\"runAllTests\":true,"
            + "\"eventKey\":{\"value\":-181996491},"
            + "\"targetNames\":[],\"type\":\"ExternalTestRunStarted\"}",
        message);
  }

  @Test
  public void testExternalTestRunEventFinished() throws Exception {
    ExternalTestRunEvent.Finished event =
        ExternalTestRunEvent.finished(ImmutableSet.of(), /* exitCode */ ExitCode.SUCCESS);
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = ObjectMappers.WRITER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,"
            + "\"eventKey\":{\"value\":-181996491},"
            + "\"type\":\"ExternalTestRunFinished\",\"exitCode\":0}",
        message);
  }

  @Test
  public void testExternalTestSpecCalculationEventStarted() throws Exception {
    ExternalTestSpecCalculationEvent.Started event =
        ExternalTestSpecCalculationEvent.started(BuildTargetFactory.newInstance("//example:app"));
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = ObjectMappers.WRITER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,"
            + "\"eventKey\":{\"value\":-1925481175},"
            + "\"type\":\"ExternalTestSpecCalculationStarted\"}",
        message);
  }

  @Test
  public void testExternalTestSpecCalculationEventFinished() throws Exception {
    ExternalTestSpecCalculationEvent.Finished event =
        ExternalTestSpecCalculationEvent.finished(BuildTargetFactory.newInstance("//example:app"));
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = ObjectMappers.WRITER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,"
            + "\"eventKey\":{\"value\":-1925481175},"
            + "\"type\":\"ExternalTestSpecCalculationFinished\"}",
        message);
  }

  @Test
  public void testIndividualTestEventStarted() throws IOException {
    IndividualTestEvent.Started event = IndividualTestEvent.started(ImmutableList.of(""));
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = ObjectMappers.WRITER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,\"type\":\"AwaitingResults\",\"eventKey\":{\"value\":-594614447}}", message);
  }

  @Test
  public void testIndividualTestEventFinished() throws IOException {
    IndividualTestEvent.Finished event =
        IndividualTestEvent.finished(
            ImmutableList.of(), FakeTestResults.newFailedInstance("Test1"));
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = ObjectMappers.WRITER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,\"eventKey\":{\"value\":-594614477},"
            + "\"results\":{\"testCases\":[{\"testCaseName\":\"Test1\",\"testResults\":[{"
            + "\"testCaseName\":\"Test1\",\"type\":\"FAILURE\",\"time\":0}],"
            + "\"failureCount\":1,\"skippedCount\":0,\"totalTime\":0,"
            + "\"success\":false}],"
            + "\"failureCount\":1,\"contacts\":[],\"labels\":[],"
            + "\"dependenciesPassTheirTests\":true,\"sequenceNumber\":0,\"totalNumberOfTests\":0,"
            + "\"buildTarget\":{\"shortName\":\"baz\",\"baseName\":\"//foo/bar\","
            + "\"flavor\":\"\"},"
            + "\"success\":false},\"type\":\"ResultsAvailable\"}",
        message);
  }

  @Test
  public void testSimplePerfEvent() throws IOException {
    SimplePerfEvent.Started event =
        SimplePerfEvent.started(PerfEventId.of("PerfId"), "value", "Some value");
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = ObjectMappers.WRITER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,"
            + "\"eventKey\":{\"value\":4242},\"eventId\":\"PerfId\",\"eventType\":\"STARTED\","
            + "\"eventInfo\":{\"value\":\"Some value\"},\"type\":\"PerfEvent.PerfId.Started\"}",
        message);
  }

  private void assertJsonEquals(String expected, String actual) {
    String commonHeader =
        String.format(
            "\"timestamp\":%d,\"nanoTime\":%d,\"threadUserNanoTime\":%d,"
                + "\"threadId\":%d,\"buildId\":\"%s\"",
            timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    assertThat(actual, new JsonMatcher(String.format(expected, commonHeader)));
  }
}
