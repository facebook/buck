/*
 * Copyright 2014-present Facebook, Inc.
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

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class FileSerializationEventBusListenerTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Test
  public void shouldSerializeEventsToFile() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_project", temporaryFolder);
    workspace.setUp();

    File eventsOutputFile = workspace.getFile("events.json");

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        ":something",
        "--output-events-to-file",
        eventsOutputFile.getAbsolutePath());
    result.assertSuccess();

    List<String> lines = Files.readAllLines(eventsOutputFile.toPath(), StandardCharsets.UTF_8);
    assertFalse(
        "The events output file should not be empty",
        lines.isEmpty());
    assertThat(
        "An event should have something JSON-like in it",
        lines.get(0),
        containsString("\"timestamp\":"));
  }
}
