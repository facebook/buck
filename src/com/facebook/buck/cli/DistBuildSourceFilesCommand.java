/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.CloseableMemoizedSupplier;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.ThrowingPrintWriter;
import com.google.common.collect.Lists;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

/**
 * Distributed build debug command that prints out all files in the local hard-drive whose contents
 * were hashed in order to take part in any rule key computation. This capturing is done at the
 * level of the ActionGraph after all transformation and enhancement steps have taken place. Only
 * files in this list will take part of the distributed build and will be lazily materialised on the
 * remote BuildSlave servers.
 *
 * <p>This command can be run in two modes: 1. Produce the list of used files for a repository in
 * the local machine passing any number of build targets. 2. Fetch and output the list of targets
 * used for a distributed build that has already occurred. This is done by passing in the
 * [stampede-id] of the build.
 */
public class DistBuildSourceFilesCommand extends AbstractDistBuildCommand {
  private static final String OUTPUT_FILENAME = "stampede_build_source_files.txt";

  @Option(
    name = "--output-file",
    usage = "File where stampede source file dependencies will be saved to."
  )
  private String outputFilename = OUTPUT_FILENAME;

  /**
   * The distributed build state does not contain any cell absolute IDs or paths. It just contains a
   * name hint/alias for the cell. All cells will be rooted off whatever value is provided to this
   * option.
   */
  @Option(name = "--cells-root-path", usage = "Path where all cells will be rooted from.")
  private String cellsRootPath = "/CELLS_ROOT_PATH";

  /** List of build targets that will be used if this command is ran locally. */
  @Argument private List<String> arguments;

  public DistBuildSourceFilesCommand() {
    this(Lists.newArrayList());
  }

  public DistBuildSourceFilesCommand(List<String> arguments) {
    this.arguments = arguments;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "gets the list of all source files required for a distributed build.";
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    Optional<StampedeId> stampedeId = getStampedeIdOptional();
    if (stampedeId.isPresent()) {
      runUsingStampedeId(params, stampedeId.get());
    } else {
      runLocally(params);
    }

    return ExitCode.SUCCESS;
  }

  /**
   * Runs the first stage of a distributed build locally to compute all required source files for
   * remote materialisation.
   */
  private void runLocally(CommandRunnerParams params) throws IOException, InterruptedException {
    try (CommandThreadManager pool =
            new CommandThreadManager(
                "DistBuildSourceFiles", getConcurrencyLimit(params.getBuckConfig()));
        CloseableMemoizedSupplier<ForkJoinPool> poolSupplier =
            getForkJoinPoolSupplier(params.getBuckConfig())) {
      BuildJobState jobState =
          BuildCommand.getAsyncDistBuildState(
                  arguments, params, pool.getWeightedListeningExecutorService(), poolSupplier)
              .get();
      outputResultToTempFile(params, jobState);
    } catch (ExecutionException e) {
      throw new RuntimeException("Could not create DistBuildState.", e);
    }
  }

  /**
   * Fetches the state from a previous distributed build and outputs all source files that were
   * deemed required for that build to take place.
   */
  private void runUsingStampedeId(CommandRunnerParams params, StampedeId stampedeId)
      throws IOException {
    try (DistBuildService service = DistBuildFactory.newDistBuildService(params)) {
      BuildJobState jobState = service.fetchBuildJobState(stampedeId);
      outputResultToTempFile(params, jobState);
    }
  }

  /**
   * Print one required source file per line expanding the root of all cells using the command line
   * argument --cells-root-path.
   */
  private void outputResultToTempFile(CommandRunnerParams params, BuildJobState jobState)
      throws IOException {
    Path logDir = params.getInvocationInfo().get().getLogDirectoryPath();
    Path outputFileAbs = logDir.resolve(outputFilename).normalize();
    int writtenLineCount = 0;
    try (ThrowingPrintWriter writer =
        new ThrowingPrintWriter(new BufferedOutputStream(Files.newOutputStream(outputFileAbs)))) {
      ProjectFilesystem fs = params.getCell().getFilesystem();
      Path cellsCommonRootPath = fs.resolve(Paths.get(cellsRootPath).normalize());
      for (BuildJobStateFileHashes cellHashes : jobState.getFileHashes()) {
        String cellName = jobState.getCells().get(cellHashes.cellIndex).getNameHint();
        Path cellRoot = cellsCommonRootPath.resolve(cellName);
        if (!cellHashes.isSetEntries()) {
          continue;
        }

        for (BuildJobStateFileHashEntry entry : cellHashes.getEntries()) {
          Path absPath = cellRoot.resolve(entry.getPath().getPath()).normalize();
          writer.println(absPath);
          ++writtenLineCount;
        }
      }
    }
    params
        .getConsole()
        .printSuccess(
            "A total of [%d] source file paths were saved to [%s].",
            writtenLineCount, outputFileAbs.toAbsolutePath().toString());
  }
}
