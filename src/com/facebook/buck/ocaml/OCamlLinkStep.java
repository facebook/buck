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

import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

/**
 * OCaml linking step. Dependencies and inputs should be topologically ordered
 */
public class OCamlLinkStep extends ShellStep {

  public static class Args {
    public final Path ocamlCompiler;
    public final Path cxxCompiler;
    public final ImmutableList<String> flags;
    public final Path output;
    public final ImmutableList<String> depInput;
    public final ImmutableList<String> input;
    public final boolean isLibrary;
    public final boolean isBytecode;

    public Args(
        Path cxxCompiler,
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
      this.cxxCompiler = Preconditions.checkNotNull(cxxCompiler);
      this.flags = Preconditions.checkNotNull(flags);
      this.output = Preconditions.checkNotNull(output);
      this.depInput = Preconditions.checkNotNull(depInput);
      this.input = Preconditions.checkNotNull(input);
    }

    public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
      return builder.set("cxxCompiler", cxxCompiler.toString())
          .set("ocamlCompiler", ocamlCompiler.toString())
          .set("output", output.toString())
          .set("depInput", depInput)
          .set("input", input)
          .set("flags", flags)
          .set("isLibrary", isLibrary)
          .set("isBytecode", isBytecode);
    }

    public ImmutableSet<Path> getAllOutputs() {
      if (isLibrary) {
        if (!isBytecode) {
          return OCamlUtil.getExtensionVariants(
              output,
              OCamlCompilables.OCAML_A,
              OCamlCompilables.OCAML_CMXA);
        } else {
          return ImmutableSet.of(output);
        }
      } else {
        return ImmutableSet.of(output);
      }
    }
  }

  private final Args args;

  private final ImmutableList<String> aAndOInput;
  private final ImmutableList<String> ocamlInput;

  public OCamlLinkStep(Args args) {
    this.args = args;

    ImmutableList.Builder<String> aAndOInputBuilder = ImmutableList.builder();
    ImmutableList.Builder<String> ocamlInputBuilder = ImmutableList.builder();

    for (String linkInput : this.args.depInput) {
      if (linkInput.endsWith(OCamlCompilables.OCAML_O) ||
          linkInput.endsWith(OCamlCompilables.OCAML_A)) {
        aAndOInputBuilder.add(linkInput);
      } else {
        if (!(this.args.isLibrary && linkInput.endsWith(OCamlCompilables.OCAML_CMXA))) {
          if (!this.args.isBytecode) {
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
        .add(args.ocamlCompiler.toString())
        .addAll(OCamlCompilables.DEFAULT_OCAML_FLAGS)
        .add("-cc")
        .add(args.cxxCompiler.toString())
        .addAll((args.isLibrary ? Optional.of("-a") : Optional.<String>absent()).asSet())
        .addAll((!args.isLibrary && args.isBytecode ?
                Optional.of("-custom") :
                Optional.<String>absent()).asSet())
        .add("-o", args.output.toString())
        .addAll(args.flags)
        .addAll(ocamlInput)
        .addAll(args.input)
        .addAll(aAndOInput)
        .build();
  }
}
