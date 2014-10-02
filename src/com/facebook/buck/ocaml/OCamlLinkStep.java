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
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

/**
 * OCaml linking step. Dependencies and inputs should be topologically ordered
 */
public class OCamlLinkStep extends ShellStep {

  private final Path ocamlCompiler;
  private final Path cCompiler;
  private final ImmutableList<String> flags;
  private final Path output;
  private final ImmutableList<String> depInput;
  private final ImmutableList<String> input;
  private final ImmutableList<String> aAndOInput;
  private final ImmutableList<String> ocamlInput;
  private final boolean isLibrary;
  private final boolean isBytecode;

  public OCamlLinkStep(
      Path cCompiler,
      Path ocamlCompiler,
      Path output,
      ImmutableList<String> depInput,
      ImmutableList<String> input,
      ImmutableList<String> flags,
      boolean isLibrary,
      boolean isBytecode) {
    this.isLibrary = isLibrary;
    this.isBytecode = isBytecode;
    this.ocamlCompiler = Preconditions.checkNotNull(ocamlCompiler);
    this.cCompiler = Preconditions.checkNotNull(cCompiler);
    this.flags = Preconditions.checkNotNull(flags);
    this.output = Preconditions.checkNotNull(output);
    this.depInput = Preconditions.checkNotNull(depInput);
    this.input = Preconditions.checkNotNull(input);

    ImmutableList.Builder<String> aAndOInputBuilder = ImmutableList.builder();
    ImmutableList.Builder<String> ocamlInputBuilder = ImmutableList.builder();

    for (String linkInput : this.depInput) {
      if (linkInput.endsWith(OCamlCompilables.OCAML_O) ||
          linkInput.endsWith(OCamlCompilables.OCAML_A)) {
        aAndOInputBuilder.add(linkInput);
      } else {
        if (!(isLibrary && linkInput.endsWith(OCamlCompilables.OCAML_CMXA))) {
          if (!isBytecode) {
            ocamlInputBuilder.add(linkInput);
          } else {
            String bytecodeLinkInput = linkInput.replaceAll(
                OCamlCompilables.OCAML_CMXA_REGEX,
                OCamlCompilables.OCAML_CMA);
            ocamlInputBuilder.add(bytecodeLinkInput);
          }
        }
      }
    }

    this.ocamlInput = ocamlInputBuilder.build();
    this.aAndOInput = aAndOInputBuilder.build().reverse();
  }

  @Override
  public String getShortName() {
    return "OCaml link";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return ImmutableList.<String>builder()
        .add(ocamlCompiler.toString())
        .addAll(OCamlCompilables.DEFAULT_OCAML_FLAGS)
        .add("-cc")
        .add(cCompiler.toString())
        .addAll((isLibrary ? Optional.of("-a") : Optional.<String>absent()).asSet())
        .addAll((!isLibrary && isBytecode ?
                Optional.of("-custom") :
                Optional.<String>absent()).asSet())
        .add("-o", output.toString())
        .addAll(flags)
        .addAll(this.ocamlInput)
        .addAll(this.input)
        .addAll(this.aAndOInput)
        .build();
  }
}
