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

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;


public final class BuckWSServerPortUtils {
  public static final int CONNECTION_FAILED = -1;
  private static final String CONNECTION_FAILED_MESSAGE =
      "Connecting to Buck failed with message: ";

  private int mPort = CONNECTION_FAILED;
  private static final Logger LOG = Logger.getInstance(BuckWSServerPortUtils.class);
  private static final String SEARCH_FOR = "http.port=";

  public int getPort(String runInPath) throws
      NumberFormatException, IOException, ExecutionException {
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
      String text = "";
      String line;
      while ((line = reader.readLine()) != null) {
        text += line;
        if (line.startsWith(BuckWSServerPortUtils.SEARCH_FOR)) {
          mPort = Integer.parseInt(line.replace(BuckWSServerPortUtils.SEARCH_FOR, ""));
          return mPort;
        }
      }
      if (mPort == CONNECTION_FAILED) {
        LOG.error(CONNECTION_FAILED_MESSAGE + text);
      }
    return mPort;
  }
}
