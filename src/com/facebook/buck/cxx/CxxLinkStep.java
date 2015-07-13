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

package com.facebook.buck.cxx;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

public class CxxLinkStep extends ShellStep {

  private final ImmutableList<String> linker;
  private final Path output;
  private final ImmutableList<String> args;
  private final ImmutableSet<Path> frameworkRoots;

  public CxxLinkStep(
      ImmutableList<String> linker,
      Path output,
      ImmutableList<String> args,
      ImmutableSet<Path> frameworkRoots) {
    this.linker = linker;
    this.output = output;
    this.args = args;
    this.frameworkRoots = frameworkRoots;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return ImmutableList.<String>builder()
        .addAll(linker)
        .add("-o", output.toString())
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-F"),
                Iterables.transform(frameworkRoots, Functions.toStringFunction())))
        .addAll(args)
        .build();
  }

  @Override
  public String getShortName() {
    return "c++ link";
  }

}
