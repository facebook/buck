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

package com.facebook.buck.worker;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class WorkerProcessTest {

  @Rule public TemporaryPaths temporaryPaths = new TemporaryPaths();

  private ProcessExecutorParams createDummyParams() {
    return ProcessExecutorParams.builder().setCommand(ImmutableList.of()).build();
  }

  @Test
  public void testSubmitAndWaitForJob() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path tmpPath = Files.createTempDirectory("tmp").toAbsolutePath().normalize();
    Path argsPath = Paths.get(tmpPath.toString(), "0.args");
    Path stdoutPath = Paths.get(tmpPath.toString(), "0.out");
    Path stderrPath = Paths.get(tmpPath.toString(), "0.err");
    String jobArgs = "my job args";
    int exitCode = 0;

    Optional<String> stdout = Optional.of("my stdout");
    Optional<String> stderr = Optional.of("my stderr");

    try (WorkerProcess process =
        new WorkerProcess(new FakeProcessExecutor(), createDummyParams(), filesystem, tmpPath)) {
      process.setProtocol(
          new FakeWorkerProcessProtocol.FakeCommandSender() {
            @Override
            public int receiveCommandResponse(int messageID) throws IOException {
              // simulate the external tool and write the stdout and stderr files
              filesystem.writeContentsToPath(stdout.get(), stdoutPath);
              filesystem.writeContentsToPath(stderr.get(), stderrPath);
              return super.receiveCommandResponse(messageID);
            }
          });

      WorkerJobResult expectedResult = WorkerJobResult.of(exitCode, stdout, stderr);
      assertThat(process.submitAndWaitForJob(jobArgs), Matchers.equalTo(expectedResult));
      assertThat(filesystem.readFileIfItExists(argsPath).get(), Matchers.equalTo(jobArgs));
    }
  }

  @Test
  public void testClose() throws IOException {
    FakeWorkerProcessProtocol.FakeCommandSender protocol =
        new FakeWorkerProcessProtocol.FakeCommandSender();

    try (WorkerProcess process =
        new WorkerProcess(
            new FakeProcessExecutor(),
            createDummyParams(),
            new FakeProjectFilesystem(),
            Paths.get("tmp").toAbsolutePath().normalize())) {
      process.setProtocol(protocol);

      assertFalse(protocol.isClosed());
      process.close();
      assertTrue(protocol.isClosed());
    }
  }

  @Test(timeout = 20 * 1000)
  public void testDoesNotBlockOnLargeStderr() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "worker_process", temporaryPaths);
    workspace.setUp();
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
    Console console = new Console(Verbosity.ALL, System.out, System.err, Ansi.withoutTty());
    String script;
    if (Platform.detect() == Platform.WINDOWS) {
      script = workspace.getDestPath().resolve("script.bat").toString();
    } else {
      script = "./script.py";
    }
    try (WorkerProcess workerProcess =
        new WorkerProcess(
            new DefaultProcessExecutor(console),
            ProcessExecutorParams.builder()
                .setCommand(ImmutableList.of(script))
                .setDirectory(workspace.getDestPath())
                .build(),
            projectFilesystem,
            temporaryPaths.newFolder())) {
      workerProcess.ensureLaunchAndHandshake();
      fail("Handshake should have failed");
    } catch (HumanReadableException e) {
      // Check that all of the process's stderr was reported.
      assertThat(e.getMessage().length(), is(greaterThan(1024 * 1024)));
    }
  }
}
