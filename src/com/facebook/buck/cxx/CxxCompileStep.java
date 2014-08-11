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
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

/**
 * A step that preprocesses, compiles, and assembles C/C++ sources.
 */
public class CxxCompileStep extends ShellStep {

  private final Path compiler;
  private final ImmutableList<String> flags;
  private final Path output;
  private final Path input;
  private final ImmutableList<Path> includes;
  private final ImmutableList<Path> systemIncludes;

  public CxxCompileStep(
      Path compiler,
      ImmutableList<String> flags,
      Path output,
      Path input,
      ImmutableList<Path> includes,
      ImmutableList<Path> systemIncludes) {
    this.compiler = Preconditions.checkNotNull(compiler);
    this.flags = Preconditions.checkNotNull(flags);
    this.output = Preconditions.checkNotNull(output);
    this.input = Preconditions.checkNotNull(input);
    this.includes = Preconditions.checkNotNull(includes);
    this.systemIncludes = Preconditions.checkNotNull(systemIncludes);
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
        .add("-g")
        .addAll(flags)
        .add("-o", output.toString())
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-I"),
                Iterables.transform(includes, Functions.toStringFunction())))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-isystem"),
                Iterables.transform(systemIncludes, Functions.toStringFunction())))
        .add(input.toString())
        .build();
  }

}
