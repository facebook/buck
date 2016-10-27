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

import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.OptionalCompat;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.Optional;

/**
 * OCaml linking step. Dependencies and inputs should be topologically ordered
 */
public class OCamlLinkStep extends ShellStep {

  public final ImmutableMap<String, String> environment;
  public final ImmutableList<String> cxxCompiler;
  public final ImmutableList<String> ocamlCompilerCommandPrefix;
  public final ImmutableList<String> flags;
  public final Optional<String> stdlib;
  public final Path output;
  public final ImmutableList<Arg> cDepInput;
  public final ImmutableList<Path> input;
  public final boolean isLibrary;
  public final boolean isBytecode;

  private final ImmutableList<String> ocamlInput;

  public OCamlLinkStep(
      Path workingDirectory,
      ImmutableMap<String, String> environment,
      ImmutableList<String> cxxCompiler,
      ImmutableList<String> ocamlCompilerCommandPrefix,
      ImmutableList<String> flags,
      Optional<String> stdlib,
      Path output,
      ImmutableList<Arg> depInput,
      ImmutableList<Arg> cDepInput,
      ImmutableList<Path> input,
      boolean isLibrary,
      boolean isBytecode) {
    super(workingDirectory);
    this.environment = environment;
    this.ocamlCompilerCommandPrefix = ocamlCompilerCommandPrefix;
    this.cxxCompiler = cxxCompiler;
    this.flags = flags;
    this.stdlib = stdlib;
    this.output = output;
    this.cDepInput = cDepInput;
    this.input = input;
    this.isLibrary = isLibrary;
    this.isBytecode = isBytecode;

    ImmutableList.Builder<String> ocamlInputBuilder = ImmutableList.builder();

    final String linkExt = isBytecode
        ? OCamlCompilables.OCAML_CMA
        : OCamlCompilables.OCAML_CMXA;

    for (String linkInput : Arg.stringify(depInput)) {
      if (isLibrary && linkInput.endsWith(linkExt)) {
        continue;
      }
      ocamlInputBuilder.add(linkInput);
    }

    ocamlInput = ocamlInputBuilder.build();
  }

  @Override
  public String getShortName() {
    return "OCaml link";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> cmd = ImmutableList.<String>builder()
        .addAll(ocamlCompilerCommandPrefix)
        .addAll(OCamlCompilables.DEFAULT_OCAML_FLAGS);

    if (stdlib.isPresent()) {
        cmd.add("-nostdlib", OCamlCompilables.OCAML_INCLUDE_FLAG, stdlib.get());
    }

    return cmd
        .add("-cc", cxxCompiler.get(0))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-ccopt"),
                cxxCompiler.subList(1, cxxCompiler.size())))
        .addAll(OptionalCompat.asSet(isLibrary ? Optional.of("-a") : Optional.empty()))
        .addAll(
            OptionalCompat.asSet(!isLibrary && isBytecode ?
                Optional.of("-custom") :
                Optional.empty()))
        .add("-o", output.toString())
        .addAll(flags)
        .addAll(ocamlInput)
        .addAll(input.stream().map(Object::toString).iterator())
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-cclib"),
                FluentIterable.from(cDepInput)
                    .transformAndConcat(Arg::stringifyList)))
        .build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return environment;
  }
}
