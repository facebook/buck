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

package com.facebook.buck.external.main;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.downwardapi.processexecutor.DownwardApiProcessExecutor;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.external.utils.ExternalBinaryBuckConstants;
import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.EnvironmentSanitizer;
import com.facebook.buck.util.ConsoleParams;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Verbosity;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Resources;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ExternalActionsIntegrationTest {

  private static final String TEST_ACTION_ID = "test_action_id";
  private static final ConsoleParams CONSOLE_PARAMS =
      ConsoleParams.of(false, Verbosity.STANDARD_INFORMATION);

  private ProcessExecutor defaultExecutor;
  private IsolatedEventBus eventBus;
  private ProcessExecutor downwardApiProcessExecutor;
  private Path testBinary;

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Before
  public void setUp() throws Exception {
    defaultExecutor = new DefaultProcessExecutor(new TestConsole());
    eventBus = BuckEventBusForTests.newInstance().isolated();
    downwardApiProcessExecutor =
        DownwardApiProcessExecutor.FACTORY.create(
            defaultExecutor, CONSOLE_PARAMS, eventBus, TEST_ACTION_ID);

    String packageName = getClass().getPackage().getName().replace('.', '/');
    URL binary = Resources.getResource(packageName + "/external_actions_bin_for_tests.jar");
    testBinary = temporaryFolder.getRoot().getPath().resolve("external_action.jar");
    try (FileOutputStream stream = new FileOutputStream(testBinary.toFile())) {
      stream.write(Resources.toByteArray(binary));
    }
  }

  @Test
  public void executingBinaryExecutesExternalActions() throws Exception {
    File buildableCommandFile = temporaryFolder.newFile("buildable_command").toFile();
    BuildableCommand buildableCommand =
        BuildableCommand.newBuilder()
            .addAllArgs(ImmutableList.of("test_path"))
            .putAllEnv(ImmutableMap.of())
            .build();
    try (OutputStream outputStream = new FileOutputStream(buildableCommandFile)) {
      buildableCommand.writeTo(outputStream);
    }

    ImmutableList<String> command =
        ImmutableList.of(
            "java",
            "-jar",
            testBinary.toString(),
            FakeMkdirExternalAction.class.getName(),
            buildableCommandFile.getAbsolutePath());

    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .setCommand(command)
            .setEnvironment(
                EnvironmentSanitizer.getSanitizedEnvForTests(
                    ImmutableMap.of(
                        ExternalBinaryBuckConstants.ENV_RULE_CELL_ROOT,
                        temporaryFolder.getRoot().toString())))
            .build();

    ProcessExecutor.Result result =
        downwardApiProcessExecutor.launchAndExecute(
            params,
            ImmutableMap.of(),
            ImmutableSet.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    assertThat(result.getExitCode(), equalTo(0));
    AbsPath actual = temporaryFolder.getRoot().resolve("test_path");
    assertTrue(Files.isDirectory(actual.getPath()));
  }
}
