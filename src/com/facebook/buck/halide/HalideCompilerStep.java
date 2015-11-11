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

package com.facebook.buck.halide;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;

public class HalideCompilerStep extends ShellStep {
  // The list of arguments needed to run our generated compiler.
  private final ImmutableList<String> compilerPrefix;

  // The output directory in which to write the generated shader code.
  private final Path outputDir;

  // The name of the function (or pipeline) to compile.
  private final String funcName;

  // The Halide target string for the target architecture, e.g. "x86-64-osx".
  // May be empty; if so, we assume that we should generate code for the host
  // architecture.
  private final Optional<String> halideTarget;

  // If true, only generate the header file for the compiled shader.
  private final boolean headerOnly;

  public HalideCompilerStep(
      Path workingDirectory,
      ImmutableList<String> compilerPrefix,
      Path outputDir,
      String funcName,
      Optional<String> halideTarget,
      boolean headerOnly) {
    super(workingDirectory);
    this.compilerPrefix = compilerPrefix;
    this.outputDir = outputDir;
    this.funcName = funcName;
    this.halideTarget = halideTarget;
    this.headerOnly = headerOnly;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    builder.addAll(compilerPrefix);
    builder.add("-o", outputDir.toString());

    if (halideTarget.isPresent() && !halideTarget.get().isEmpty()) {
      builder.add("-t", halideTarget.get());
    }

    if (headerOnly) {
      builder.add("--header-only");
    }

    builder.add(funcName);
    return builder.build();
  }

  @Override
  public String getShortName() {
    return "halide";
  }
}
