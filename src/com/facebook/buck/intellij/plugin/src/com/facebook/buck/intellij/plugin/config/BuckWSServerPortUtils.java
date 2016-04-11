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

package com.facebook.buck.intellij.plugin.config;

import com.facebook.buck.util.HumanReadableException;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;


public final class BuckWSServerPortUtils {
  public static final int CONNECTION_FAILED = -1;

  private int mPort = CONNECTION_FAILED;
  private static final String SEARCH_FOR = "http.port=";

  public int getPort(String runInPath) throws
      NumberFormatException, IOException, ExecutionException, HumanReadableException {
    String exec = BuckSettingsProvider.getInstance().getState().buckExecutable;
    GeneralCommandLine commandLine = new GeneralCommandLine();
    commandLine.setExePath(exec);
    commandLine.withWorkDirectory(runInPath);
    commandLine.addParameter("server");
    commandLine.addParameter("status");
    commandLine.addParameter("--http-port");
    commandLine.setRedirectErrorStream(true);

    Process p = commandLine.createProcess();
    BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));

    String error = "";
    String line;
    while ((line = reader.readLine()) != null) {
      error += line;
      if (line.startsWith(BuckWSServerPortUtils.SEARCH_FOR)) {
        mPort = Integer.parseInt(line.replace(BuckWSServerPortUtils.SEARCH_FOR, ""));
        if (mPort == CONNECTION_FAILED) {
          // if the buck server is off, and it gives us -1, throw this exception
          error = "Your buck server may be turned off, since buck has the buck daemon on port " +
              mPort + ".\nTry adding to your '.buckconfig.local' file:\n" +
              "[httpserver]\n" +
              "    port = 0\n" +
              "After that, try running the command again.\n";
          break;
        }
      }
    }
    if (mPort == CONNECTION_FAILED) {
      throw new HumanReadableException(error);
    }
    return mPort;
  }
}
