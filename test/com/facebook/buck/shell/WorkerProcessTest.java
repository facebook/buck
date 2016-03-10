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

package com.facebook.buck.shell;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class WorkerProcessTest {

  private ProcessExecutorParams createDummyParams() {
    return ProcessExecutorParams.builder()
        .setCommand(ImmutableList.<String>of())
        .build();
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

    // simulate the external tool and write the stdout and stderr files
    Optional<String> stdout = Optional.of("my stdout");
    Optional<String> stderr = Optional.of("my stderr");
    filesystem.writeContentsToPath(stdout.get(), stdoutPath);
    filesystem.writeContentsToPath(stderr.get(), stderrPath);

    WorkerProcess process = new WorkerProcess(
        new FakeProcessExecutor(),
        createDummyParams(),
        filesystem,
        tmpPath);
    process.setProtocol(new FakeWorkerProcessProtocol());

    WorkerJobResult expectedResult = WorkerJobResult.of(exitCode, stdout, stderr);
    assertThat(process.submitAndWaitForJob(jobArgs), Matchers.equalTo(expectedResult));
    assertThat(filesystem.readFileIfItExists(argsPath).get(), Matchers.equalTo(jobArgs));
  }

  @Test
  public void testClose() throws IOException {
    FakeWorkerProcessProtocol protocol = new FakeWorkerProcessProtocol();

    WorkerProcess process = new WorkerProcess(
        new FakeProcessExecutor(),
        createDummyParams(),
        new FakeProjectFilesystem(),
        Paths.get("tmp").toAbsolutePath().normalize());
    process.setProtocol(protocol);

    assertFalse(protocol.isClosed());
    process.close();
    assertTrue(protocol.isClosed());
  }
}
