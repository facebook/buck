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
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.BuildRuleKeys;
import com.facebook.buck.rules.BuildRuleStatus;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.IndividualTestEvent;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.TestRunEvent;
import com.facebook.buck.test.FakeTestResults;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.testutil.JsonMatcher;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.DefaultClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.Random;

public class EventSerializationTest {

  // The hardcoded strings depend on this being an vanilla mapper.

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private long timestamp;
  private long nanoTime;
  private long threadUserNanoTime;
  private long threadId;
  private BuildId buildId;

  @Before
  public void setUp() {
    Clock clock = new DefaultClock();
    timestamp = clock.currentTimeMillis();
    nanoTime = clock.nanoTime();
    // Not using real value as not all JVMs will support thread user time.
    threadUserNanoTime = new Random().nextLong();
    threadId = 0;
    buildId = new BuildId("Test");
    EventKey.setSequenceValueForTest(4242L);
  }

  @Test
  public void testConsoleEvent() throws IOException {
    ConsoleEvent event = ConsoleEvent.severe("Something happened");
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = MAPPER.writeValueAsString(event);
    assertJsonEquals("{%s,\"type\":\"ConsoleEvent\",\"eventKey\":{\"value\":4242}," +
        "\"level\": {\"name\":\"SEVERE\",\"resourceBundleName\":" +
        "\"sun.util.logging.resources.logging\",\"localizedName\":\"SEVERE\"}," +
        " \"message\":\"Something happened\"}", message);
  }

  @Test
  public void testProjectGenerationEventFinished() throws IOException {
    ProjectGenerationEvent.Finished event = ProjectGenerationEvent.finished();
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = MAPPER.writeValueAsString(event);
    assertJsonEquals("{%s,\"type\":\"ProjectGenerationFinished\"," +
        "\"eventKey\":{\"value\":4242}}", message);
  }

  @Test
  public void testProjectGenerationEventStarted() throws IOException {
    ProjectGenerationEvent.Started event = ProjectGenerationEvent.started();
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = MAPPER.writeValueAsString(event);
    assertJsonEquals("{%s,\"type\":\"ProjectGenerationStarted\"," +
        "\"eventKey\":{\"value\":4242}}", message);
  }

  @Test
  public void testParseEventStarted() throws IOException {
    ParseEvent.Started event = ParseEvent.started(ImmutableList.of());
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = MAPPER.writeValueAsString(event);
    assertJsonEquals("{%s,\"buildTargets\":[],\"type\":\"ParseStarted\"," +
        "\"eventKey\":{\"value\":4242}}", message);
  }

  @Test
  public void testParseEventFinished() throws IOException {
    ParseEvent.Started started = ParseEvent.started(ImmutableList.of(
            BuildTargetFactory.newInstance("//base:short#flv")));
    ParseEvent.Finished event = ParseEvent.finished(started, Optional.empty());
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = MAPPER.writeValueAsString(event);
    assertJsonEquals("{%s," +
        "\"buildTargets\":[{\"cell\":{\"present\":false},\"baseName\":\"//base\"," +
        "\"shortName\":\"short\",\"flavor\":\"flv\"}],\"type\":\"ParseFinished\"," +
        "\"eventKey\":{\"value\":4242}}", message);
  }

  @Test
  public void testBuildEventStarted() throws IOException {
    BuildEvent.Started event = BuildEvent.started(ImmutableSet.of("//base:short"));
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = MAPPER.writeValueAsString(event);
    assertJsonEquals("{%s,\"eventKey\":{\"value\":4242}," +
            "\"buildArgs\":[\"//base:short\"], \"distributedBuild\":false," +
            "\"type\":\"BuildStarted\"}",
        message);
  }

