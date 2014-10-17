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
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * Compilation step for C interoperability files.
 */
public class OCamlCCompileStep extends ShellStep {

  public static class Args {
    public final Path ocamlCompiler;
    public final Path cCompiler;
    public final ImmutableList<String> flags;
    public final Path output;
    public final Path input;
    private final ImmutableMap<Path, SourcePath> includes;

    public Args(
        Path cCompiler,
        Path ocamlCompiler,
        Path output,
        Path input,
        ImmutableList<String> flags,
        ImmutableMap<Path, SourcePath> includes) {
      this.cCompiler = Preconditions.checkNotNull(cCompiler);
      this.ocamlCompiler = Preconditions.checkNotNull(ocamlCompiler);
      this.output = Preconditions.checkNotNull(output);
      this.input = Preconditions.checkNotNull(input);
      this.flags = Preconditions.checkNotNull(flags);
      this.includes = Preconditions.checkNotNull(includes);
    }

    RuleKey.Builder appendDetailsToRuleKey(SourcePathResolver resolver, RuleKey.Builder builder) {
      builder.set("cCompiler", cCompiler.toString());
      builder.set("ocamlCompiler", ocamlCompiler.toString());
      builder.set("output", output.toString());
      builder.set("input", input.toString());
      builder.set("flags", flags.toString());
      for (Path path : ImmutableSortedSet.copyOf(includes.keySet())) {
        SourcePath source = includes.get(path);
        builder.setInput("include(" + path + ")", resolver.getPath(source));
      }
      return builder;
    }

  }

  private final Args args;

  OCamlCCompileStep(Args args) {
    this.args = args;
  }

  @Override
  public String getShortName() {
    return "OCaml C compile";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return ImmutableList.<String>builder()
        .add(args.ocamlCompiler.toString())
        .addAll(OCamlCompilables.DEFAULT_OCAML_FLAGS)
        .add("-cc", args.cCompiler.toString())
        .add("-c")
        .add("-annot")
        .add("-bin-annot")
        .add("-o", args.output.toString())
        .add("-ccopt", "-Wall")
        .add("-ccopt", "-Wextra")
        .add("-ccopt", String.format("-o %s", args.output.toString()))
        .addAll(args.flags)
        .add(args.input.toString())
        .build();
  }

}
