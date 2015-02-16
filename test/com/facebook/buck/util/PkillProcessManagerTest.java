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

package com.facebook.buck.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.io.IOException;

public class PkillProcessManagerTest {

  @Test
  public void processIsRunningIfPkill0Succeeds() throws IOException, InterruptedException {
    ProcessExecutorParams pkill0Params =
        ImmutableProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("pkill", "-0", "processName"))
            .build();
    FakeProcessExecutor processExecutor = new FakeProcessExecutor(
        ImmutableMap.of(
            pkill0Params,
            new FakeProcess(0)));
    PkillProcessManager processManager = new PkillProcessManager(
        processExecutor);
    assertTrue(processManager.isProcessRunning("processName"));
    assertTrue(processExecutor.isProcessLaunched(pkill0Params));
  }

  @Test
  public void processIsNotRunningIfPkill0Fails() throws IOException, InterruptedException {
    ProcessExecutorParams pkill0Params =
        ImmutableProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("pkill", "-0", "processName"))
            .build();
    FakeProcessExecutor processExecutor = new FakeProcessExecutor(
        ImmutableMap.of(
            pkill0Params,
            new FakeProcess(1)));
    PkillProcessManager processManager = new PkillProcessManager(
        processExecutor);
    assertFalse(processManager.isProcessRunning("processName"));
    assertTrue(processExecutor.isProcessLaunched(pkill0Params));
  }

  @Test(expected = HumanReadableException.class)
  public void isProcessRunningThrowsOnUnexpectedExitCode()
      throws IOException, InterruptedException {
    ProcessExecutorParams pkill0Params =
        ImmutableProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("pkill", "-0", "processName"))
            .build();
    FakeProcessExecutor processExecutor = new FakeProcessExecutor(
        ImmutableMap.of(
            pkill0Params,
            new FakeProcess(49152)));
    PkillProcessManager processManager = new PkillProcessManager(processExecutor);
    processManager.isProcessRunning("processName");
  }

  @Test
  public void killProcessLaunchesProcessWithSuccess() throws IOException, InterruptedException {
    ProcessExecutorParams pkillParams =
        ImmutableProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("pkill", "processName"))
            .build();
    FakeProcessExecutor processExecutor = new FakeProcessExecutor(
        ImmutableMap.of(
            pkillParams,
            new FakeProcess(0)));
    PkillProcessManager processManager = new PkillProcessManager(processExecutor);
    processManager.killProcess("processName");
    assertTrue(processExecutor.isProcessLaunched(pkillParams));
  }

  @Test
  public void killProcessLaunchesProcessWithFailure() throws IOException, InterruptedException {
    ProcessExecutorParams pkillParams =
        ImmutableProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("pkill", "processName"))
            .build();
    FakeProcessExecutor processExecutor = new FakeProcessExecutor(
        ImmutableMap.of(
            pkillParams,
            new FakeProcess(1)));
    PkillProcessManager processManager = new PkillProcessManager(processExecutor);
    processManager.killProcess("processName");
    assertTrue(processExecutor.isProcessLaunched(pkillParams));
  }

  @Test(expected = HumanReadableException.class)
  public void killProcessThrowsWithUnexpectedExitCode()
      throws IOException, InterruptedException {
    ProcessExecutorParams pkillParams =
        ImmutableProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("pkill", "processName"))
            .build();
    FakeProcessExecutor processExecutor = new FakeProcessExecutor(
        ImmutableMap.of(
            pkillParams,
            new FakeProcess(64738)));
    PkillProcessManager processManager = new PkillProcessManager(processExecutor);
    processManager.killProcess("processName");
  }
}
