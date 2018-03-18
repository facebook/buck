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

import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;

public class DistBuildStatusCommand extends AbstractDistBuildCommand {
  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public String getShortDescription() {
    return "retrieves the status of a distributed build (experimental)";
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    StampedeId stampedeId = getStampedeId();
    Console console = params.getConsole();
    ObjectMapper objectMapper =
        ObjectMappers.legacyCreate()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);

    try (DistBuildService service = DistBuildFactory.newDistBuildService(params)) {
      BuildJob buildJob = service.getCurrentBuildJobState(getStampedeId());
      objectMapper.writeValue(console.getStdOut(), buildJob);
      console.getStdOut().println();
      console.printSuccess(
          String.format("Successfully fetched the build status for [%s].", stampedeId));
      return ExitCode.SUCCESS;
    }
  }
}
