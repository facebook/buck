/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.step.external;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.worker.WorkerJobResult;
import com.facebook.buck.worker.WorkerProcess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;

public class BundledExternalProcessLauncherTest {
  @Rule public TemporaryPaths temporaryPaths = new TemporaryPaths();

  @Test
  public void canLaunch() throws IOException {
    // Worker process is currently broken on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    BundledExternalProcessLauncher launcher = new BundledExternalProcessLauncher();

    Path tmpPath = Files.createTempDirectory("tmp").toAbsolutePath().normalize();
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(tmpPath.getRoot());

    ProcessExecutor processExecutor = new DefaultProcessExecutor(new TestConsole());
    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .setCommand(launcher.getCommandForStepExecutor())
            .setDirectory(tmpPath)
            .build();

    try (WorkerProcess process =
        new WorkerProcess(processExecutor, params, projectFilesystem, tmpPath)) {
      process.ensureLaunchAndHandshake();
      WorkerJobResult jobResult = process.submitAndWaitForJob("jobArg");
      assertEquals(0, jobResult.getExitCode());
    }
  }
}
