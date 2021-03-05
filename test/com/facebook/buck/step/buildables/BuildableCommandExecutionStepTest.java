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

package com.facebook.buck.step.buildables;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.stringContainsInOrder;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystemFactory;
import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.EnvironmentSanitizer;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import java.io.FileOutputStream;
import java.net.URL;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Rule;
import org.junit.Test;

public class BuildableCommandExecutionStepTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private final ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

  @Test
  public void canExecuteStep() throws Exception {
    String packageName = getClass().getPackage().getName().replace('.', '/');
    URL binary = Resources.getResource(packageName + "/external_actions_bin_for_tests.jar");
    AbsPath testBinary =
        AbsPath.of(
            temporaryFolder.getRoot().getPath().resolve("external_action.jar").toAbsolutePath());
    try (FileOutputStream stream = new FileOutputStream(testBinary.toFile())) {
      stream.write(Resources.toByteArray(binary));
    }

    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildableCommandExecutionStep testStep =
        new BuildableCommandExecutionStep(
            BuildableCommand.getDefaultInstance(),
            projectFilesystem,
            ImmutableList.of("java"),
            () -> projectFilesystem.relativize(testBinary)) {};
    StepExecutionResult result = testStep.execute(createExecutionContext(this.projectFilesystem));
    assertThat(result.getExitCode(), equalTo(0));
    assertThat(
        result.getStderr().orElseThrow(IllegalStateException::new),
        stringContainsInOrder("Received args:", "buildable_command_"));
  }

  private StepExecutionContext createExecutionContext(ProjectFilesystem projectFilesystem) {
    AbsPath rootPath = projectFilesystem.getRootPath();
    return StepExecutionContext.builder()
        .setConsole(Console.createNullConsole())
        .setBuckEventBus(BuckEventBusForTests.newInstance())
        .setPlatform(Platform.UNKNOWN)
        .setEnvironment(EnvironmentSanitizer.getSanitizedEnvForTests(ImmutableMap.of()))
        .setBuildCellRootPath(Paths.get("cell"))
        .setProcessExecutor(
            new DefaultProcessExecutor(
                new Console(
                    Verbosity.STANDARD_INFORMATION, System.out, System.err, new Ansi(true))))
        .setProjectFilesystemFactory(new FakeProjectFilesystemFactory())
        .setRuleCellRoot(rootPath)
        .setActionId("test_action_id")
        .setClock(FakeClock.doNotCare())
        .setWorkerToolPools(new ConcurrentHashMap<>())
        .build();
  }
}
