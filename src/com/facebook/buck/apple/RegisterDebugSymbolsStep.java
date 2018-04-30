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

import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

class RegisterDebugSymbolsStep implements Step {

  private final SourcePath binary;
  private final Tool lldb;
  private final SourcePathResolver resolver;
  private final Path dsymPath;

  public RegisterDebugSymbolsStep(
      SourcePath binary, Tool lldb, SourcePathResolver resolver, Path dsymPath) {
    this.binary = binary;
    this.lldb = lldb;
    this.resolver = resolver;
    this.dsymPath = dsymPath;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    ImmutableList<String> lldbCommandPrefix = lldb.getCommandPrefix(resolver);
    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .addCommand(lldbCommandPrefix.toArray(new String[lldbCommandPrefix.size()]))
            .build();
    return StepExecutionResult.of(
        context
            .getProcessExecutor()
            .launchAndExecute(
                params,
                ImmutableSet.of(),
                Optional.of(
                    String.format(
                        "target create %s\ntarget symbols add %s",
                        resolver.getAbsolutePath(binary), dsymPath)),
                Optional.empty(),
                Optional.empty()));
  }

  @Override
  public String getShortName() {
    return "register debug symbols";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format(
        "register debug symbols for binary '%s': '%s'", resolver.getRelativePath(binary), dsymPath);
  }
}
