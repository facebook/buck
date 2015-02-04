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
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

/**
 * A yacc step which processes .mly files and outputs .ml and mli files
 */
public class OCamlYaccStep extends ShellStep {

  public static class Args {
    public final Path yaccCompiler;
    public final Path output;
    public final Path input;
    public Args(
        Path yaccCompiler,
        Path output,
        Path input
    ) {
      this.yaccCompiler = yaccCompiler;
      this.output = output;
      this.input = input;
    }

    public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
      return builder.setReflectively("yaccCompiler", yaccCompiler.toString())
          .setReflectively("output", output.toString())
          .setReflectively("input", input.toString());
    }
  }

  private final Args args;

  public OCamlYaccStep(Args args) {
    this.args = args;
  }

  @Override
  public String getShortName() {
    return "OCaml yacc";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return ImmutableList.<String>builder()
        .add(args.yaccCompiler.toString())
        .add("-b", OCamlUtil.stripExtension(args.output.toString()))
        .add(args.input.toString())
        .build();
  }

}
