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

package com.facebook.buck.cli;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.support.fix.FixBuckConfig;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Optional;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Option;

/** Attempts to fix errors encountered in previous builds */
public class FixCommand extends AbstractCommand {

  @Option(
      name = "--build-id",
      usage =
          "The build id for which to attempt to run the fix script. If not provided, the last "
              + "non-fix/doctor/server build will be used")
  @Nullable
  private String buildId;

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    FixBuckConfig config = params.getBuckConfig().getView(FixBuckConfig.class);

    Optional<ImmutableList<String>> legacyFixScript = config.getLegacyFixScript();

    if (config.shouldUseLegacyFixScript()) {
      if (!legacyFixScript.isPresent()) {
        throw new IllegalStateException(
            "`buck fix` requires the buck.legacy_fix_script java system property to be set by the "
                + "wrapper script if no custom fix script is configured.");
      }
      return runLegacyFixScript(params, legacyFixScript.get());
    }

    FixCommandHandler fixCommandHandler =
        new FixCommandHandler(
            params.getCells().getRootCell().getFilesystem(),
            params.getConsole(),
            params.getEnvironment(),
            config,
            commandArgsFile,
            params.getInvocationInfo().get());
    if (buildId == null) {
      fixCommandHandler.runWithLatestCommand(true);
    } else {
      fixCommandHandler.runWithBuildId(new BuildId(buildId), true);
    }

    return ExitCode.SUCCESS;
  }

  private ExitCode runLegacyFixScript(CommandRunnerParams params, ImmutableList<String> scriptPath)
      throws IOException, InterruptedException {
    ProcessExecutor processExecutor =
        new DefaultProcessExecutor(getExecutionContext().getConsole());
    ProcessExecutorParams processParams =
        ProcessExecutorParams.builder()
            .addAllCommand(scriptPath)
            .setEnvironment(params.getEnvironment())
            .setDirectory(params.getCells().getRootCell().getFilesystem().getRootPath().getPath())
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
