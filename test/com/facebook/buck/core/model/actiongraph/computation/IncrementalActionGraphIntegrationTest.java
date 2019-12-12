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

package com.facebook.buck.core.model.actiongraph.computation;

import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.TestWithBuckd;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.log.LogFormatter;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestContext;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class IncrementalActionGraphIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public TestWithBuckd testWithBuckd = new TestWithBuckd(tmp);

  private ProjectWorkspace workspace;
  private Logger incrementalActionGraphGeneratorLogger;

  @Before
  public void setUp() throws IOException {
    incrementalActionGraphGeneratorLogger = Logger.get(IncrementalActionGraphGenerator.class);
    incrementalActionGraphGeneratorLogger.setLevel(Level.FINER);
    Path fullLogFilePath = tmp.getRoot().resolve(getLogFilePath());
    Files.createDirectories(fullLogFilePath.getParent());
    FileHandler handler = new FileHandler(fullLogFilePath.toString());
    handler.setFormatter(new LogFormatter());
    incrementalActionGraphGeneratorLogger.addHandler(handler);

    workspace =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "incremental_action_graph", tmp);
    workspace.setUp();
  }

  @Test
  public void incrementalActionGraphWorksWithConfigTargetNodes() throws IOException {
    // When --target-platforms is used, config nodes appear in the target graph
    // The incremental action graph must be able to be built, taking into account the fact
    // those node don't appear in previous action graphs (only in target graphs).
    try (TestContext context = new TestContext()) {

      // First populate the cache
      workspace
          .runBuckdCommand(context, "build", "--target-platforms=//:platform", "//:thing1")
          .assertSuccess();

      // The new action graph must be built with no problems
      workspace
          .runBuckdCommand(context, "build", "--target-platforms=//:platform", "//:thing2")
          .assertSuccess();

      // make sure we attempted to incrementally create the action graph
      String logs = workspace.getFileContents(getLogFilePath());
      String expectedMsg =
          "[com.facebook.buck.core.model.actiongraph.computation.IncrementalActionGraphGenerator] reused 1 of 3 build rules";

      assertTrue(
          "Incremental action graph generator should have reused the shared dep",
          logs.contains(expectedMsg));
    }
  }

  private Path getLogFilePath() {
    return tmp.getRoot().resolve("buck.test.log");
  }
}
