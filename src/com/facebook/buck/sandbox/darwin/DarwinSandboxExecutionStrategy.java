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

package com.facebook.buck.sandbox.darwin;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.sandbox.SandboxConfig;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.sandbox.SandboxProperties;
import com.facebook.buck.shell.programrunner.ProgramRunner;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutor.Option;
import com.facebook.buck.util.ProcessExecutorParams;
import java.io.IOException;
import java.util.Collections;
import java.util.Optional;
import java.util.function.Supplier;

/** {@link SandboxExecutionStrategy} for OS X sandbox. */
public class DarwinSandboxExecutionStrategy implements SandboxExecutionStrategy {

  private static final Logger LOG = Logger.get(DarwinSandboxExecutionStrategy.class);

  private final SandboxConfig sandboxConfig;
  private final Supplier<Boolean> isSandboxExecutionVerified;

  public DarwinSandboxExecutionStrategy(
      ProcessExecutor processExecutor, SandboxConfig sandboxConfig) {
    this.sandboxConfig = sandboxConfig;

    this.isSandboxExecutionVerified =
        MoreSuppliers.memoize(() -> verifySandboxExecution(processExecutor));
  }

  @Override
  public boolean isSandboxEnabled() {
    return sandboxConfig.isDarwinSandboxEnabled() && isSandboxExecutionVerified.get();
  }

  @Override
  public ProgramRunner createSandboxProgramRunner(SandboxProperties sandboxProperties) {
    return new DarwinSandboxProgramRunner(sandboxProperties);
  }

  private static boolean verifySandboxExecution(ProcessExecutor processExecutor) {
    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .addAllCommand(DarwinSandbox.createVerificationCommandLineArguments())
            .build();
    ProcessExecutor.Result result;
    try {
      result =
          processExecutor.launchAndExecute(
              params,
              Collections.singleton(Option.IS_SILENT),
              Optional.empty(),
              Optional.of(10000L),
              Optional.empty());
    } catch (IOException e) {
      LOG.error(e, "Cannot detect if Darwin sandboxing is supported");
      return false;
    } catch (InterruptedException e) {
      Thread.interrupted();
      throw new RuntimeException(e);
    }

    if (result.getExitCode() != 0) {
      LOG.error(
          "Cannot detect if Darwin sandboxing is supported: "
              + result.getMessageForUnexpectedResult("sandbox verification"));
    }
    return result.getExitCode() == 0;
  }
}
