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

import com.facebook.buck.java.JavaLibraryDescription;
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
import com.facebook.buck.rules.BuildRuleSuccess;
import com.facebook.buck.rules.CacheResult;
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

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

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
  }

  @Test
  public void testParseEventStarted() throws IOException {
    ParseEvent.Started event = ParseEvent.started(ImmutableList.<BuildTarget>of());
    event.configure(timestamp, nanoTime, threadId, buildId);
    String message = new ObjectMapper().writeValueAsString(event);
    assertJsonEquals("{\"timestamp\":%d,\"nanoTime\":%d,\"threadId\":%d,\"buildId\":\"%s\"," +
        "\"buildTargets\":[],\"type\":\"ParseStarted\"}", message);
  }

  @Test
  public void testParseEventFinished() throws IOException {
    ParseEvent.Finished event = ParseEvent.finished(ImmutableList.<BuildTarget>of(
        BuildTarget.builder("//base", "short").addFlavors(ImmutableFlavor.of("flv")).build()),
        Optional.<TargetGraph>absent());
    event.configure(timestamp, nanoTime, threadId, buildId);
    String message = new ObjectMapper().writeValueAsString(event);
    assertJsonEquals("{\"timestamp\":%d,\"nanoTime\":%d,\"threadId\":%d,\"buildId\":\"%s\"," +
        "\"buildTargets\":[{\"repository\":{\"present\":false},\"baseName\":\"//base\"," +
        "\"shortName\":\"short\",\"flavor\":\"flv\"}],\"type\":\"ParseFinished\"}", message);
  }

  @Test
  public void testBuildEventStarted() throws IOException {
    BuildEvent.Started event = BuildEvent.started(ImmutableSet.<BuildTarget>of(
        BuildTarget.builder("//base", "short").build()));
    event.configure(timestamp, nanoTime, threadId, buildId);
    String message = new ObjectMapper().writeValueAsString(event);
    assertJsonEquals("{\"timestamp\":%d,\"nanoTime\":%d,\"threadId\":%d,\"buildId\":\"%s\"," +
        "\"buildTargets\":[{\"repository\":{\"present\":false},\"baseName\":\"//base\"," +
        "\"shortName\":\"short\",\"flavor\":\"\"}],\"type\":\"BuildStarted\"}", message);
  }

  @Test
  public void testBuildEventFinished() throws IOException {
    BuildEvent.Finished event = BuildEvent.finished(ImmutableSet.<BuildTarget>of(
        BuildTarget.builder("//base", "short").build()), 0);
    event.configure(timestamp, nanoTime, threadId, buildId);
    String message = new ObjectMapper().writeValueAsString(event);
    assertJsonEquals("{\"timestamp\":%d,\"nanoTime\":%d,\"threadId\":%d,\"buildId\":\"%s\"," +
        "\"buildTargets\":[{\"repository\":{\"present\":false},\"baseName\":\"//base\"," +
        "\"shortName\":\"short\",\"flavor\":\"\"}],\"exitCode\":0,\"type\":\"BuildFinished\"}",
        message);
  }

  @Test
  public void testBuildRuleEventStarted() throws IOException {
    BuildRuleEvent.Started event = BuildRuleEvent.started(generateFakeBuildRule());
    event.configure(timestamp, nanoTime, threadId, buildId);
    String message = new ObjectMapper().writeValueAsString(event);
    assertJsonEquals("{\"timestamp\":%d,\"nanoTime\":%d,\"threadId\":%d,\"buildId\":\"%s\"," +
        "\"buildRule\":{\"type\":{\"name\":\"java_library\",\"testRule\":false}," +
        "\"name\":\"//fake:rule\"},\"ruleKeySafe\":\"aaaa\",\"type\":\"BuildRuleStarted\"}",
        message);
  }

  @Test
  public void testBuildRuleEventFinished() throws IOException {
    BuildRuleEvent.Finished event = BuildRuleEvent.finished(generateFakeBuildRule(),
        BuildRuleStatus.SUCCESS,
        CacheResult.MISS,
        Optional.<BuildRuleSuccess.Type>absent());
    event.configure(timestamp, nanoTime, threadId, buildId);
    String message = new ObjectMapper().writeValueAsString(event);
    assertJsonEquals("{\"timestamp\":%d,\"nanoTime\":%d,\"threadId\":%d,\"buildId\":\"%s\"," +
        "\"status\":\"SUCCESS\",\"cacheResult\":\"MISS\",\"buildRule\":{\"type\":" +
        "{\"name\":\"java_library\",\"testRule\":false},\"name\":\"//fake:rule\"}," +
        "\"ruleKeySafe\":\"aaaa\",\"type\":\"BuildRuleFinished\"}", message);
  }

  @Test
  public void testTestRunEventStarted() throws IOException {
    TestRunEvent.Started event = TestRunEvent.started(
        true, TestSelectorList.empty(), false, ImmutableSet.<String>of());
    event.configure(timestamp, nanoTime, threadId, buildId);
    String message = new ObjectMapper().writeValueAsString(event);
    assertJsonEquals("{\"timestamp\":%d,\"nanoTime\":%d,\"threadId\":%d,\"buildId\":\"%s\"," +
        "\"runAllTests\":true," +
        "\"targetNames\":[],\"type\":\"RunStarted\"}", message);
  }

  @Test
  public void testTestRunEventFinished() throws IOException {
    TestRunEvent.Finished event = TestRunEvent.finished(ImmutableSet.of("target"),
        ImmutableList.of(generateFakeTestResults()));
    event.configure(timestamp, nanoTime, threadId, buildId);
    String message = new ObjectMapper().writeValueAsString(event);
    assertJsonEquals("{\"timestamp\":%d,\"nanoTime\":%d,\"threadId\":%d,\"buildId\":\"%s\",\"" +
        "results\":[{\"testCases\":[{\"testCaseName\":\"Test1\",\"testResults\":[{\"testName\":" +
        "null,\"type\":\"FAILURE\",\"time\":0,\"message\":null," +
        "\"stacktrace\":null,\"stdOut\":null," +
        "\"stdErr\":null}],\"failureCount\":1,\"totalTime\":0,\"success\":false}]," +
        "\"failureCount\":1,\"contacts\":[],\"labels\":[]," +
        "\"dependenciesPassTheirTests\":true,\"success\":false}]," +
        "\"type\":\"RunComplete\"}", message);
  }

  @Test
  public void testIndividualTestEventStarted() throws IOException {
    IndividualTestEvent.Started event = IndividualTestEvent.started(ImmutableList.of(""));
    event.configure(timestamp, nanoTime, threadId, buildId);
    String message = new ObjectMapper().writeValueAsString(event);
    assertJsonEquals("{\"timestamp\":%d,\"nanoTime\":%d,\"threadId\":%d,\"buildId\":\"%s\"," +
        "\"type\":\"AwaitingResults\"}", message);
  }

  @Test
  public void testIndividualTestEventFinished() throws IOException {
    IndividualTestEvent.Finished event = IndividualTestEvent.finished(ImmutableList.<String>of(),
        generateFakeTestResults());
    event.configure(timestamp, nanoTime, threadId, buildId);
    String message = new ObjectMapper().writeValueAsString(event);
    assertJsonEquals("{\"timestamp\":%d,\"nanoTime\":%d,\"threadId\":%d,\"buildId\":\"%s\"," +
        "\"results\":{\"testCases\":[{\"testCaseName\":\"Test1\",\"testResults\":[{\"testName\"" +
        ":null,\"type\":\"FAILURE\",\"time\":0,\"message\":null," +
        "\"stacktrace\":null,\"stdOut\":null," +
        "\"stdErr\":null}],\"failureCount\":1,\"totalTime\":0,\"success\":false}]," +
        "\"failureCount\":1,\"contacts\":[],\"labels\":[]," +
        "\"dependenciesPassTheirTests\":true,\"success\":false}," +
        "\"type\":\"ResultsAvailable\"}", message);
  }

  private BuildRule generateFakeBuildRule() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//fake:rule");
    FakeBuildRule result = new FakeBuildRule(
        JavaLibraryDescription.TYPE,
        buildTarget,
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSortedSet.<BuildRule>of()
    );
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

  private void assertJsonEquals(String expected, String actual) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    JsonFactory factory = mapper.getJsonFactory();
    JsonParser jsonParser = factory.createJsonParser(
        String.format(expected, timestamp, nanoTime, threadId, buildId));
    JsonNode expectedObject = mapper.readTree(jsonParser);
    jsonParser = factory.createJsonParser(actual);
    JsonNode actualObject = mapper.readTree(jsonParser);
    assertEquals(expectedObject, actualObject);
  }
}
