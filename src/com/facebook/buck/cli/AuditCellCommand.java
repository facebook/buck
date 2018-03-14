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

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.util.ExitCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.kohsuke.args4j.Argument;

public class AuditCellCommand extends AbstractCommand {

  private Stream<String> getCells(BuckConfig buckConfig) {
    return buckConfig
        .getCellPathResolver()
        .getCellPaths()
        .entrySet()
        .stream()
        .map((entry) -> String.format("%s: %s", entry.getKey(), entry.getValue()));
  }

  @Argument private List<String> arguments = new ArrayList<>();

  public List<String> getArguments() {
    return arguments;
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    Stream<String> cellList;
    if (getArguments().isEmpty()) {
      cellList = getCells(params.getBuckConfig());
    } else {
      CellPathResolver cellPathResolver = params.getBuckConfig().getCellPathResolver();
      Stream.Builder<String> outputBuilder = Stream.builder();
      for (String arg : getArguments()) {
        Path cellPath = cellPathResolver.getCellPathOrThrow(Optional.of(arg));
        outputBuilder.add(String.format("%s: %s", arg, cellPath));
      }
      cellList = outputBuilder.build();
    }

    cellList.forEachOrdered(params.getConsole().getStdOut()::println);
    return ExitCode.SUCCESS;
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
