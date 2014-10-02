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
 * Compilation step for C interoperability files.
 */
public class OCamlCCompileStep extends ShellStep {

  private final Path ocamlCompiler;
  private final Path cCompiler;
  private final ImmutableList<String> flags;
  private final Path output;
  private final Path input;

  public OCamlCCompileStep(
      Path cCompiler,
      Path ocamlCompiler,
      Path output,
      Path input,
      ImmutableList<String> flags) {
    this.ocamlCompiler = Preconditions.checkNotNull(ocamlCompiler);
    this.cCompiler = Preconditions.checkNotNull(cCompiler);
    this.flags = Preconditions.checkNotNull(flags);
    this.output = Preconditions.checkNotNull(output);
    this.input = Preconditions.checkNotNull(input);
  }

  @Override
  public String getShortName() {
    return "OCaml C compile";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return ImmutableList.<String>builder()
        .add(ocamlCompiler.toString())
        .addAll(OCamlCompilables.DEFAULT_OCAML_FLAGS)
        .add("-cc", cCompiler.toString())
        .add("-c")
        .add("-annot")
        .add("-bin-annot")
        .add("-o", output.toString())
        .add("-ccopt", "-Wall")
        .add("-ccopt", "-Wextra")
        .add("-ccopt", String.format("-o %s", output.toString()))
        .addAll(flags)
        .add(input.toString())
        .build();
  }

}
