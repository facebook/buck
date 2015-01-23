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

import com.facebook.buck.log.Logger;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;

import java.io.IOException;

/**
 * Checks for and kills processes by name.
 *
 * Only supported on non-Windows platforms (depends on {@code pkill} binary).
 */
public class PkillProcessManager implements ProcessManager {
  private static final Logger LOG = Logger.get(PkillProcessManager.class);
  private static final String PKILL_BINARY = "pkill";

  private final ProcessExecutor processExecutor;

  public PkillProcessManager(ProcessExecutor processExecutor) {
    this.processExecutor = processExecutor;
  }

  @Override
  public boolean isProcessRunning(String processName) throws InterruptedException, IOException {
    LOG.debug("Checking if %s is running...", processName);
    ImmutableList<String> processAndArgs = ImmutableList.of(PKILL_BINARY, "-0", processName);
    ProcessExecutor.Result result = runProcess(processAndArgs);
    if (result.getExitCode() == 0) {
      LOG.debug("Process %s is running", processName);
      return true;
    } else if (result.getExitCode() == 1) {
      LOG.debug("Process %s is not running", processName);
      return false;
    } else {
      throw new HumanReadableException(
          "Unexpected error running %s: exit code %d, stdout %s, stderr %s",
          Joiner.on(" ").join(processAndArgs),
          result.getExitCode(),
          result.getStdout(),
          result.getStderr());
    }
  }

  @Override
  public void killProcess(String processName) throws InterruptedException, IOException {
    LOG.debug("Killing process %s...", processName);
    ImmutableList<String> processAndArgs = ImmutableList.of(PKILL_BINARY, processName);
    ProcessExecutor.Result result = runProcess(processAndArgs);
    if (result.getExitCode() == 0) {
      LOG.debug("Killed process %s.", processName);
    } else if (result.getExitCode() == 1) {
      LOG.debug("Could not kill process %s (not running)", processName);
      // We don't treat this as an error, because the user could have killed
      // the process themselves.
    } else {
      throw new HumanReadableException(
          "Unexpected error running %s: exit code %d, stdout %s, stderr %s",
          Joiner.on(" ").join(processAndArgs),
          result.getExitCode(),
          result.getStdout(),
          result.getStderr());
    }
  }

  private ProcessExecutor.Result runProcess(ImmutableList<String> processAndArgs)
      throws InterruptedException, IOException {
    ProcessExecutorParams params = ImmutableProcessExecutorParams.builder()
        .setCommand(processAndArgs)
        .build();
    return processExecutor.launchAndExecute(params);
  }
}
