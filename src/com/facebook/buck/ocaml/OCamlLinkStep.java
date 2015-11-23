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
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

/**
 * OCaml linking step. Dependencies and inputs should be topologically ordered
 */
public class OCamlLinkStep extends ShellStep {

  public final ImmutableMap<String, String> environment;
  public final ImmutableList<String> cxxCompiler;
  public final ImmutableList<String> ocamlCompilerCommandPrefix;
  public final ImmutableList<String> flags;
  public final Path output;
  public final ImmutableList<Arg> depInput;
  public final ImmutableList<Arg> nativeDepInput;
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
      Path output,
      ImmutableList<Arg> depInput,
      ImmutableList<Arg> nativeDepInput,
      ImmutableList<Path> input,
      boolean isLibrary,
      boolean isBytecode) {
    super(workingDirectory);
    this.environment = environment;
    this.ocamlCompilerCommandPrefix = ocamlCompilerCommandPrefix;
    this.cxxCompiler = cxxCompiler;
    this.flags = flags;
    this.output = output;
    this.depInput = depInput;
    this.nativeDepInput = nativeDepInput;
    this.input = input;
    this.isLibrary = isLibrary;
    this.isBytecode = isBytecode;

    ImmutableList.Builder<String> ocamlInputBuilder = ImmutableList.builder();

    for (Arg linkInputArg : depInput) {
      String linkInput = linkInputArg.stringify();
      if (isLibrary && linkInput.endsWith(OCamlCompilables.OCAML_CMXA)) {
        continue;
      }
      if (isBytecode) {
        linkInput = linkInput.replaceAll(
            OCamlCompilables.OCAML_CMXA_REGEX,
            OCamlCompilables.OCAML_CMA);
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
    return ImmutableList.<String>builder()
        .addAll(ocamlCompilerCommandPrefix)
        .addAll(OCamlCompilables.DEFAULT_OCAML_FLAGS)
        .add("-cc", cxxCompiler.get(0))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-ccopt"),
                cxxCompiler.subList(1, cxxCompiler.size())))
        .addAll((isLibrary ? Optional.of("-a") : Optional.<String>absent()).asSet())
        .addAll(
            (!isLibrary && isBytecode ?
                Optional.of("-custom") :
                Optional.<String>absent()).asSet())
        .add("-o", output.toString())
        .addAll(flags)
        .addAll(ocamlInput)
        .addAll(FluentIterable.from(input).transform(Functions.toStringFunction()))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-cclib"),
                FluentIterable.from(nativeDepInput)
                    .transform(Arg.stringifyFunction())))
        .build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return environment;
  }
}
