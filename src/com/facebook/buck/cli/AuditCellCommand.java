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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

public class AuditCellCommand extends AbstractCommand {

  @Option(name = "--json", usage = "Output in JSON format")
  private boolean generateJsonOutput;

  public boolean shouldGenerateJsonOutput() {
    return generateJsonOutput;
  }

  @Argument private List<String> arguments = new ArrayList<>();

  public List<String> getArguments() {
    return arguments;
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    ImmutableMap<String, Path> cellMap;
    if (getArguments().isEmpty()) {
      cellMap = params.getBuckConfig().getCellPathResolver().getCellPaths();
    } else {
      CellPathResolver cellPathResolver = params.getBuckConfig().getCellPathResolver();
      ImmutableMap.Builder<String, Path> outputBuilder = ImmutableMap.builder();
      for (String arg : getArguments()) {
        Path cellPath = cellPathResolver.getCellPathOrThrow(Optional.of(arg));
        outputBuilder.put(arg, cellPath);
      }
      cellMap = outputBuilder.build();
    }

    if (shouldGenerateJsonOutput()) {
      printJsonOutput(params, cellMap);
    } else {
      printOutput(params, cellMap);
    }
    return ExitCode.SUCCESS;
  }

  private void printOutput(CommandRunnerParams params, ImmutableMap<String, Path> cellMap) {
    for (Map.Entry<String, Path> entry : cellMap.entrySet()) {
      params
          .getConsole()
          .getStdOut()
          .println(String.format("%s: %s", entry.getKey(), entry.getValue()));
    }
  }

  private void printJsonOutput(CommandRunnerParams params, ImmutableMap<String, Path> cellMap)
      throws IOException {
    ObjectMappers.WRITER.writeValue(params.getConsole().getStdOut(), cellMap);
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "Query information about the [repositories] list in .buckconfig.";
  }
}
