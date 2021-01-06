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

package com.facebook.buck.worker;

import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class FakeWorkerProcess extends WorkerProcess {

  private ImmutableMap<String, WorkerJobResult> jobArgsToJobResultMap;
  private boolean isAlive;

  public FakeWorkerProcess(ImmutableMap<String, WorkerJobResult> jobArgsToJobResultMap)
      throws IOException {
    super(
        new FakeProcessExecutor(),
        ProcessExecutorParams.builder().setCommand(ImmutableList.of()).build(),
        new FakeProjectFilesystem(),
        Files.createTempFile("buck-worker-", "-stderr.log"),
        Paths.get("tmp").toAbsolutePath().normalize());
    this.jobArgsToJobResultMap = jobArgsToJobResultMap;
    this.isAlive = false;
  }

  @Override
  public boolean isAlive() {
    return isAlive;
  }

  @Override
  public synchronized void ensureLaunchAndHandshake() {
    isAlive = true;
  }

  @Override
  public synchronized ListenableFuture<WorkerJobResult> submitJob(String jobArgs) {
    WorkerJobResult result = this.jobArgsToJobResultMap.get(jobArgs);
    if (result == null) {
      throw new IllegalArgumentException(
          String.format("No fake WorkerJobResult found for job arguments '%s'", jobArgs));
    }
    SettableFuture<WorkerJobResult> out = SettableFuture.create();
    out.set(result);
    return out;
  }

  @Override
  public void close() {
    Preconditions.checkState(isAlive, "Closing a dead process?");
    isAlive = false;
  }
}
