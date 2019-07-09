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
package com.facebook.buck.support.fix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.doctor.BuildLogHelper;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class BuckFixSpecParserTest {

  @Rule public TemporaryPaths tempFolder = new TemporaryPaths();

  BuckConfig sampleConfig =
      FakeBuckConfig.builder()
          .setSections(
              ImmutableMap.of(
                  "fix",
                  ImmutableMap.of(
                      "fix_script",
                      "path/to/fixit.sh \"quoted arg\" --fix-spec-path   {fix_spec_path}",
                      "fix_script_contact",
                      "support@example.com",
                      "fix_script_message",
                      "Running '{command}', talk to '{contact}'",
                      "legacy_fix_script",
                      "legacy/script/location")))
          .build();
  FixBuckConfig fixConfig = sampleConfig.getView(FixBuckConfig.class);

  ProjectFilesystem filesystem;
  BuildId buildCommandId = new BuildId("2ef9b523-fc4e-48a3-aa9f-baab9ca36386");
  String buildCommandDir = "2019-05-21_15h18m53s_buildcommand_2ef9b523-fc4e-48a3-aa9f-baab9ca36386";
  String buildCommandTrace = "build.2019-05-21.08-18-53.2ef9b523-fc4e-48a3-aa9f-baab9ca36386.trace";
  String launchCommandDir = "2019-05-21_15h54m33s_launch_f15b0963-e77b-7f2c-281a-992afdc386e8";

  @Before
  public void setUp() {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tempFolder.getRoot());
  }

  @Test
  public void returnsEmptyWhenBuildIdWasNotFound() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "report", tempFolder);
    workspace.setUp();
    filesystem.deleteRecursivelyIfExists(
        filesystem.getBuckPaths().getLogDir().resolve(buildCommandDir));
    assertTrue(filesystem.exists(filesystem.getBuckPaths().getLogDir().resolve(launchCommandDir)));

    BuildLogHelper helper = new BuildLogHelper(filesystem);

    BuckFixSpecParser.FixSpecFailure failure =
        BuckFixSpecParser.parseFromBuildId(helper, fixConfig, buildCommandId, false).getRight();

    assertEquals(BuckFixSpecParser.FixSpecFailure.MISSING, failure);
  }

  @Test
  public void returnsEmptyWhenFetchingByIdButRequiredFieldsAreMissing() throws IOException {
    // A couple of these states return generic "MISSING" errors. This is due to an implementation
    // detail in BuildLogHelper, and may be subject to change later
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "report", tempFolder);
    workspace.setUp();

    Path logDir = filesystem.getBuckPaths().getLogDir().resolve(buildCommandDir);
    Path machineLog = logDir.resolve("buck-machine-log");

    BuildLogHelper helper = new BuildLogHelper(filesystem);

    assertTrue(
        BuckFixSpecParser.parseFromBuildId(helper, fixConfig, buildCommandId, false).isLeft());

    filesystem.deleteFileAtPathIfExists(machineLog);
    filesystem.move(logDir.resolve("buck-machine-log_missing_build_id"), machineLog);

    BuckFixSpecParser.FixSpecFailure failure =
        BuckFixSpecParser.parseFromBuildId(helper, fixConfig, buildCommandId, false).getRight();
    assertEquals(BuckFixSpecParser.FixSpecFailure.MISSING, failure);

    filesystem.deleteFileAtPathIfExists(machineLog);
    filesystem.move(logDir.resolve("buck-machine-log_missing_exit_code"), machineLog);

    failure =
        BuckFixSpecParser.parseFromBuildId(helper, fixConfig, buildCommandId, false).getRight();
    assertEquals(BuckFixSpecParser.FixSpecFailure.MISSING_EXIT_CODE, failure);

    filesystem.deleteFileAtPathIfExists(machineLog);
    filesystem.move(logDir.resolve("buck-machine-log_missing_command_args"), machineLog);

    failure =
        BuckFixSpecParser.parseFromBuildId(helper, fixConfig, buildCommandId, false).getRight();
    assertEquals(BuckFixSpecParser.FixSpecFailure.MISSING_EXPANDED_COMMAND_ARGS, failure);

    filesystem.deleteFileAtPathIfExists(machineLog);
    filesystem.move(logDir.resolve("buck-machine-log_missing_unexpanded_command_args"), machineLog);

    failure =
        BuckFixSpecParser.parseFromBuildId(helper, fixConfig, buildCommandId, false).getRight();
    assertEquals(BuckFixSpecParser.FixSpecFailure.MISSING, failure);
  }

  @Test
  public void returnsByBuildId() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "report", tempFolder);
    workspace.setUp();

    Path logDir = filesystem.getBuckPaths().getLogDir().resolve(buildCommandDir);

    BuckFixSpec expectedSpec =
        new ImmutableBuckFixSpec(
            buildCommandId,
            "build",
            0,
            ImmutableList.of("@file", "buck"),
            ImmutableList.of("-c", "foo.bar=baz", "buck"),
            false,
            Optional.empty(),
            ImmutableMap.of("jasabi_fix", ImmutableList.of("legacy/script/location")),
            BuckFixSpec.getLogsMapping(
                Optional.of(logDir.resolve("buck.log")),
                Optional.of(logDir.resolve("buck-machine-log")),
                Optional.of(logDir.resolve(buildCommandTrace)),
                Optional.of(logDir.resolve("buckconfig.json"))));

    BuildLogHelper helper = new BuildLogHelper(filesystem);

    BuckFixSpec spec =
        BuckFixSpecParser.parseFromBuildId(helper, fixConfig, buildCommandId, false).getLeft();

    assertEquals(expectedSpec, spec);
  }

  @Test
  public void returnsEmptyWhenNoNonInternalLogsWereFound() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "report", tempFolder);
    workspace.setUp();
    filesystem.deleteRecursivelyIfExists(
        filesystem.getBuckPaths().getLogDir().resolve(buildCommandDir));
    assertTrue(filesystem.exists(filesystem.getBuckPaths().getLogDir().resolve(launchCommandDir)));

    BuildLogHelper helper = new BuildLogHelper(filesystem);

    BuckFixSpecParser.FixSpecFailure failure =
        BuckFixSpecParser.parseLastCommand(helper, fixConfig, false).getRight();

    assertEquals(BuckFixSpecParser.FixSpecFailure.MISSING, failure);
  }

  @Test
  public void returnsEmptyWhenFirstLogIsPresentButRequiredFieldsAreMissing() throws IOException {
    // A couple of these states return generic "MISSING" errors. This is due to an implementation
    // detail in BuildLogHelper, and may be subject to change later

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "report", tempFolder);
    workspace.setUp();

    Path logDir = filesystem.getBuckPaths().getLogDir().resolve(buildCommandDir);
    Path machineLog = logDir.resolve("buck-machine-log");

    BuildLogHelper helper = new BuildLogHelper(filesystem);

    assertTrue(BuckFixSpecParser.parseLastCommand(helper, fixConfig, false).isLeft());

    filesystem.deleteFileAtPathIfExists(machineLog);
    filesystem.move(logDir.resolve("buck-machine-log_missing_build_id"), machineLog);

    BuckFixSpecParser.FixSpecFailure failure =
        BuckFixSpecParser.parseLastCommand(helper, fixConfig, false).getRight();
    assertEquals(BuckFixSpecParser.FixSpecFailure.MISSING, failure);

    filesystem.deleteFileAtPathIfExists(machineLog);
    filesystem.move(logDir.resolve("buck-machine-log_missing_exit_code"), machineLog);

    failure = BuckFixSpecParser.parseLastCommand(helper, fixConfig, false).getRight();
    assertEquals(BuckFixSpecParser.FixSpecFailure.MISSING_EXIT_CODE, failure);

    filesystem.deleteFileAtPathIfExists(machineLog);
    filesystem.move(logDir.resolve("buck-machine-log_missing_command_args"), machineLog);

    failure = BuckFixSpecParser.parseLastCommand(helper, fixConfig, false).getRight();
    assertEquals(BuckFixSpecParser.FixSpecFailure.MISSING_EXPANDED_COMMAND_ARGS, failure);

    filesystem.deleteFileAtPathIfExists(machineLog);
    filesystem.move(logDir.resolve("buck-machine-log_missing_unexpanded_command_args"), machineLog);

    failure = BuckFixSpecParser.parseLastCommand(helper, fixConfig, false).getRight();
    assertEquals(BuckFixSpecParser.FixSpecFailure.MISSING, failure);
  }

  @Test
  public void returnsFirstNonInternalLog() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "report", tempFolder);
    workspace.setUp();

    Path logDir = filesystem.getBuckPaths().getLogDir().resolve(buildCommandDir);

    BuckFixSpec expectedSpec =
        new ImmutableBuckFixSpec(
            buildCommandId,
            "build",
            0,
            ImmutableList.of("@file", "buck"),
            ImmutableList.of("-c", "foo.bar=baz", "buck"),
            false,
            Optional.empty(),
            ImmutableMap.of("jasabi_fix", ImmutableList.of("legacy/script/location")),
            BuckFixSpec.getLogsMapping(
                Optional.of(logDir.resolve("buck.log")),
                Optional.of(logDir.resolve("buck-machine-log")),
                Optional.of(logDir.resolve(buildCommandTrace)),
                Optional.of(logDir.resolve("buckconfig.json"))));

    BuildLogHelper helper = new BuildLogHelper(filesystem);

    BuckFixSpec spec = BuckFixSpecParser.parseLastCommand(helper, fixConfig, false).getLeft();

    assertEquals(expectedSpec, spec);
  }
}
