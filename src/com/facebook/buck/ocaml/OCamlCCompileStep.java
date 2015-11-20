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

import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.MoreIterables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

/**
 * Compilation step for C interoperability files.
 */
public class OCamlCCompileStep extends ShellStep {

  private final SourcePathResolver resolver;
  private final Args args;

  OCamlCCompileStep(SourcePathResolver resolver, Path workingDirectory, Args args) {
    super(workingDirectory);
    this.resolver = resolver;
    this.args = args;
  }

  @Override
  public String getShortName() {
    return "OCaml C compile";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return ImmutableList.<String>builder()
        .addAll(args.ocamlCompiler.getCommandPrefix(resolver))
        .addAll(OCamlCompilables.DEFAULT_OCAML_FLAGS)
        .add("-cc", args.cCompiler.get(0))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-ccopt"),
                args.cCompiler.subList(1, args.cCompiler.size())))
        .add("-c")
        .add("-annot")
        .add("-bin-annot")
        .add("-o", args.output.toString())
        .add("-ccopt", "-Wall")
        .add("-ccopt", "-Wextra")
        .add("-ccopt", String.format("-o %s", args.output.toString()))
        .addAll(args.flags)
        .add(resolver.getAbsolutePath(args.input).toString())
        .build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return args.environment;
  }

  public static class Args implements RuleKeyAppendable {
    public final ImmutableMap<String, String> environment;
    public final Tool ocamlCompiler;
    public final ImmutableList<String> cCompiler;
    public final ImmutableList<String> flags;
    public final Path output;
    public final SourcePath input;
    public final ImmutableMap<Path, SourcePath> includes;

    public Args(
        ImmutableMap<String, String> environment,
        ImmutableList<String> cCompiler,
        Tool ocamlCompiler,
        Path output,
        SourcePath input,
        ImmutableList<String> flags,
        ImmutableMap<Path, SourcePath> includes) {
      this.environment = environment;
      this.cCompiler = cCompiler;
      this.ocamlCompiler = ocamlCompiler;
      this.output = output;
      this.input = input;
      this.flags = flags;
      this.includes = includes;
    }

    @Override
    public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
      builder.setReflectively("cCompiler", cCompiler);
      builder.setReflectively("ocamlCompiler", ocamlCompiler);
      builder.setReflectively("output", output.toString());
      builder.setReflectively("input", input);
      builder.setReflectively("flags", flags);
      builder.setReflectively("includes", includes.values());
      return builder;
    }
  }

}
