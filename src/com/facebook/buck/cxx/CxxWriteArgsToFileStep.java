/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * This step takes a list of args, stringify, escape them (if escaper is present), and finally store
 * to a file {@link #argFilePath}.
 */
class CxxWriteArgsToFileStep implements Step {

  private final Path argFilePath;
  private final ImmutableList<String> argFileContents;

  public static CxxWriteArgsToFileStep create(
      Path argFilePath,
      ImmutableList<Arg> args,
      Optional<Function<String, String>> escaper,
      Path currentCellPath,
      SourcePathResolver pathResolver) {
    ImmutableList<String> argFileContents = stringify(args, currentCellPath, pathResolver);
    if (escaper.isPresent()) {
      argFileContents =
          argFileContents.stream().map(escaper.get()).collect(ImmutableList.toImmutableList());
    }
    return new CxxWriteArgsToFileStep(argFilePath, argFileContents);
  }

  private CxxWriteArgsToFileStep(Path argFilePath, ImmutableList<String> argFileContents) {
    this.argFilePath = argFilePath;
    this.argFileContents = argFileContents;
  }

  @VisibleForTesting
  ImmutableList<String> getArgFileContents() {
    return argFileContents;
  }

  static ImmutableList<String> stringify(
      ImmutableCollection<Arg> args, Path currentCellPath, SourcePathResolver pathResolver) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    for (Arg arg : args) {
      if (arg instanceof FileListableLinkerInputArg) {
        ((FileListableLinkerInputArg) arg)
            .appendToCommandLineRel(builder::add, currentCellPath, pathResolver);
      } else if (arg instanceof SourcePathArg) {
        ((SourcePathArg) arg).appendToCommandLineRel(builder::add, currentCellPath, pathResolver);
      } else {
        arg.appendToCommandLine(builder::add, pathResolver);
      }
    }
    return builder.build();
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    if (Files.notExists(argFilePath.getParent())) {
      Files.createDirectories(argFilePath.getParent());
    }
    MostFiles.writeLinesToFile(argFileContents, argFilePath);
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "write args to file";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "Write list of args to file, use string escape if provided.";
  }
}
