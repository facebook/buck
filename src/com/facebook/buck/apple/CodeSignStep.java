/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

public class CodeSignStep implements Step {
  private final Path workingDirectory;
  private final Path pathToSign;
  private final Path pathToSigningEntitlements;
  private final String codeSignIdentity;

  public CodeSignStep(
      Path workingDirectory,
      Path pathToSign,
      Path pathToSigningEntitlements,
      String codeSignIdentity) {
    this.workingDirectory = workingDirectory;
    this.pathToSign = pathToSign;
    this.pathToSigningEntitlements = pathToSigningEntitlements;
    this.codeSignIdentity = codeSignIdentity;
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "codesign",
                    "--force",
                    "--sign", codeSignIdentity,
                    "--entitlements", pathToSigningEntitlements.toString(),
                    pathToSign.toString()))
            .setDirectory(workingDirectory.toFile())
            .build();
    // Must specify that stdout is expected or else output may be wrapped in Ansi escape chars.
    Set<ProcessExecutor.Option> options = EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT);
    ProcessExecutor.Result result;
    try {
      ProcessExecutor processExecutor = context.getProcessExecutor();
      result = processExecutor.launchAndExecute(
          processExecutorParams,
          options,
              /* stdin */ Optional.<String>absent(),
              /* timeOutMs */ Optional.<Long>absent(),
              /* timeOutHandler */ Optional.<Function<Process, Void>>absent());
    } catch (InterruptedException | IOException e) {
      context.logError(e, "Could not execute codesign.");
      return 1;
    }

    if (result.getExitCode() != 0) {
      return 1;
    }
    return 0;
  }

  @Override
  public String getShortName() {
    return "code-sign";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("code-sign %s",
        pathToSign);
  }
}
