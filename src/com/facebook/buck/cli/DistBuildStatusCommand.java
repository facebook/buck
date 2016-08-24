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

import com.facebook.buck.distributed.BuildStatusGetter;
import com.facebook.buck.distributed.thrift.BuildId;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.slb.ThriftService;
import com.facebook.buck.util.Console;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.base.Optional;

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
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    BuildId buildId = getBuildId();
    Console console = params.getConsole();
    ObjectMapper objectMapper = params.getObjectMapper().copy();
    objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    objectMapper.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);

    try (ThriftService<FrontendRequest, FrontendResponse> service =
             DistBuildCommand.newFrontendService(params)) {
      Optional<BuildJob> buildJob = BuildStatusGetter.getBuildJob(getBuildId(), service);
      if (buildJob.isPresent()) {
        objectMapper.writeValue(console.getStdOut(), buildJob.get());
        console.getStdOut().println();
        console.printSuccess(String.format(
            "Successfully fetched the build status for [%s].",
            buildId));
        return 0;
      } else {
        console.printBuildFailure(String.format(
            "Distributed build with [%s] does not exist.",
            buildId));
        return -1;
      }
    }
  }
}
