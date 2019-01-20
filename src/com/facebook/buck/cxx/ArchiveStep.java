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

import com.facebook.buck.cxx.toolchain.Archiver;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.CommandSplitter;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;

/** Create an object archive with ar. */
class ArchiveStep implements Step {

  private final ProjectFilesystem filesystem;
  private final ImmutableMap<String, String> environment;
  private final ImmutableList<String> archiverCommand;
  private final ImmutableList<String> archiverFlags;
  private final ImmutableList<String> archiverExtraFlags;
  private final Path output;
  private final ImmutableList<Path> inputs;
  private final Archiver archiver;
  private final Path scratchDir;

  public ArchiveStep(
      ProjectFilesystem filesystem,
      ImmutableMap<String, String> environment,
      ImmutableList<String> archiverCommand,
      ImmutableList<String> archiverFlags,
      ImmutableList<String> archiverExtraFlags,
      Path output,
      ImmutableList<Path> inputs,
      Archiver archiver,
      Path scratchDir) {
    Preconditions.checkArgument(!output.isAbsolute());
    // Our current support for thin archives requires that all the inputs are relative paths from
    // the same cell as the output.
    for (Path input : inputs) {
      Preconditions.checkArgument(!input.isAbsolute());
    }
    this.filesystem = filesystem;
    this.environment = environment;
    this.archiverCommand = archiverCommand;
    this.archiverFlags = archiverFlags;
    this.archiverExtraFlags = archiverExtraFlags;
    this.output = output;
    this.inputs = inputs;
    this.archiver = archiver;
    this.scratchDir = scratchDir;
  }

  private ImmutableList<String> getAllInputs() throws IOException {
    ImmutableList.Builder<String> allInputs = ImmutableList.builder();

    // Inputs can either be files or directories.  In the case of the latter, we add all files
    // found from a recursive search.
    for (Path input : inputs) {
      if (filesystem.isDirectory(input)) {
        // We make sure to sort the files we find under the directories so that we get
        // deterministic output.
        Set<String> dirFiles = new TreeSet<>();
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

  private ProcessExecutor.Result runArchiver(
      ExecutionContext context, ImmutableList<String> command)
      throws IOException, InterruptedException {
    Map<String, String> env = new HashMap<>(context.getEnvironment());
    env.putAll(environment);
    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .setDirectory(filesystem.getRootPath())
            .setEnvironment(ImmutableMap.copyOf(env))
            .setCommand(command)
            .build();
    ProcessExecutor.Result result = context.getProcessExecutor().launchAndExecute(params);
    if (result.getExitCode() != 0 && result.getStderr().isPresent()) {
      context.getBuckEventBus().post(ConsoleEvent.create(Level.SEVERE, result.getStderr().get()));
    }
    return result;
  }

  private Path getArgfile() {
    return filesystem.resolve(scratchDir).resolve("ar.argsfile");
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    ImmutableList<String> allInputs = getAllInputs();
    if (allInputs.isEmpty()) {
      filesystem.writeContentsToPath("!<arch>\n", output);
      return StepExecutionResults.SUCCESS;
    } else {
      ImmutableList<String> outputArgs = archiver.outputArgs(output.toString());
      if (archiver.isArgfileRequired()) {
        Iterable<String> argfileLines =
            Iterables.concat(archiverFlags, archiverExtraFlags, outputArgs, allInputs);
        Path argfile = getArgfile();
        filesystem.writeLinesToPath(argfileLines, argfile);
        ImmutableList<String> command =
            ImmutableList.<String>builder().addAll(archiverCommand).add("@" + argfile).build();
        return StepExecutionResult.of(runArchiver(context, command));
      } else {
        ImmutableList<String> archiveCommandPrefix =
            ImmutableList.<String>builder()
                .addAll(archiverCommand)
                .addAll(archiverFlags)
                .addAll(archiverExtraFlags)
                .addAll(outputArgs)
                .build();
        CommandSplitter commandSplitter = new CommandSplitter(archiveCommandPrefix);
        for (ImmutableList<String> command : commandSplitter.getCommandsForArguments(allInputs)) {
          ProcessExecutor.Result result = runArchiver(context, command);
          if (result.getExitCode() != 0) {
            return StepExecutionResult.of(result);
          }
        }
        return StepExecutionResults.SUCCESS;
      }
    }
  }

  @Override
  public String getDescription(ExecutionContext context) {
    ImmutableList.Builder<String> command =
        archiver.isArgfileRequired()
            ? ImmutableList.<String>builder().addAll(archiverCommand).add("@" + getArgfile())
            : ImmutableList.<String>builder()
                .add("ar")
                .addAll(archiverFlags)
                .addAll(archiverExtraFlags)
                .addAll(archiver.outputArgs(output.toString()))
                .addAll(Iterables.transform(inputs, Object::toString));
    return Joiner.on(' ').join(command.build());
  }

  @Override
  public String getShortName() {
    return "archive";
  }
}
