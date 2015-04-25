/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.util.Console;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.util.Map;

public class ServerStatusCommand extends AbstractCommandRunner<ServerStatusCommandOptions> {

  protected ServerStatusCommand(CommandRunnerParams params) {
    super(params);
  }

  @Override
  ServerStatusCommandOptions createOptions(BuckConfig buckConfig) {
    return new ServerStatusCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(ServerStatusCommandOptions options)
      throws IOException, InterruptedException {
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();

    if (options.isShowHttpserverPort()) {
      int port = -1;
      Optional<WebServer> webServer = getCommandRunnerParams().getWebServer();
      if (webServer.isPresent()) {
        port = webServer.get().getPort().or(port);
      }

      builder.put("http.port", port);
    }

    ImmutableMap<String, Object> values = builder.build();

    Console console = getCommandRunnerParams().getConsole();

    if (options.isPrintJson()) {
      console.getStdOut().println(getObjectMapper().writeValueAsString(values));
    } else {
      for (Map.Entry<String, Object> entry : values.entrySet()) {
        console.getStdOut().printf("%s=%s%n", entry.getKey(), entry.getValue());
      }
    }

    return 0;
  }

  @Override
  String getUsageIntro() {
    return "Specify options to print the server status.";
  }
}
