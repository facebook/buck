/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.event.listener.integration;

import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class CriticalPathEventBusListenerIntegrationTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void shouldSerializeCriticalPathToFile() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("--no-cache", "//:foo");
    result.assertSuccess();

    Path lastBuildCommandLogDir =
        EventBusListenerIntegrationTestsUtils.getLastBuildCommandLogDir(workspace);
    String fileContents =
        workspace.getFileContents(lastBuildCommandLogDir.resolve("critical_path.log"));
    List<CriticalPathItem> criticalPaths = new ArrayList<>();
    for (String s : fileContents.split(System.lineSeparator())) {
      criticalPaths.add(toCriticalPathItem(s));
    }

    assertThat(criticalPaths, Matchers.hasSize(2));
    CriticalPathItem criticalPathItem1 = criticalPaths.get(0);
    CriticalPathItem criticalPathItem2 = criticalPaths.get(1);
    assertCriticalPathItem(criticalPathItem1, "//:bar", "genrule");
    assertCriticalPathItem(
        criticalPathItem2, "//:foo", "genrule", criticalPathItem1.totalElapsedTime);
  }

  private void assertCriticalPathItem(
      CriticalPathItem criticalPathItem, String expectedBuildTargetName, String expectedRuleType) {
    assertCriticalPathItem(criticalPathItem, expectedBuildTargetName, expectedRuleType, 0);
  }

  private void assertCriticalPathItem(
      CriticalPathItem criticalPathItem,
      String expectedBuildTargetName,
      String expectedRuleType,
      long prevTotalTime) {
    assertThat(criticalPathItem.buildTarget, Matchers.equalTo(expectedBuildTargetName));
    assertThat(criticalPathItem.ruleType, Matchers.equalTo(expectedRuleType));
    assertThat(criticalPathItem.elapsedTime, Matchers.greaterThan(0L));
    assertThat(
        criticalPathItem.totalElapsedTime,
        Matchers.equalTo(criticalPathItem.elapsedTime + prevTotalTime));
  }

  private CriticalPathItem toCriticalPathItem(String line) throws IOException {
    CriticalPathItem criticalPath = new CriticalPathItem();
    ObjectMapper mapper = new ObjectMapper();
    JsonNode node = mapper.readTree(line.substring(line.indexOf("{")).trim());

    criticalPath.elapsedTime = node.get("executionTimeInfo").path("executionDurationMs").asLong();
    criticalPath.totalElapsedTime = node.path("totalElapsedTimeMs").asLong();
    criticalPath.ruleType = node.get("type").asText();
    criticalPath.buildTarget = line.substring(0, line.indexOf("{") - 2).trim();
    return criticalPath;
  }

  private static class CriticalPathItem {
    String buildTarget;
    String ruleType;
    long elapsedTime;
    long totalElapsedTime;
  }
}
