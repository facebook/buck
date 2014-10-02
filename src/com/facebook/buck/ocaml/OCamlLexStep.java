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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

/**
 * Runs ocamllex to generate .ml files from .mll
 */
public class OCamlLexStep extends ShellStep {

  private final Path lexCompiler;
  private final Path output;
  private final Path input;

  public OCamlLexStep(
      Path lexCompiler,
      Path output,
      Path input) {
    this.lexCompiler = Preconditions.checkNotNull(lexCompiler);
    this.output = Preconditions.checkNotNull(output);
    this.input = Preconditions.checkNotNull(input);
  }

  @Override
  public String getShortName() {
    return "OCaml lex";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return ImmutableList.<String>builder()
        .add(lexCompiler.toString())
        .add("-o", output.toString())
        .add(input.toString())
        .build();
  }

}
