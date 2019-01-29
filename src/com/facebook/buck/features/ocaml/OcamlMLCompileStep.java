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
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import java.nio.file.Path;
import java.util.Optional;

/** A compilation step for .ml and .mli files */
public class OcamlMLCompileStep extends ShellStep {

  public static class Args implements AddsToRuleKey {

    public final ImmutableMap<String, String> environment;
    @AddToRuleKey final Tool ocamlCompiler;
    @AddToRuleKey final ImmutableList<String> cCompiler;
    @AddToRuleKey final ImmutableList<Arg> flags;
    @AddToRuleKey final Optional<String> stdlib;

    @AddToRuleKey(stringify = true)
    final Path output;
    // TODO (cjhopman): This should be a SourcePath
    @AddToRuleKey(stringify = true)
    final Path input;

    public Args(
        ImmutableMap<String, String> environment,
        ImmutableList<String> cCompiler,
        Tool ocamlCompiler,
        Optional<String> stdlib,
        Path output,
        Path input,
        ImmutableList<Arg> flags) {
      this.environment = environment;
      this.ocamlCompiler = ocamlCompiler;
      this.stdlib = stdlib;
      this.cCompiler = cCompiler;
      this.flags = flags;
      this.output = output;
      this.input = input;
    }

    public ImmutableSet<Path> getAllOutputs() {
      String outputStr = output.toString();
      if (outputStr.endsWith(OcamlCompilables.OCAML_CMX)) {
        return OcamlUtil.getExtensionVariants(
            output,
            OcamlCompilables.OCAML_CMX,
            OcamlCompilables.OCAML_O,
            OcamlCompilables.OCAML_CMI,
            OcamlCompilables.OCAML_CMT,
            OcamlCompilables.OCAML_ANNOT);
      } else if (outputStr.endsWith(OcamlCompilables.OCAML_CMI)) {
        return OcamlUtil.getExtensionVariants(
            output, OcamlCompilables.OCAML_CMI, OcamlCompilables.OCAML_CMTI);
      } else {
        Preconditions.checkState(outputStr.endsWith(OcamlCompilables.OCAML_CMO));
        return OcamlUtil.getExtensionVariants(
            output,
            OcamlCompilables.OCAML_CMO,
            OcamlCompilables.OCAML_CMI,
            OcamlCompilables.OCAML_CMT,
            OcamlCompilables.OCAML_ANNOT);
      }
    }
  }

  private final Args args;
  private final SourcePathResolver resolver;

  public OcamlMLCompileStep(Path workingDirectory, SourcePathResolver resolver, Args args) {
    super(workingDirectory);
    this.resolver = resolver;
    this.args = args;
  }

  @Override
  public String getShortName() {
    return "OCaml compile";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> cmd =
        ImmutableList.<String>builder()
            .addAll(args.ocamlCompiler.getCommandPrefix(resolver))
            .addAll(OcamlCompilables.DEFAULT_OCAML_FLAGS);

    if (args.stdlib.isPresent()) {
      cmd.add("-nostdlib", OcamlCompilables.OCAML_INCLUDE_FLAG, args.stdlib.get());
    }

    String ext = Files.getFileExtension(args.input.toString());
    String dotExt = "." + ext;
    boolean isImplementation =
        dotExt.equals(OcamlCompilables.OCAML_ML) || dotExt.equals(OcamlCompilables.OCAML_RE);
    boolean isReason =
        dotExt.equals(OcamlCompilables.OCAML_RE) || dotExt.equals(OcamlCompilables.OCAML_REI);

    cmd.add("-cc", args.cCompiler.get(0))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-ccopt"), args.cCompiler.subList(1, args.cCompiler.size())))
        .add("-c")
        .add("-annot")
        .add("-bin-annot")
        .add("-o", args.output.toString())
        .addAll(Arg.stringify(args.flags, resolver));

    if (isReason && isImplementation) {
      cmd.add("-pp").add("refmt").add("-intf-suffix").add("rei").add("-impl");
    }
    if (isReason && !isImplementation) {
      cmd.add("-pp").add("refmt").add("-intf");
    }

    cmd.add(args.input.toString());
    return cmd.build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return args.environment;
  }
}
