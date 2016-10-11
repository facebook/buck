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

package com.facebook.buck.cli;

import com.facebook.buck.autodeps.AutodepsWriter;
import com.facebook.buck.autodeps.DepsForBuildFiles;
import com.facebook.buck.jvm.java.autodeps.JavaDepsFinder;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

/**
 * Command for generating BUCK.generated files with deps.
 */
public class AutodepsCommand extends AbstractCommand {

  /*
   * This command is intended to support inferring dependencies for various programming languages,
   * though initially, we focus exclusively on Java. Further, it should be possible to run this
   * command on a specific set of BUCK files, but at least initially, we will only support running
   * this on the entire project.
   *
   * Finally, the initial implementation is admittedly very inefficient because it does not do any
   * caching. It should leverage Watchman to avoid re-scanning the entire filesystem every time.
   */
  @Override
  public int runWithoutHelp(final CommandRunnerParams params)
      throws IOException, InterruptedException {
    JavaBuildGraphProcessor.Processor processor =
        (graph, javaDepsFinder, executorService) -> generateAutodeps(
            params,
            getConcurrencyLimit(params.getBuckConfig()),
            params.getCell(),
            executorService,
            graph,
            javaDepsFinder,
            params.getConsole());
    try {
      JavaBuildGraphProcessor.run(params, this, processor);
    } catch (JavaBuildGraphProcessor.ExitCodeException e) {
      return e.exitCode;
    }

    return 0;
  }

  private void generateAutodeps(
      CommandRunnerParams params,
      ConcurrencyLimit concurrencyLimit,
      Cell cell,
      WeightedListeningExecutorService executorService,
      TargetGraph graph,
      JavaDepsFinder javaDepsFinder,
      Console console) throws IOException {
    DepsForBuildFiles depsForBuildFiles = javaDepsFinder.findDepsForBuildFiles(graph, console);

    // Now that the dependencies have been computed, write out the BUCK.autodeps files.
    int numWritten;
    try {
      numWritten = AutodepsWriter.write(
          depsForBuildFiles,
          cell.getBuildFileName(),
          params.getObjectMapper(),
          executorService,
          concurrencyLimit.threadLimit);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }

    String message = numWritten == 1
        ? "1 file written."
        : numWritten + " files written.";
    console.printSuccess(message);
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public String getShortDescription() {
    return "auto-generates dependencies for build rules, where possible";
  }
}
