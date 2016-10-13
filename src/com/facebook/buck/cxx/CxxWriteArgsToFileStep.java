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

import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * This step takes a list of args, stringify, escape them (if {@link #escaper} is present), and
 * finally store to a file {@link #argFilePath}.
 */
public class CxxWriteArgsToFileStep implements Step {

  private final Path argFilePath;
  private final ImmutableList<Arg> args;
  private final Optional<Function<String, String>> escaper;

  public CxxWriteArgsToFileStep(
      Path argFilePath,
      ImmutableList<Arg> args,
      Optional<Function<String, String>> escaper) {
    this.argFilePath = argFilePath;
    this.args = args;
    this.escaper = escaper;
  }

  private void createArgFile() throws IOException {
    if (Files.notExists(argFilePath.getParent())) {
      Files.createDirectories(argFilePath.getParent());
    }
    ImmutableList<String> argFileContents = Arg.stringify(args);
    if (escaper.isPresent()) {
      argFileContents = FluentIterable.from(argFileContents)
          .transform(escaper.get())
          .toList();
    }
    MoreFiles.writeLinesToFile(argFileContents, argFilePath);
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    createArgFile();
    return StepExecutionResult.SUCCESS;
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
