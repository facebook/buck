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

import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.nio.file.Paths;

public class FakeWorkerProcess extends WorkerProcess {

  private ImmutableMap<String, WorkerJobResult> jobArgsToJobResultMap;

  public FakeWorkerProcess(
      ImmutableMap<String, WorkerJobResult> jobArgsToJobResultMap) throws IOException {
    super(
        new FakeProcessExecutor(),
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.<String>of())
            .build(),
        new FakeProjectFilesystem(),
        Paths.get("tmp").toAbsolutePath().normalize());
    this.jobArgsToJobResultMap = jobArgsToJobResultMap;
    this.setProtocol(new FakeWorkerProcessProtocol());
  }

  @Override
  public synchronized void ensureLaunchAndHandshake() throws IOException {}

  @Override
  public synchronized WorkerJobResult submitAndWaitForJob(String jobArgs) throws IOException {
    return this.jobArgsToJobResultMap.get(jobArgs);
  }

  @Override
  public void close() {}
}
