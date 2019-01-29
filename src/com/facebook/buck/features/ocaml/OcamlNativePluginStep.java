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

package com.facebook.buck.features.ocaml;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.ShellStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;

/**
 * This step is run when `native_plugin=True` is set in an `ocaml_library` rule.
 *
 * <p>This builds a `.cmxs` file from the corresponding `.cmo`/`cma` file that was built as part of
 * the static library compilation.
 *
 * <p>`.cmxs` files are generally used as dynamically-linked plugins for native-compiled ocaml. See
 * the built-in `Dynlink` ocaml module for more info.
 */
public class OcamlNativePluginStep extends ShellStep {

  public final ImmutableMap<String, String> environment;
  public final ImmutableList<String> cxxCompiler;
  public final ImmutableList<String> ocamlCompilerCommandPrefix;
  public final ImmutableList<String> flags;
  public final Optional<String> stdlib;
  public final Path output;
  public final ImmutableList<Arg> cDepInput;
  public final ImmutableList<Path> input;

  private final ImmutableList<String> ocamlInput;

  public OcamlNativePluginStep(
      Path workingDirectory,
      ImmutableMap<String, String> environment,
      ImmutableList<String> cxxCompiler,
      ImmutableList<String> ocamlCompilerCommandPrefix,
      ImmutableList<String> flags,
      Optional<String> stdlib,
      Path output,
      ImmutableList<Arg> cDepInput,
      ImmutableList<Path> input,
      ImmutableList<String> ocamlInput) {
    super(workingDirectory);
    this.environment = environment;
    this.ocamlCompilerCommandPrefix = ocamlCompilerCommandPrefix;
    this.cxxCompiler = cxxCompiler;
    this.flags = flags;
    this.stdlib = stdlib;
    this.output = output;
    this.cDepInput = cDepInput;
    this.input = input;
    this.ocamlInput = ocamlInput;
  }

  @Override
  public String getShortName() {
    return "OCaml native dynamic library";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> cmd =
        ImmutableList.<String>builder()
            .addAll(ocamlCompilerCommandPrefix)
            .addAll(OcamlCompilables.DEFAULT_OCAML_FLAGS);

    if (stdlib.isPresent()) {
      cmd.add("-nostdlib", OcamlCompilables.OCAML_INCLUDE_FLAG, stdlib.get());
    }

    return cmd.add("-shared")
        .add("-o", output.toString())
        .addAll(flags)
        .addAll(ocamlInput)
        .addAll(input.stream().map(Object::toString).iterator())
        .build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return environment;
  }
}
