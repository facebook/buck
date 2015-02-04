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
import com.facebook.buck.shell.Shell;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeExecutableStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * OCaml linking step. Dependencies and inputs should be topologically ordered
 */
public class OCamlDebugLauncherStep implements Step {

  public static class Args {
    public final Path ocamlDebug;
    public final Path bytecodeOutput;
    public final ImmutableList<OCamlLibrary> ocamlInput;
    public final ImmutableList<String> bytecodeIncludeFlags;

    public Args(
        Path ocamlDebug,
        Path bytecodeOutput,
        ImmutableList<OCamlLibrary> ocamlInput,
        ImmutableList<String> bytecodeIncludeFlags) {
      this.ocamlDebug = ocamlDebug;
      this.bytecodeOutput = bytecodeOutput;
      this.ocamlInput = ocamlInput;
      this.bytecodeIncludeFlags = bytecodeIncludeFlags;
    }

    public Path getOutput() {
      return Paths.get(bytecodeOutput.toString() + ".debug");
    }

    public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
      return builder.setReflectively("ocamlDebug", ocamlDebug.toString())
          .setReflectively("bytecodeOutput", bytecodeOutput.toString())
          .setReflectively("flags", bytecodeIncludeFlags);
    }
  }

  private final Args args;

  public OCamlDebugLauncherStep(Args args) {
    this.args = args;
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    String debugCmdStr = getDebugCmd();
    String debugLuancherScript = getDebugLauncherScript(debugCmdStr);

    WriteFileStep writeFile = new WriteFileStep(debugLuancherScript, args.getOutput());
    int writeExitCode = writeFile.execute(context);
    if (writeExitCode != 0) {
      return writeExitCode;
    }

    ShellStep chmod = new MakeExecutableStep(args.getOutput().toString());
    return chmod.execute(context);
  }

  private String getDebugLauncherScript(String debugCmdStr) {
    ImmutableList.Builder<String> debugFile = ImmutableList.builder();
    debugFile.add("#!/bin/sh");
    debugFile.add(debugCmdStr);

    return Joiner.on("\n").join(debugFile.build());
  }

  private String getDebugCmd() {
    ImmutableList.Builder<String> debugCmd = ImmutableList.builder();
    debugCmd.add("rlwrap");
    debugCmd.add(args.ocamlDebug.toString());

    Iterable<String> includesBytecodeDirs = FluentIterable.from(args.ocamlInput)
        .transformAndConcat(new Function<OCamlLibrary, Iterable<String>>() {
                              @Override
                              public Iterable<String> apply(OCamlLibrary input) {
                                return input.getBytecodeIncludeDirs();
                              }
                            });

    ImmutableList<String> includesBytecodeFlags = ImmutableList.copyOf(
        MoreIterables.zipAndConcat(
            Iterables.cycle(OCamlCompilables.OCAML_INCLUDE_FLAG),
            includesBytecodeDirs));

    debugCmd.addAll(includesBytecodeFlags);
    debugCmd.addAll(args.bytecodeIncludeFlags);

    debugCmd.add(args.bytecodeOutput.toString());
    return Shell.shellQuoteJoin(debugCmd.build(), " ") + " $@";
  }

  @Override
  public String getShortName() {
    return "OCaml debug launcher";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return getShortName();
  }

}
