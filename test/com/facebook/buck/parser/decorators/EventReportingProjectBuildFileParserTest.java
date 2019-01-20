/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.parser.decorators;

import static org.junit.Assert.*;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.json.ProjectBuildFileParseEvents;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.skylark.io.GlobSpecWithResult;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.eventbus.Subscribe;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class EventReportingProjectBuildFileParserTest {

  private static final Path SOME_PATH = Paths.get("some_path");
  private TestProjectBuildFileParser delegate;

  private EventReportingProjectBuildFileParser parser;
  private ProjectBuildFileParseEventListener listener;
  private BuildFileManifest allRulesAndMetadata;

  private static class ProjectBuildFileParseEventListener {
    private int startedCount;
    private int finishedCount;

    @Subscribe
    public void onProjectBuildFileParseStarted(
        @SuppressWarnings("unused") ProjectBuildFileParseEvents.Started started) {
      startedCount += 1;
    }

    @Subscribe
    public void onProjectBuildFileParseFinished(
        @SuppressWarnings("unused") ProjectBuildFileParseEvents.Finished finished) {
      finishedCount += 1;
    }

    public int getStartedCount() {
      return startedCount;
    }

    public boolean isStarted() {
      return startedCount > 0;
    }

    public boolean isFinished() {
      return finishedCount > 0;
    }
  }

  private class TestProjectBuildFileParser implements ProjectBuildFileParser {

    private boolean isProfileReported;
    private boolean isClosed;

    @Override
    public BuildFileManifest getBuildFileManifest(Path buildFile) {
      return allRulesAndMetadata;
    }

    @Override
    public void reportProfile() {
      isProfileReported = true;
    }

    @Override
    public ImmutableSortedSet<String> getIncludedFiles(Path buildFile)
        throws BuildFileParseException {
      return ImmutableSortedSet.of();
    }

    @Override
    public boolean globResultsMatchCurrentState(
        Path buildFile, ImmutableList<GlobSpecWithResult> existingGlobsWithResults) {
      return false;
    }

    @Override
    public void close() {
      isClosed = true;
    }

    public boolean isProfileReported() {
      return isProfileReported;
    }
  }

  @Before
  public void setUp() {
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    delegate = new TestProjectBuildFileParser();
    listener = new ProjectBuildFileParseEventListener();
    eventBus.register(listener);
    parser = EventReportingProjectBuildFileParser.of(delegate, eventBus);
  }

  @Test
  public void startEventIsRecordedOnlyOnce() throws Exception {
    assertFalse(listener.isStarted());
    parser.getBuildFileManifest(SOME_PATH);
    parser.getBuildFileManifest(SOME_PATH);
    assertTrue(listener.isStarted());
    assertThat(listener.getStartedCount(), Matchers.is(1));
  }

  @Test
  public void getBuildFileManifestFiresStartEvent() throws Exception {
    assertFalse(listener.isStarted());
    parser.getBuildFileManifest(SOME_PATH);
    assertTrue(listener.isStarted());
  }

  @Test
  public void getBuildFileManifestReturnsUnderlyingRules() throws Exception {
    allRulesAndMetadata =
        BuildFileManifest.of(
            ImmutableMap.of(),
            ImmutableSortedSet.of(),
            ImmutableMap.of(),
            Optional.empty(),
            ImmutableList.of());
    assertSame(allRulesAndMetadata, parser.getBuildFileManifest(SOME_PATH));
  }

  @Test
  public void reportProfileDelegates() throws Exception {
    assertFalse(delegate.isProfileReported());
    parser.reportProfile();
    assertTrue(delegate.isProfileReported());
  }

  @Test
  public void closeReportsFinishedEvent() throws Exception {
    parser.getBuildFileManifest(SOME_PATH);
    assertFalse(listener.isFinished());
    parser.close();
    assertTrue(listener.isFinished());
  }

  @Test
  public void closeDoesNotFireFinishedEventWithoutStart() throws Exception {
    assertFalse(listener.isFinished());
    parser.close();
    assertFalse(listener.isFinished());
  }

  @Test
  public void closeClosesUnderlyingParser() throws Exception {
    assertFalse(delegate.isClosed);
    parser.close();
    assertTrue(delegate.isClosed);
  }
}
