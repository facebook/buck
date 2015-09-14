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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleStatus;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.IndividualTestEvent;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestRunEvent;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.DefaultClock;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.HashSet;

public class EventSerializationTest {

  private long timestamp;
  private long nanoTime;
  private long threadId;
  private BuildId buildId;

  @Before
  public void setUp() {
    Clock clock = new DefaultClock();
    timestamp = clock.currentTimeMillis();
    nanoTime = clock.nanoTime();
    threadId = 0;
    buildId = new BuildId("Test");
    EventKey.setSequenceValueForTest(4242L);
  }

  @Test
  public void testParseEventStarted() throws IOException {
    ParseEvent.Started event = ParseEvent.started(ImmutableList.<BuildTarget>of());
    event.configure(timestamp, nanoTime, threadId, buildId);
    String message = new ObjectMapper().writeValueAsString(event);
    assertJsonEquals("{\"timestamp\":%d,\"nanoTime\":%d,\"threadId\":%d,\"buildId\":\"%s\"," +
        "\"buildTargets\":[],\"type\":\"ParseStarted\"," +
        "\"eventKey\":{\"value\":4242}}", message);
  }

  @Test
  public void testParseEventFinished() throws IOException {
    ParseEvent.Started started = ParseEvent.started(ImmutableList.<BuildTarget>of(
            BuildTarget.builder("//base", "short").addFlavors(ImmutableFlavor.of("flv")).build()));
    ParseEvent.Finished event = ParseEvent.finished(started, Optional.<TargetGraph>absent());
    event.configure(timestamp, nanoTime, threadId, buildId);
    String message = new ObjectMapper().writeValueAsString(event);
    assertJsonEquals("{\"timestamp\":%d,\"nanoTime\":%d,\"threadId\":%d,\"buildId\":\"%s\"," +
        "\"buildTargets\":[{\"repository\":{\"present\":false},\"baseName\":\"//base\"," +
        "\"shortName\":\"short\",\"flavor\":\"flv\"}],\"type\":\"ParseFinished\"," +
        "\"eventKey\":{\"value\":4242}}", message);
  }

  @Test
  public void testBuildEventStarted() throws IOException {
    BuildEvent.Started event = BuildEvent.started(ImmutableSet.of("//base:short"));
    event.configure(timestamp, nanoTime, threadId, buildId);
    String message = new ObjectMapper().writeValueAsString(event);
    assertJsonEquals(
        "{\"timestamp\":%d,\"nanoTime\":%d,\"threadId\":%d,\"buildId\":\"%s\"," +
        "\"eventKey\":{\"value\":4242}," +
        "\"buildArgs\":[\"//base:short\"], \"type\":\"BuildStarted\"}",
        message);
  }

  @Test
  public void testBuildEventFinished() throws IOException {
    BuildEvent.Finished event = BuildEvent.finished(
        BuildEvent.started(ImmutableSet.of("//base:short")),
        0);
    event.configure(timestamp, nanoTime, threadId, buildId);
    String message = new ObjectMapper().writeValueAsString(event);
    assertJsonEquals(
        "{\"timestamp\":%d,\"nanoTime\":%d,\"threadId\":%d,\"buildId\":\"%s\"," +
        "\"eventKey\":{\"value\":4242}," +
        "\"buildArgs\":[\"//base:short\"], \"exitCode\":0,\"type\":\"BuildFinished\"}",
        message);
  }

  @Test
  public void testBuildRuleEventStarted() throws IOException {
    BuildRuleEvent.Started event = BuildRuleEvent.started(generateFakeBuildRule());
    event.configure(timestamp, nanoTime, threadId, buildId);
    String message = new ObjectMapper().writeValueAsString(event);
    assertJsonEquals(
        "{\"timestamp\":%d,\"nanoTime\":%d,\"threadId\":%d,\"buildId\":\"%s\"," +
        "\"buildRule\":{\"type\":\"fake_build_rule\",\"name\":\"//fake:rule\"}," +
        "\"ruleKeySafe\":\"aaaa\",\"type\":\"BuildRuleStarted\"," +
        "\"eventKey\":{\"value\":1024186770}}",
        message);
  }

