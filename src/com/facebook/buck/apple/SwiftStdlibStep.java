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

package com.facebook.buck.apple;

import com.facebook.buck.log.Logger;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.SimpleProcessListener;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * A step that invokes Apple's tool to scan the binary and copy any needed Swift standard libraries.
 */
public class SwiftStdlibStep implements Step {

  private static final Logger LOG = Logger.get(SwiftStdlibStep.class);

  private final Path workingDirectory;
  private final Iterable<String> command;
  private final Optional<Supplier<CodeSignIdentity>> codeSignIdentitySupplier;

  public SwiftStdlibStep(
      Path workingDirectory,
      Iterable<String> command,
      Optional<Supplier<CodeSignIdentity>> codeSignIdentitySupplier) {
    this.workingDirectory = workingDirectory;
    this.command = command;
    this.codeSignIdentitySupplier = codeSignIdentitySupplier;
  }

  @Override
  public String getShortName() {
    return "copy swift standard libs";
  }

  private ProcessExecutorParams makeProcessExecutorParams() {
    ProcessExecutorParams.Builder builder = ProcessExecutorParams.builder();
    builder.setDirectory(workingDirectory.toAbsolutePath().toFile());
    builder.setCommand(command);
    if (codeSignIdentitySupplier.isPresent()) {
      builder.addCommand(
          "--sign",
          CodeSignStep.getIdentityArg(codeSignIdentitySupplier.get().get()));
    }
    return builder.build();
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    ListeningProcessExecutor executor = new ListeningProcessExecutor();
    ProcessExecutorParams params = makeProcessExecutorParams();
    SimpleProcessListener listener = new SimpleProcessListener();

    // TODO(ryu2): parse output as needed
    try {
      LOG.debug("%s", command);
      ListeningProcessExecutor.LaunchedProcess process = executor.launchProcess(params, listener);
      int result = executor.waitForProcess(process, Long.MAX_VALUE, TimeUnit.SECONDS);
      if (result != 0) {
        LOG.error("Error running %s: %s", getDescription(context), listener.getStderr());
      }
      return result;
    } catch (IOException e) {
      LOG.error(e, "Could not execute command %s", command);
      return 1;
    }
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return Joiner.on(" ").join(command);
  }
}
