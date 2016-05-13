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

import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.log.Logger;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Prepares argfile for the CxxLinkStep, so all arguments to the linker will be stored in a
 * single file. CxxLinkStep then would pass it to the linker via @path/to/argfile.txt command.
 * This allows us to break the constraints that command line sets for the maximum length of
 * the commands.
 */
public class CxxPrepareForLinkStep implements Step {

  private final Path argFilePath;
  private final Path output;
  private final ImmutableList<String> args;

  private static final Logger LOG = Logger.get(CxxPrepareForLinkStep.class);

  public CxxPrepareForLinkStep(
      Path argFilePath,
      Path output,
      ImmutableList<String> args) {
    this.argFilePath = argFilePath;
    this.output = output;
    this.args = args;
  }

  private void prepareArgFileContents() throws IOException {
    cleanupPreviousArgFile();
    createArgFile();
  }

  private void createArgFile() throws IOException {
    if (Files.notExists(argFilePath.getParent())) {
      Files.createDirectories(argFilePath.getParent());
    }
    try (PrintWriter pr = new PrintWriter(argFilePath.toString())) {
      LOG.verbose("Creating argfile");
      ImmutableList<String> argFileContents = getLinkerOptions();
      for (String option : FluentIterable.from(argFileContents).transform(Javac.ARGFILES_ESCAPER)) {
        pr.println(option);
      }
    }
  }

  private void cleanupPreviousArgFile() throws IOException {
    LOG.verbose("Cleaning up previous argfile at " + argFilePath.toString());
    Files.deleteIfExists(argFilePath);
  }

  private ImmutableList<String> getLinkerOptions() {
    return ImmutableList.<String>builder()
        .add("-o", output.toString())
        .addAll(args)
        .build();
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    prepareArgFileContents();
    return StepExecutionResult.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "cxx prepare for link step";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "prepares arg file that will be passed to the linker";
  }
}
