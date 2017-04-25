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

package com.facebook.buck.intellij.ideabuck.config;

import com.google.common.base.Strings;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public final class BuckWSServerPortUtils {
  private static final int CONNECTION_FAILED_PORT = -1;

  private static final String SEARCH_FOR = "http.port=";

  public static int getPort(String runInPath)
      throws NumberFormatException, IOException, ExecutionException {
    BuckSettingsProvider.State state = BuckSettingsProvider.getInstance().getState();
    if (state == null) {
      throw new RuntimeException("Cannot load ideabuck settings.");
    }

    String exec = state.buckExecutable;

    if (Strings.isNullOrEmpty(exec)) {
      throw new RuntimeException("Buck executable is not defined in settings.");
    }

    GeneralCommandLine commandLine = new GeneralCommandLine();
    commandLine.setExePath(exec);
    commandLine.withWorkDirectory(runInPath);
    commandLine.addParameter("server");
    commandLine.addParameter("status");
    commandLine.addParameter("--http-port");
    commandLine.setRedirectErrorStream(true);

    Process p = commandLine.createProcess();
    BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));

    int port = CONNECTION_FAILED_PORT;
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.startsWith(SEARCH_FOR)) {
        port = Integer.parseInt(line.substring(SEARCH_FOR.length()));
        if (port == CONNECTION_FAILED_PORT) {
          // if the buck server is off, and it gives us -1, throw this exception
          String error =
              "Your buck server may be turned off, since the Buck daemon is on port "
                  + port
                  + ".\nTry adding to your '.buckconfig.local' or '.buckconfig' file,"
                  + " if you don't have it already set:\n"
                  + "[httpserver]\n"
                  + "    port = 0\n"
                  + "After that, restart IntelliJ or reopen your project.\n";
          throw new RuntimeException(error);
        }
      }
    }
    return port;
  }

  private BuckWSServerPortUtils() {}
}
