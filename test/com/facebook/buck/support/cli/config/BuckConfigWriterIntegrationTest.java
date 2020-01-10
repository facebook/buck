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

package com.facebook.buck.support.cli.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class BuckConfigWriterIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  @Parameters({"query", "build"})
  public void writesConfigToDiskOnCommand(String command) throws IOException, InterruptedException {

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "log", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(command, "-c", "foo.bar=baz", "//:foo");

    result.assertSuccess();
    Path logDir =
        Files.find(
                workspace.resolve(workspace.getBuckPaths().getLogDir()),
                1,
                (p, attrs) ->
                    attrs.isDirectory() && p.getFileName().toString().contains(command + "command"))
            .findFirst()
            .get();
    Path configPath = logDir.resolve("buckconfig.json");

    assertTrue(Files.exists(configPath));
    try (Reader reader = Files.newBufferedReader(configPath)) {
      JsonNode js = ObjectMappers.READER.readTree(reader);
      assertEquals("baz", js.get("settings").get("foo").get("bar").textValue());
    }
  }
}
