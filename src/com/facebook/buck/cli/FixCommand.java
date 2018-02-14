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

package com.facebook.buck.cli;

import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Optional;

/**
 * Attempts to fix errors encountered in the previous build.
 *
 * <p>TODO(jkeljo): The present implementation is specific to source-only ABI, but the intention is
 * for this to grow into a general-purpose extension point for autofixes of all kinds.
 */
public class FixCommand extends AbstractCommand {

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    String scriptPath = System.getProperty("buck.fix_script");

    ProcessExecutor processExecutor =
        new DefaultProcessExecutor(getExecutionContext().getConsole());
    ProcessExecutorParams processParams =
        ProcessExecutorParams.builder()
            .addCommand(Preconditions.checkNotNull(scriptPath))
            .setEnvironment(params.getEnvironment())
            .setDirectory(params.getCell().getFilesystem().getRootPath())
            .build();

    int code =
        processExecutor
            .launchAndExecute(
                processParams,
                EnumSet.of(
                    ProcessExecutor.Option.PRINT_STD_ERR, ProcessExecutor.Option.PRINT_STD_OUT),
                Optional.empty(),
                Optional.empty(),
                Optional.empty())
            .getExitCode();
    return ExitCode.map(code);
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public String getShortDescription() {
    return "attempts to fix errors encountered in the previous build";
  }
}
