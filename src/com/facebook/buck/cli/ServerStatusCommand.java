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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.util.Map;

public class ServerStatusCommand extends AbstractCommand {

  @Option(
      name = "--http-port",
      usage = "Print the port that the server is running on.")
  private boolean showHttpserverPort = false;

  @Option(
      name = "--json",
      usage = "Print the output in a json format.")
  private boolean printJson = false;

  public boolean isShowHttpserverPort() {
    return showHttpserverPort;
  }

  public boolean isPrintJson() {
    return printJson;
  }

  @VisibleForTesting
  void enableShowHttpserverPort() {
    showHttpserverPort = true;
  }

  @VisibleForTesting
  void enablePrintJson() {
    printJson = true;
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();

    if (isShowHttpserverPort()) {
      int port = -1;
      Optional<WebServer> webServer = params.getWebServer();
      if (webServer.isPresent()) {
        port = webServer.get().getPort().or(port);
      }

      builder.put("http.port", port);
    }

    ImmutableMap<String, Object> values = builder.build();

    Console console = params.getConsole();

    if (isPrintJson()) {
      console.getStdOut().println(params.getObjectMapper().writeValueAsString(values));
    } else {
      for (Map.Entry<String, Object> entry : values.entrySet()) {
        console.getStdOut().printf("%s=%s%n", entry.getKey(), entry.getValue());
      }
    }

    return 0;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "Specify options to print the server status.";
  }
}
