/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.cxx.toolchain.Archiver;
import com.facebook.buck.downwardapi.processexecutor.DownwardApiProcessExecutor;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
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
  private final ImmutableList<RelPath> inputs;
  private final Archiver archiver;
  private final Path scratchDir;
  private final boolean withDownwardApi;

  public ArchiveStep(
      ProjectFilesystem filesystem,
      ImmutableMap<String, String> environment,
      ImmutableList<String> archiverCommand,
      ImmutableList<String> archiverFlags,
      ImmutableList<String> archiverExtraFlags,
      Path output,
      ImmutableList<RelPath> inputs,
      Archiver archiver,
      Path scratchDir,
      boolean withDownwardApi) {
    this.withDownwardApi = withDownwardApi;
    Preconditions.checkArgument(!output.isAbsolute());

    // Our current support for thin archives requires that all the inputs are relative paths from
    // the same cell as the output.

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
    for (RelPath input : inputs) {
      if (filesystem.isDirectory(input)) {
        // We make sure to sort the files we find under the directories so that we get
        // deterministic output.
        Set<String> dirFiles = new TreeSet<>();
        filesystem.walkFileTree(
            filesystem.resolve(input).getPath(),
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
      StepExecutionContext context, ImmutableList<String> command)
      throws IOException, InterruptedException {
    Map<String, String> env = new HashMap<>(context.getEnvironment());
    env.putAll(environment);
    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .setDirectory(filesystem.getRootPath().getPath())
            .setEnvironment(ImmutableMap.copyOf(env))
            .setCommand(command)
            .build();

    ProcessExecutor processExecutor = context.getProcessExecutor();
    if (withDownwardApi) {
      processExecutor =
          processExecutor.withDownwardAPI(
              DownwardApiProcessExecutor.FACTORY, context.getBuckEventBus().isolated());
    }

    ProcessExecutor.Result result = processExecutor.launchAndExecute(params);
    if (result.getExitCode() != 0 && result.getStderr().isPresent()) {
      context.getBuckEventBus().post(ConsoleEvent.create(Level.SEVERE, result.getStderr().get()));
    }
    return result;
  }

  private Path getArgfile() {
    return filesystem.resolve(scratchDir).resolve("ar.argsfile");
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {
    ImmutableList<String> allInputs = getAllInputs();
    if (allInputs.isEmpty()) {
      filesystem.writeContentsToPath("!<arch>\n", output);
      return StepExecutionResults.SUCCESS;
    } else {
      ImmutableList<String> outputArgs = archiver.outputArgs(output.toString());
      if (archiver.isArgfileRequired()) {
        Iterable<String> argfileLines = Iterables.concat(outputArgs, allInputs);
        argfileLines =
            Iterables.transform(
                argfileLines,
                (String s) -> {
                  String ret = s;
                  ret = ret.replace("\\", "\\\\");
                  ret = ret.replace("\"", "\\\"");
                  if (s.contains(" ")) {
                    ret = '"' + ret + '"';
                  }
                  return ret;
                });
        Path argfile = getArgfile();
        filesystem.createParentDirs(argfile);
        filesystem.writeLinesToPath(argfileLines, argfile);
        ImmutableList<String> command =
            ImmutableList.<String>builder()
                .addAll(archiverCommand)
                .addAll(archiverFlags)
                .addAll(archiverExtraFlags)
                .add("@" + argfile)
                .build();
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
  public String getDescription(StepExecutionContext context) {
    ImmutableList.Builder<String> command =
        ImmutableList.<String>builder()
            .addAll(archiverCommand)
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
