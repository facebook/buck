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
 * A yacc step which processes .mly files and outputs .ml and mli files
 */
public class OCamlYaccStep extends ShellStep {

  private final Path yaccCompiler;
  private final Path output;
  private final Path input;

  public OCamlYaccStep(
      Path yaccCompiler,
      Path output,
      Path input) {
    this.yaccCompiler = Preconditions.checkNotNull(yaccCompiler);
    this.output = Preconditions.checkNotNull(output);
    this.input = Preconditions.checkNotNull(input);
  }

  private static String stripExtension(String fileName) {
    int index = fileName.lastIndexOf('.');

    // if dot is in the first position,
    // we are dealing with a hidden file rather than an extension
    return (index > 0) ? fileName.substring(0, index) : fileName;
  }

  @Override
  public String getShortName() {
    return "OCaml yacc";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return ImmutableList.<String>builder()
        .add(yaccCompiler.toString())
        .add("-b", stripExtension(output.toString()))
        .add(input.toString())
        .build();
  }

}
