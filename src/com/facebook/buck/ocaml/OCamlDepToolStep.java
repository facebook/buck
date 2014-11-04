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

package com.facebook.buck.ocaml;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

/**
 * This step runs ocamldep tool to compute dependencies among source files (*.mli and *.ml)
 */
public class OCamlDepToolStep extends ShellStep {

  private final ImmutableList<Path> input;
  private final ImmutableList<String> flags;
  private Path ocamlDepTool;

  public OCamlDepToolStep(
      Path ocamlDepTool,
      ImmutableList<Path> input, ImmutableList<String> flags) {
    this.ocamlDepTool = ocamlDepTool;
    this.flags = flags;
    this.input = input;
  }

  @Override
  public String getShortName() {
    return "OCaml dependency";
  }

  @Override
  protected void addOptions(
      ExecutionContext context,
      ImmutableSet.Builder<ProcessExecutor.Option> options) {
    // We need this else we get output with color codes which confuses parsing
    options.add(ProcessExecutor.Option.EXPECTING_STD_ERR);
    options.add(ProcessExecutor.Option.EXPECTING_STD_OUT);
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return ImmutableList.<String>builder()
        .add(ocamlDepTool.toString())
        .add("-one-line")
        .add("-native")
        .addAll(flags)
        .addAll(Iterables.transform(input, Functions.toStringFunction()))
        .build();
  }
}
