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

package com.facebook.buck.cxx;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

/**
 * A step that compiles and assembles C/C++ sources.
 */
public class CxxCompileStep extends ShellStep {

  private final Path compiler;
  private final ImmutableList<String> flags;
  private final Path output;
  private final Path input;

  public CxxCompileStep(
      Path compiler,
      ImmutableList<String> flags,
      Path output,
      Path input) {
    this.compiler = compiler;
    this.flags = flags;
    this.output = output;
    this.input = input;
  }

  @Override
  public String getShortName() {
    return "c++ compile";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return ImmutableList.<String>builder()
        .add(compiler.toString())
        .add("-c")
        .addAll(flags)
        .add("-o", output.toString())
        .add(input.toString())
        .build();
  }

}
