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

import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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

    Path logDir = workspace.getBuckPaths().getLogDir();
    Path criticalPathDestinationDir = getDestinationPath(workspace.resolve(logDir));

    String fileContents =
        workspace.getFileContents(criticalPathDestinationDir.resolve("critical_path.log"));
    List<CriticalPathItem> criticalPaths =
        Stream.of(fileContents.split(System.lineSeparator()))
            // skip header
            .skip(2)
            .map(line -> toCriticalPathItem(line))
            .collect(Collectors.toList());

    assertThat(criticalPaths, Matchers.hasSize(2));
    CriticalPathItem criticalPathItem1 = criticalPaths.get(0);
    CriticalPathItem criticalPathItem2 = criticalPaths.get(1);
    assertCriticalPathItem(criticalPathItem1, "//:bar", "genrule");
    assertCriticalPathItem(
        criticalPathItem2, "//:foo", "genrule", criticalPathItem1.totalElapsedTime);
  }

  private Path getDestinationPath(Path logDir) throws IOException {
    List<Path> pathList =
        Files.list(logDir)
            .filter(Files::isDirectory)
            .filter(dir -> dir.getFileName().toString().contains("buildcommand"))
            .collect(Collectors.toList());
    return Iterables.getOnlyElement(pathList);
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
    assertThat(criticalPathItem.elapsedTimePercent, Matchers.greaterThan(0.));
    assertThat(
        criticalPathItem.totalElapsedTime,
        Matchers.equalTo(criticalPathItem.elapsedTime + prevTotalTime));
  }

  private CriticalPathItem toCriticalPathItem(String line) {
    Scanner scanner = new Scanner(line);
    CriticalPathItem criticalPath = new CriticalPathItem();
    criticalPath.elapsedTime = scanner.nextLong();
    criticalPath.totalElapsedTime = scanner.nextLong();
    criticalPath.elapsedTimePercent = scanner.nextDouble();
    criticalPath.ruleType = scanner.next().trim();
    criticalPath.buildTarget = scanner.next().trim();
    return criticalPath;
  }

  private static class CriticalPathItem {
    String buildTarget;
    String ruleType;
    long elapsedTime;
    double elapsedTimePercent;
    long totalElapsedTime;
  }
}
