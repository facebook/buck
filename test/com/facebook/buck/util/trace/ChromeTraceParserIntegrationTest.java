/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.util.trace;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ChromeTraceParserIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectFilesystem projectFilesystem;
  private ChromeTraceParser parser;
  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws InterruptedException, IOException {
    projectFilesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    parser = new ChromeTraceParser(projectFilesystem);
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "chrome_trace_parser", tmp);
    workspace.setUp();
  }

  @Test
  public void shouldNotCrashOnEmptyTrace() throws IOException {
    Set<ChromeTraceParser.ChromeTraceEventMatcher<?>> matchers =
        ImmutableSet.of(ChromeTraceParser.COMMAND);
    Map<ChromeTraceParser.ChromeTraceEventMatcher<?>, Object> results =
        parser.parse(Paths.get("empty_trace.json"), matchers);
    assertEquals(ImmutableMap.of(), results);
  }

  @Test
  public void canParseCommandFromTrace() throws IOException {
    Set<ChromeTraceParser.ChromeTraceEventMatcher<?>> matchers =
        ImmutableSet.of(ChromeTraceParser.COMMAND);
    Map<ChromeTraceParser.ChromeTraceEventMatcher<?>, Object> results =
        parser.parse(Paths.get("build_trace_with_command.json"), matchers);
    String expectedQuery = "buck query deps(fb4a, 1)";
    assertEquals(ImmutableMap.of(ChromeTraceParser.COMMAND, expectedQuery), results);
    assertEquals(
        Optional.of(expectedQuery),
        ChromeTraceParser.getResultForMatcher(ChromeTraceParser.COMMAND, results));
  }

  @Test
  public void canApplyMultipleMatchersToTrace() throws IOException {
    @SuppressWarnings("unchecked")
    ChromeTraceParser.ChromeTraceEventMatcher<Integer> meaningOfLifeMatcher =
        (json, name) -> {
          if (!"meaningOfLife".equals(name)) {
            return Optional.empty();
          }

          Object argsEl = json.get("args");
          if (!(argsEl instanceof Map)
              || ((Map<String, Object>) argsEl).get("is") == null
              || !(((Map<String, Object>) argsEl).get("is") instanceof Integer)) {
            return Optional.empty();
          }

          int value = (Integer) ((Map<String, Object>) argsEl).get("is");
          return Optional.of(value);
        };

    Set<ChromeTraceParser.ChromeTraceEventMatcher<?>> matchers =
        ImmutableSet.of(ChromeTraceParser.COMMAND, meaningOfLifeMatcher);
    Map<ChromeTraceParser.ChromeTraceEventMatcher<?>, Object> results =
        parser.parse(Paths.get("build_trace_with_multiple_features.json"), matchers);
    assertEquals(
        ImmutableMap.of(
            ChromeTraceParser.COMMAND, "buck query deps(fb4a, 1)", meaningOfLifeMatcher, 42),
        results);
  }
}
