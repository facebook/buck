/*
 * Copyright 2012-present Facebook, Inc.
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
import com.facebook.buck.step.CompositeStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.CommandSplitter;
import com.google.common.base.Functions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

/**
 * Create an object archive with ar.
 */
public class ArchiveStep extends CompositeStep {

  public ArchiveStep(
      Path workingDirectory,
      ImmutableList<String> archiver,
      Path output,
      ImmutableList<Path> inputs) {
    super(getArchiveCommandSteps(workingDirectory, archiver, output, inputs));
  }

  private static ImmutableList<Step> getArchiveCommandSteps(
      Path workingDirectory,
      ImmutableList<String> archiver,
      Path output,
      ImmutableList<Path> inputs) {
    ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();

    ImmutableList<String> archiveCommandPrefix = ImmutableList.<String>builder()
        .addAll(archiver)
        .add("rcs")
        .add(output.toString())
        .build();
    CommandSplitter commandSplitter = new CommandSplitter(archiveCommandPrefix);
    Iterable<String> arguments = FluentIterable.from(inputs)
        .transform(Functions.toStringFunction());
    for (final ImmutableList<String> command : commandSplitter.getCommandsForArguments(arguments)) {
      stepsBuilder.add(
          new ShellStep(workingDirectory) {
            @Override
            public String getShortName() {
              return "archive";
            }

            @Override
            protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
              return command;
            }
          });
    }

    return stepsBuilder.build();
  }

  @Override
  public String getShortName() {
    return "archive";
  }

}
