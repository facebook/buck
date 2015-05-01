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

import com.google.common.annotations.VisibleForTesting;

import org.kohsuke.args4j.Option;

// TODO(natthu): Either this class should not extend AbstractCommandOptions, or the super class
// should not contain any arguments.
public class ServerStatusCommandOptions extends AbstractCommandOptions {

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
}
