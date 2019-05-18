/*
 * Copyright 2019-present Facebook, Inc.
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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.ActionGraphSerializer.ActionGraphData;
import com.facebook.buck.cli.ImmutableActionGraphData;
import com.facebook.buck.event.listener.FileSerializationOutputRuleDepsListener.RuleExecutionTimeData;
import com.facebook.buck.event.listener.ImmutableRuleExecutionTimeData;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;

public class FileSerializationOutputRuleDepsListenerTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void shouldSerializeRulesToFiles() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", temporaryFolder);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckBuild("--no-cache", "--output-rule-deps-to-file", "//:foo");
    result.assertSuccess();

    Path simulatorDir = workspace.resolve(workspace.getBuckPaths().getSimulatorDir());

    String actionGraphFileContent =
        workspace.getFileContents(simulatorDir.resolve("action_graph.json"));
    Map<String, List<ActionGraphData>> actionGraphData =
        Stream.of(actionGraphFileContent.split(System.lineSeparator()))
            .map(line -> convertToObject(line, ImmutableActionGraphData.class))
            .collect(Collectors.groupingBy(ActionGraphData::getTargetId));

    String ruleExecutionTimeFileContent =
        workspace.getFileContents(simulatorDir.resolve("rule_exec_time.json"));
    Map<String, List<RuleExecutionTimeData>> executionTime =
        Stream.of(ruleExecutionTimeFileContent.split(System.lineSeparator()))
            .map(line -> convertToObject(line, ImmutableRuleExecutionTimeData.class))
            .collect(Collectors.groupingBy(RuleExecutionTimeData::getTargetId));

    assertEquals(2, actionGraphData.size());
    assertEquals(2, executionTime.size());
    assertRuleData(actionGraphData, executionTime, "//:bar", "genrule");
    assertRuleData(actionGraphData, executionTime, "//:foo", "genrule", "//:bar");
  }

  private void assertRuleData(
      Map<String, List<ActionGraphData>> actionGraphData,
      Map<String, List<RuleExecutionTimeData>> executionTime,
      String expectedTarget,
      String expectedRuleType,
      String... expectedDependencies) {
    ActionGraphData graphData = Iterables.getOnlyElement(actionGraphData.get(expectedTarget));
    assertThat(graphData.getRuleType(), equalTo(expectedRuleType));
    assertThat(graphData.getBuildDeps(), containsInAnyOrder(expectedDependencies));
    assertTrue(graphData.getRuntimeDeps().isEmpty());

    RuleExecutionTimeData ruleExecutionTimeData =
        Iterables.getOnlyElement(executionTime.get(expectedTarget));
    assertThat(ruleExecutionTimeData.getTargetId(), equalTo(expectedTarget));
    assertThat(ruleExecutionTimeData.getElapsedTimeMs(), greaterThan(0L));
  }

  private <T> T convertToObject(String line, Class<T> clazz) {
    try {
      return ObjectMappers.readValue(line, clazz);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