  @Test
  public void testBuildRuleEventFinished() throws IOException {
    BuildRuleEvent.Finished event =
        BuildRuleEvent.finished(
            generateFakeBuildRule(),
            BuildRuleStatus.SUCCESS,
            CacheResult.miss(),
            Optional.<BuildRuleSuccessType>absent(),
            Optional.<HashCode>absent(),
            Optional.<Long>absent());
    event.configure(timestamp, nanoTime, threadId, buildId);
    String message = new ObjectMapper().writeValueAsString(event);
    assertJsonEquals(
        "{\"timestamp\":%d,\"nanoTime\":%d,\"threadId\":%d,\"buildId\":\"%s\"," +
            "\"status\":\"SUCCESS\",\"cacheResult\":{\"type\":\"MISS\",\"cacheSource\":{" +
            "\"present\":false},\"cacheError\":{\"present\":false}," +
            "\"metadata\":{\"present\":false}}, \"buildRule\":{\"type\":" +
            "\"fake_build_rule\",\"name\":\"//fake:rule\"},\"ruleKeySafe\":\"aaaa\"," +
            "\"type\":\"BuildRuleFinished\"," +
            "\"eventKey\":{\"value\":1024186770}}",
        message);
  }

  @Test
  public void testTestRunEventStarted() throws IOException {
    TestRunEvent.Started event = TestRunEvent.started(
        true, TestSelectorList.empty(), false, ImmutableSet.<String>of());
    event.configure(timestamp, nanoTime, threadId, buildId);
    String message = new ObjectMapper().writeValueAsString(event);
    assertJsonEquals("{\"timestamp\":%d,\"nanoTime\":%d,\"threadId\":%d,\"buildId\":\"%s\"," +
        "\"runAllTests\":true," +
        "\"eventKey\":{\"value\":256329280}," +
        "\"targetNames\":[],\"type\":\"RunStarted\"}", message);
  }

  @Test
  public void testTestRunEventFinished() throws IOException {
    TestRunEvent.Finished event = TestRunEvent.finished(
        ImmutableSet.of("target"),
        ImmutableList.of(generateFakeTestResults()));
    event.configure(timestamp, nanoTime, threadId, buildId);
    String message = new ObjectMapper().writeValueAsString(event);
    assertJsonEquals("{\"timestamp\":%d,\"nanoTime\":%d,\"threadId\":%d,\"buildId\":\"%s\",\"" +
        "results\":[{\"testCases\":[{\"testCaseName\":\"Test1\",\"testResults\":[{\"testName\":" +
        "null,\"testCaseName\":\"Test1\",\"type\":\"FAILURE\",\"time\":0,\"message\":null," +
        "\"stacktrace\":null,\"stdOut\":null," +
        "\"stdErr\":null}],\"failureCount\":1,\"skippedCount\":0,\"totalTime\":0," +
        "\"success\":false}]," +
        "\"failureCount\":1,\"contacts\":[],\"labels\":[]," +
        "\"dependenciesPassTheirTests\":true,\"sequenceNumber\":0,\"totalNumberOfTests\":0," +
        "\"buildTarget\":{\"shortName\":\"baz\",\"baseName\":\"//foo/bar\"," +
        "\"repository\":{\"present\":false},\"flavor\":\"\"}," +
        "\"success\":false}],\"type\":\"RunComplete\", \"eventKey\":" +
        "{\"value\":-624576559}}",
        message);
  }

  @Test
  public void testIndividualTestEventStarted() throws IOException {
    IndividualTestEvent.Started event = IndividualTestEvent.started(ImmutableList.of(""));
    event.configure(timestamp, nanoTime, threadId, buildId);
    String message = new ObjectMapper().writeValueAsString(event);
    assertJsonEquals("{\"timestamp\":%d,\"nanoTime\":%d,\"threadId\":%d,\"buildId\":\"%s\"," +
        "\"type\":\"AwaitingResults\",\"eventKey\":{\"value\":-594614447}}",
        message);
  }

