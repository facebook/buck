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

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.CommandSplitter;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;

/**
 * Create an object archive with ar.
 */
public class ArchiveStep implements Step {

  private final ProjectFilesystem filesystem;
  private final ImmutableMap<String, String> environment;
  private final ImmutableList<String> archiver;
  private final Archive.Contents contents;
  private final Path output;
  private final ImmutableList<Path> inputs;

  public ArchiveStep(
      ProjectFilesystem filesystem,
      ImmutableMap<String, String> environment,
      ImmutableList<String> archiver,
      Archive.Contents contents,
      Path output,
      ImmutableList<Path> inputs) {
    Preconditions.checkArgument(!output.isAbsolute());
    // Our current support for thin archives requires that all the inputs are relative paths from
    // the same cell as the output.
    for (Path input : inputs) {
      Preconditions.checkArgument(!input.isAbsolute());
    }
    this.filesystem = filesystem;
    this.environment = environment;
    this.archiver = archiver;
    this.contents = contents;
    this.output = output;
    this.inputs = inputs;
  }

  private ImmutableList<String> getAllInputs() throws IOException {
    ImmutableList.Builder<String> allInputs = ImmutableList.builder();

    // Inputs can either be files or directories.  In the case of the latter, we add all files
    // found from a recursive search.
    for (Path input : inputs) {
      if (filesystem.isDirectory(input)) {
        // We make sure to sort the files we find under the directories so that we get
        // deterministic output.
        final Set<String> dirFiles = new TreeSet<>();
        filesystem.walkFileTree(
            filesystem.resolve(input),
            new SimpleFileVisitor<Path>() {
              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                dirFiles.add(file.toString());
                return FileVisitResult.CONTINUE;
              }
            });
        allInputs.addAll(dirFiles);
      } else {
        allInputs.add(input.toString());
      }
    }

    return allInputs.build();
  }

  private int runArchiver(
      ExecutionContext context,
      final ImmutableList<String> command)
      throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder();
    builder.directory(filesystem.getRootPath().toFile());
    builder.environment().putAll(environment);
    builder.command(command);
    ProcessExecutor.Result result = context.getProcessExecutor().execute(builder.start());
    if (result.getExitCode() != 0 && result.getStderr().isPresent()) {
      context.getBuckEventBus().post(ConsoleEvent.create(Level.SEVERE, result.getStderr().get()));
    }
    return result.getExitCode();
  }

  private String getArchiveOptions() {
    StringBuilder options = new StringBuilder();
    options.append("qc");
    if (contents == Archive.Contents.THIN) {
      options.append("T");
    }
    return options.toString();
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    ImmutableList<String> allInputs = getAllInputs();
    if (allInputs.isEmpty()) {
      filesystem.writeContentsToPath("!<arch>\n", output);
      return StepExecutionResult.SUCCESS;
    } else {
      ImmutableList<String> archiveCommandPrefix =
          ImmutableList.<String>builder()
              .addAll(archiver)
              .add(getArchiveOptions())
              .add(output.toString())
              .build();
      CommandSplitter commandSplitter = new CommandSplitter(archiveCommandPrefix);
      for (ImmutableList<String> command : commandSplitter.getCommandsForArguments(allInputs)) {
        int exitCode = runArchiver(context, command);
        if (exitCode != 0) {
          return StepExecutionResult.of(exitCode);
        }
      }
      return StepExecutionResult.SUCCESS;
    }
  }

  @Override
  public String getDescription(ExecutionContext context) {
    ImmutableList.Builder<String> command = ImmutableList.builder();
    command.add("ar", getArchiveOptions());
    command.add(output.toString());
    command.addAll(Iterables.transform(inputs, Functions.toStringFunction()));
    return Joiner.on(' ').join(command.build());
  }

  @Override
  public String getShortName() {
    return "archive";
  }

}
