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

package com.facebook.buck.rage;

import static org.junit.Assert.assertThat;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ObjectMappers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.util.Map;

public class BuildLogHelperIntegrationTest {

  @Rule
  public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void findsLogFiles() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "build_log_helper", temporaryFolder);
    workspace.setUp();

    Cell cell = workspace.asCell();
    ProjectFilesystem filesystem = cell.getFilesystem();
    BuildLogHelper buildLogHelper = new BuildLogHelper(
        filesystem,
        ObjectMappers.newDefaultInstance());

    ImmutableList<BuildLogEntry> buildLogs = buildLogHelper.getBuildLogs();

    ImmutableMap<BuildId, BuildLogEntry> idToLogMap = FluentIterable.from(
        buildLogs)
        .uniqueIndex(
            input -> input.getBuildId().get());
    Map<BuildId, String> buildIdToCommandMap = Maps.transformValues(
        idToLogMap,
        input -> input.getCommandArgs().get());

    assertThat(
        buildIdToCommandMap,
        Matchers.equalTo(
            ImmutableMap.of(
                new BuildId("ac8bd626-6137-4747-84dd-5d4f215c876c"),
                "build, buck",

                new BuildId("d09893d5-b11e-4e3f-a5bf-70c60a06896e"),
                "autodeps")
        ));
  }
}
