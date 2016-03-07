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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.IOException;

public class StripDebugSymbolsStep implements Step {

  private static final Logger LOG = Logger.get(StripDebugSymbolsStep.class);

  private final BuildRule binaryBuildRule;
  private final Tool strip;
  private final ProjectFilesystem projectFilesystem;
  private final SourcePathResolver resolver;

  public StripDebugSymbolsStep(
      BuildRule binaryBuildRule,
      Tool strip,
      ProjectFilesystem projectFilesystem,
      SourcePathResolver resolver) {
    this.binaryBuildRule = binaryBuildRule;
    this.strip = strip;
    this.projectFilesystem = projectFilesystem;
    this.resolver = resolver;
  }

  @Override
  public int execute(ExecutionContext context) throws IOException, InterruptedException {
    Preconditions.checkNotNull(binaryBuildRule.getPathToOutput(),
        "Binary build rule " + binaryBuildRule.toString() + " has no output path.");
    // Don't strip binaries which are already code-signed.  Note: we need to use
    // binaryOutputPath instead of binaryPath because codesign will evaluate the
    // entire .app bundle, even if you pass it the direct path to the embedded binary.
    if (!CodeSigning.hasValidSignature(
        context.getProcessExecutor(),
        binaryBuildRule.getPathToOutput())) {
      return (new DefaultShellStep(
          projectFilesystem.getRootPath(),
          ImmutableList.<String>builder()
              .addAll(strip.getCommandPrefix(resolver))
              .add("-S")
              .add(projectFilesystem.resolve(binaryBuildRule.getPathToOutput()).toString())
              .build(),
          strip.getEnvironment(resolver)))
          .execute(context);
    } else {
      LOG.info("Not stripping code-signed binary.");
      return 0;
    }
  }

  @Override
  public String getShortName() {
    return "strip binary";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format(
        "strip debug symbols from binary '%s'",
        binaryBuildRule.getPathToOutput());
  }
}