  @Test
  public void testIndividualTestEventFinished() throws IOException {
    IndividualTestEvent.Finished event = IndividualTestEvent.finished(ImmutableList.<String>of(),
        generateFakeTestResults());
    event.configure(timestamp, nanoTime, threadId, buildId);
    String message = new ObjectMapper().writeValueAsString(event);
    assertJsonEquals("{\"timestamp\":%d,\"nanoTime\":%d,\"threadId\":%d,\"buildId\":\"%s\"," +
        "\"eventKey\":{\"value\":-594614477}," +
        "\"results\":{\"testCases\":[{\"testCaseName\":\"Test1\",\"testResults\":[{\"testName\"" +
        ":null,\"testCaseName\":\"Test1\",\"type\":\"FAILURE\",\"time\":0,\"message\":null," +
        "\"stacktrace\":null,\"stdOut\":null," +
        "\"stdErr\":null}],\"failureCount\":1,\"skippedCount\":0,\"totalTime\":0," +
        "\"success\":false}]," +
        "\"failureCount\":1,\"contacts\":[],\"labels\":[]," +
        "\"dependenciesPassTheirTests\":true,\"sequenceNumber\":0,\"totalNumberOfTests\":0," +
        "\"buildTarget\":{\"shortName\":\"baz\",\"baseName\":\"//foo/bar\"," +
        "\"repository\":{\"present\":false},\"flavor\":\"\"}," +
        "\"success\":false},\"type\":\"ResultsAvailable\"}", message);
  }


  @Test
  public void testSimplePerfEvent() throws IOException {
    SimplePerfEvent.Started event = SimplePerfEvent.started(
        PerfEventId.of("PerfId"),
        "value", Optional.of(BuildTargetFactory.newInstance("//:fake")));
    event.configure(timestamp, nanoTime, threadId, buildId);
    String message = new ObjectMapper().writeValueAsString(event);
    assertJsonEquals("{\"timestamp\":%d,\"nanoTime\":%d,\"threadId\":%d,\"buildId\":\"%s\"," +
            "\"eventKey\":{\"value\":4242},\"eventId\":\"PerfId\",\"eventType\":\"STARTED\"," +
            "\"eventInfo\":{\"value\":{\"present\":true}},\"type\":\"PerfEventPerfIdStarted\"}",
        message);
  }

  private BuildRule generateFakeBuildRule() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//fake:rule");
    FakeBuildRule result = new FakeBuildRule(
        buildTarget,
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSortedSet.<BuildRule>of());
    result.setRuleKey(new RuleKey("aaaa"));
    return result;
  }

  private TestResults generateFakeTestResults() {
    String testCaseName = "Test1";
    TestResultSummary testResultSummary = new TestResultSummary(
        testCaseName, null, ResultType.FAILURE, 0, null, null, null, null);
    TestCaseSummary testCase = new TestCaseSummary(testCaseName,
        ImmutableList.of(testResultSummary));
    ImmutableList<TestCaseSummary> testCases = ImmutableList.of(testCase);
    return new TestResults(testCases);
  }

  private void matchJsonObjects(String path, JsonNode expected, JsonNode actual) {
    if (expected != null && actual != null && expected.isObject()) {
      assertTrue(actual.isObject());
      HashSet<String> expectedFields = Sets.newHashSet(expected.fieldNames());
      HashSet<String> actualFields = Sets.newHashSet(actual.fieldNames());

      for (String field : expectedFields) {
        assertTrue(
            String.format("Expecting field %s at path %s", field, path),
            actualFields.contains(field));
        matchJsonObjects(path + "/" + field, expected.get(field), actual.get(field));
      }
      assertEquals("Found unexpected fields",
          Sets.newHashSet(), Sets.difference(actualFields, expectedFields));
    }
    assertEquals(
        "At path " + path,
        expected,
        actual);
  }

  private void assertJsonEquals(String expected, String actual) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    JsonFactory factory = mapper.getJsonFactory();
    JsonParser jsonParser = factory.createJsonParser(
        String.format(expected, timestamp, nanoTime, threadId, buildId));
    JsonNode expectedObject = mapper.readTree(jsonParser);
    jsonParser = factory.createJsonParser(actual);
    JsonNode actualObject = mapper.readTree(jsonParser);
    matchJsonObjects("/", expectedObject, actualObject);
    assertEquals(expectedObject, actualObject);
  }
}
