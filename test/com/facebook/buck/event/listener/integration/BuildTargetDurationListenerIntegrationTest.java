/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.event.listener.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.listener.BuildTargetDurationListener;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Charsets;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Asserts if logs are created as expected. */
public class BuildTargetDurationListenerIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private Path logDir;
  private String targetName;

  private JsonNode readLogFile(Path logDir, final String logFileName) throws IOException {
    File[] logFiles =
        (logDir.toFile()).listFiles(pathname -> pathname.getName().equals(logFileName));
    assertEquals(logFiles.length, 1);
    return ObjectMappers.READER.readTree(
        new String(Files.readAllBytes(logFiles[0].toPath()), Charsets.UTF_8));
  }

  @Before
  public void setUp() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_project", tmp);
    workspace.setUp();
    // Execute test command with critical path analysis enabled.
    workspace
        .runBuckCommand("test", "--all", "--config", "log.critical_path_analysis_enabled=true")
        .assertSuccess();
    targetName = "//test:something";
    // The folder should have only one command.
    logDir = workspace.resolve("buck-out/log/");
  }

  @Test
  public void testOutputForParsingCriticalPath() throws IOException {
    // Parse critical path JSON file and check fields.
    final JsonNode criticalPathRoot =
        readLogFile(logDir, BuildTargetDurationListener.CRITICAL_PATH_FILE_NAME);
    assertTrue(criticalPathRoot.isArray());
    assertEquals(1, criticalPathRoot.size());

    assertTrue(criticalPathRoot.get(0).has("target-name"));
    assertEquals(targetName, criticalPathRoot.get(0).get("target-name").asText());

    assertTrue(criticalPathRoot.get(0).has("target-duration"));
    assertTrue(criticalPathRoot.get(0).get("target-duration").isIntegralNumber());

    assertTrue(criticalPathRoot.get(0).has("critical-path-duration"));
    assertTrue(criticalPathRoot.get(0).get("critical-path-duration").isIntegralNumber());

    assertTrue(criticalPathRoot.get(0).has("critical-path"));
    assertTrue(criticalPathRoot.get(0).get("critical-path").isArray());
    assertEquals(1, criticalPathRoot.get(0).get("critical-path").size());
    assertTrue(criticalPathRoot.get(0).get("critical-path").get(0).has("rule-name"));
    assertTrue(criticalPathRoot.get(0).get("critical-path").get(0).has("duration"));
    assertTrue(criticalPathRoot.get(0).get("critical-path").get(0).has("start"));
    assertTrue(criticalPathRoot.get(0).get("critical-path").get(0).has("finish"));
  }

  @Test
  public void testOutputForParsingCriticalPathTrace() throws IOException {
    // Parse critical path trace JSON file and check fields.
    final JsonNode criticalPathTraceRoot =
        readLogFile(logDir, BuildTargetDurationListener.CRITICAL_PATH_TRACE_FILE_NAME);
    assertTrue(criticalPathTraceRoot.isArray());
    // It should contain at least MetaEvent, BeginEvent and EndEvent events.
    assertTrue("At least 3 events are expected", criticalPathTraceRoot.size() >= 3);

    assertTrue(criticalPathTraceRoot.get(0).has("ph"));
    assertEquals("M", criticalPathTraceRoot.get(0).get("ph").asText());
    assertTrue(criticalPathTraceRoot.get(0).has("name"));
    assertEquals("process_name", criticalPathTraceRoot.get(0).get("name").asText());

    assertTrue(criticalPathTraceRoot.get(1).has("ph"));
    assertEquals("B", criticalPathTraceRoot.get(1).get("ph").asText());
    assertTrue(criticalPathTraceRoot.get(1).has("name"));
    assertEquals(targetName, criticalPathTraceRoot.get(1).get("name").asText());

    assertTrue(criticalPathTraceRoot.get(2).has("ph"));
    assertEquals("E", criticalPathTraceRoot.get(2).get("ph").asText());
    assertTrue(criticalPathTraceRoot.get(2).has("name"));
    assertEquals(targetName, criticalPathTraceRoot.get(2).get("name").asText());
  }

  @Test
  public void testOutputForParsingTargetBuildTimes() throws IOException {
    // Parse target builds times JSON file and check fields.
    final JsonNode targetBuildsTimesRoot =
        readLogFile(logDir, BuildTargetDurationListener.TARGETS_BUILD_TIMES_FILE_NAME);
    assertTrue(targetBuildsTimesRoot.isArray());
    assertEquals(1, targetBuildsTimesRoot.size());

    assertTrue(targetBuildsTimesRoot.get(0).has("target-name"));
    assertEquals(targetName, targetBuildsTimesRoot.get(0).get("target-name").asText());

    assertTrue(targetBuildsTimesRoot.get(0).has("target-duration"));
    assertTrue(targetBuildsTimesRoot.get(0).get("target-duration").isIntegralNumber());
    assertNotEquals(0L, targetBuildsTimesRoot.get(0).get("target-duration").longValue());
  }
}
