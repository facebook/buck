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
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

import javax.annotation.Nullable;

public class CxxLinkStep extends ShellStep {

  private final ImmutableList<String> linker;
  private final Path output;
  private final ImmutableList<String> args;
  private final ImmutableSet<Path> frameworkRoots;
  private final ImmutableSet<Path> librarySearchDirectories;
  private final ImmutableSet<String> libraries;

  public CxxLinkStep(
      Path workingDirectory,
      ImmutableList<String> linker,
      Path output,
      ImmutableList<String> args,
      ImmutableSet<Path> frameworkRoots,
      ImmutableSet<Path> librarySearchDirectories,
      ImmutableSet<String> libraries) {
    super(workingDirectory);
    this.linker = linker;
    this.output = output;
    this.args = args;
    this.frameworkRoots = frameworkRoots;
    this.librarySearchDirectories = librarySearchDirectories;
    this.libraries = libraries;
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
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-L"),
                Iterables.transform(librarySearchDirectories, Functions.toStringFunction())))
        .addAll(
            Iterables.transform(
                libraries, new Function<String, String>() {
                  @Nullable
                  @Override
                  public String apply(String input) {
                    return "-l" + input;
                  }
                }))
        .addAll(args)
        .build();
  }

  @Override
  public String getShortName() {
    return "c++ link";
  }

}