  @Test
  public void testBuildEventFinished() throws IOException {
    BuildEvent.Finished event = BuildEvent.finished(
        BuildEvent.started(ImmutableSet.of("//base:short")),
        0);
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = MAPPER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,\"eventKey\":{\"value\":4242}," +
        "\"buildArgs\":[\"//base:short\"], \"exitCode\":0,\"type\":\"BuildFinished\"}",
        message);
  }

  @Test
  public void testBuildRuleEventStarted() throws IOException {
    BuildRule rule = FakeBuildRule.newEmptyInstance("//fake:rule");
    BuildRuleEvent.Started event = BuildRuleEvent.started(rule);
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = MAPPER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,\"buildRule\":{\"type\":\"fake_build_rule\",\"name\":\"//fake:rule\"}," +
        "\"type\":\"BuildRuleStarted\",\"ruleRunningAfterThisEvent\":true," +
        "\"eventKey\":{\"value\":1024186770}}",
        message);
  }

  @Test
  public void testBuildRuleEventFinished() throws IOException {
    BuildRule rule = FakeBuildRule.newEmptyInstance("//fake:rule");
    BuildRuleEvent.Finished event =
        BuildRuleEvent.finished(
            rule,
            BuildRuleKeys.of(new RuleKey("aaaa")),
            BuildRuleStatus.SUCCESS,
            CacheResult.miss(),
            Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = MAPPER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,\"status\":\"SUCCESS\",\"cacheResult\":{\"type\":\"MISS\",\"cacheSource\":{" +
            "\"present\":false},\"cacheError\":{\"present\":false}," +
            "\"metadata\":{\"present\":false},\"artifactSizeBytes\":{\"present\":false}}," +
            "\"buildRule\":{\"type\":" +
            "\"fake_build_rule\",\"name\":\"//fake:rule\"}," +
            "\"type\":\"BuildRuleFinished\",\"ruleRunningAfterThisEvent\":false," +
            "\"eventKey\":{\"value\":1024186770}," +
            "\"ruleKeys\":{\"ruleKey\":{\"hashCode\":\"aaaa\"}," +
            "\"inputRuleKey\":{\"present\":false}," +
            "\"depFileRuleKey\":{\"present\":false}," +
            "\"manifestRuleKey\":{\"present\":false}}," +
            "\"outputHash\":{\"present\":false}},",
        message);
  }

  @Test
  public void testTestRunEventStarted() throws IOException {
    TestRunEvent.Started event = TestRunEvent.started(
        true, TestSelectorList.empty(), false, ImmutableSet.of());
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = MAPPER.writeValueAsString(event);
    assertJsonEquals("{%s,\"runAllTests\":true," +
        "\"eventKey\":{\"value\":256329280}," +
        "\"targetNames\":[],\"type\":\"RunStarted\"}", message);
  }

  @Test
  public void testTestRunEventFinished() throws IOException {
    TestRunEvent.Finished event = TestRunEvent.finished(
        ImmutableSet.of("target"),
        ImmutableList.of(FakeTestResults.newFailedInstance("Test1")));
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = MAPPER.writeValueAsString(event);
    assertJsonEquals("{%s," +
        "\"results\":[{\"testCases\":[{\"testCaseName\":\"Test1\",\"testResults\":[{\"testName\":" +
        "null,\"testCaseName\":\"Test1\",\"type\":\"FAILURE\",\"time\":0,\"message\":null," +
        "\"stacktrace\":null,\"stdOut\":null," +
        "\"stdErr\":null}],\"failureCount\":1,\"skippedCount\":0,\"totalTime\":0," +
        "\"success\":false}]," +
        "\"failureCount\":1,\"contacts\":[],\"labels\":[]," +
        "\"dependenciesPassTheirTests\":true,\"sequenceNumber\":0,\"totalNumberOfTests\":0," +
        "\"buildTarget\":{\"shortName\":\"baz\",\"baseName\":\"//foo/bar\"," +
        "\"cell\":{\"present\":false},\"flavor\":\"\"}," +
        "\"success\":false}],\"type\":\"RunComplete\", \"eventKey\":" +
        "{\"value\":-624576559}}",
        message);
  }

  @Test
  public void testIndividualTestEventStarted() throws IOException {
    IndividualTestEvent.Started event = IndividualTestEvent.started(ImmutableList.of(""));
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = MAPPER.writeValueAsString(event);
    assertJsonEquals("{%s,\"type\":\"AwaitingResults\",\"eventKey\":{\"value\":-594614447}}",
        message);
  }

  @Test
  public void testIndividualTestEventFinished() throws IOException {
    IndividualTestEvent.Finished event = IndividualTestEvent.finished(ImmutableList.of(),
        FakeTestResults.newFailedInstance("Test1"));
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = MAPPER.writeValueAsString(event);
    assertJsonEquals("{%s,\"eventKey\":{\"value\":-594614477}," +
        "\"results\":{\"testCases\":[{\"testCaseName\":\"Test1\",\"testResults\":[{\"testName\"" +
        ":null,\"testCaseName\":\"Test1\",\"type\":\"FAILURE\",\"time\":0,\"message\":null," +
        "\"stacktrace\":null,\"stdOut\":null," +
        "\"stdErr\":null}],\"failureCount\":1,\"skippedCount\":0,\"totalTime\":0," +
        "\"success\":false}]," +
        "\"failureCount\":1,\"contacts\":[],\"labels\":[]," +
        "\"dependenciesPassTheirTests\":true,\"sequenceNumber\":0,\"totalNumberOfTests\":0," +
        "\"buildTarget\":{\"shortName\":\"baz\",\"baseName\":\"//foo/bar\"," +
        "\"cell\":{\"present\":false},\"flavor\":\"\"}," +
        "\"success\":false},\"type\":\"ResultsAvailable\"}", message);
  }


  @Test
  public void testSimplePerfEvent() throws IOException {
    SimplePerfEvent.Started event = SimplePerfEvent.started(
        PerfEventId.of("PerfId"),
        "value", Optional.of(BuildTargetFactory.newInstance("//:fake")));
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = MAPPER.writeValueAsString(event);
    assertJsonEquals("{%s," +
            "\"eventKey\":{\"value\":4242},\"eventId\":\"PerfId\",\"eventType\":\"STARTED\"," +
            "\"eventInfo\":{\"value\":{\"present\":true}},\"type\":\"PerfEventPerfIdStarted\"}",
        message);
  }

  private void assertJsonEquals(String expected, String actual) throws IOException {
    String commonHeader = String.format(
        "\"timestamp\":%d,\"nanoTime\":%d,\"threadUserNanoTime\":%d," +
            "\"threadId\":%d,\"buildId\":\"%s\"",
        timestamp,
        nanoTime,
        threadUserNanoTime,
        threadId,
        buildId);
    assertThat(actual, new JsonMatcher(String.format(expected, commonHeader)));
  }
}
