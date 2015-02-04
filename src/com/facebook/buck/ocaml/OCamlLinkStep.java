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
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

/**
 * OCaml linking step. Dependencies and inputs should be topologically ordered
 */
public class OCamlLinkStep extends ShellStep {

  public static class Args {
    public final Path ocamlCompiler;
    public final ImmutableList<String> cxxCompiler;
    public final ImmutableList<String> flags;
    public final Path output;
    public final ImmutableList<String> depInput;
    public final ImmutableList<String> input;
    public final boolean isLibrary;
    public final boolean isBytecode;

    public Args(
        ImmutableList<String> cxxCompiler,
        Path ocamlCompiler,
        Path output,
        ImmutableList<String> depInput,
        ImmutableList<String> input,
        ImmutableList<String> flags,
        boolean isLibrary,
        boolean isBytecode) {
      this.isLibrary = isLibrary;
      this.isBytecode = isBytecode;
      this.ocamlCompiler = ocamlCompiler;
      this.cxxCompiler = cxxCompiler;
      this.flags = flags;
      this.output = output;
      this.depInput = depInput;
      this.input = input;
    }

    public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
      return builder.setReflectively("cxxCompiler", cxxCompiler.toString())
          .setReflectively("ocamlCompiler", ocamlCompiler.toString())
          .setReflectively("output", output.toString())
          .setReflectively("depInput", depInput)
          .setReflectively("input", input)
          .setReflectively("flags", flags)
          .setReflectively("isLibrary", isLibrary)
          .setReflectively("isBytecode", isBytecode);
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
  private final ImmutableList<String> systemSoLibs;

  public OCamlLinkStep(Args args) {
    this.args = args;

    ImmutableList.Builder<String> aAndOInputBuilder = ImmutableList.builder();
    ImmutableList.Builder<String> ocamlInputBuilder = ImmutableList.builder();
    ImmutableList.Builder<String> systemSoLibsBuilder = ImmutableList.builder();

    for (String linkInput : this.args.depInput) {
      if (linkInput.endsWith(OCamlCompilables.OCAML_O) ||
          linkInput.endsWith(OCamlCompilables.OCAML_A)) {
        aAndOInputBuilder.add(linkInput);
      } else if (linkInput.endsWith(OCamlCompilables.SYSTEM_SO)) {
        systemSoLibsBuilder.add(linkInput);
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
    this.systemSoLibs = systemSoLibsBuilder.build();
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
        .add("-cc", args.cxxCompiler.get(0))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-ccopt"),
                args.cxxCompiler.subList(1, args.cxxCompiler.size())))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-ccopt"),
                systemSoLibs))
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
