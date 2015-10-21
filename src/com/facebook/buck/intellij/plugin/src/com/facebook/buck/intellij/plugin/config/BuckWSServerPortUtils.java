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

import java.io.BufferedReader;
import java.io.InputStreamReader;


public final class BuckWSServerPortUtils {
  private int mPort = -1;

  private static final String SEARCH_FOR = "http.port=";

  public int getPort(String runInPath) {
    String exec = BuckSettingsProvider.getInstance().getState().buckExecutable;
    GeneralCommandLine commandLine = new GeneralCommandLine();
    commandLine.setExePath(exec);
    commandLine.withWorkDirectory(runInPath);
    commandLine.addParameter("server");
    commandLine.addParameter("status");
    commandLine.addParameter("--http-port");
    commandLine.setRedirectErrorStream(true);

      try {
        Process p = null;
        p = commandLine.createProcess();
        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));

        String line = "";
        while ((line = reader.readLine()) != null) {
            if (line.startsWith(BuckWSServerPortUtils.SEARCH_FOR)) {
                try {
                    mPort = Integer.parseInt(line.replace(BuckWSServerPortUtils.SEARCH_FOR, ""));
                    return mPort;
                } catch (java.lang.NumberFormatException ex) {
                    mPort = -1;
                }
            }
        }
      } catch (ExecutionException e) {
          mPort = -1;
      } catch (java.io.IOException e) {
          mPort = -1;
      }
    return mPort;
  }
}
