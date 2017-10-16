/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.tools.consistency;

import java.util.Arrays;
import java.util.Optional;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

/**
 * Main entry point into the consistency checker. This tool finds differences between rule keys,
 * target graphs, and can ensure that the target graph is deterministic.
 */
public class Main {
  /** Main entry into consistency checker */
  public static void main(String[] args) {
    CliArgs parsedArgs = new CliArgs();
    CmdLineParser argParser = new CmdLineParser(parsedArgs);
    try {
      argParser.parseArgument(args);
      if (parsedArgs.showHelp || (parsedArgs.cmd != null && parsedArgs.cmd.showHelp)) {
        CliArgs.printUsage(Optional.empty(), Arrays.asList(args), System.err);
        System.exit(1);
      }
    } catch (CmdLineException e) {
      CliArgs.printUsage(Optional.of(e), Arrays.asList(args), System.err);
      System.exit(1);
    }

    if (parsedArgs.cmd instanceof CliArgs.PrintCliCommand) {
      System.exit(handlePrintCommand((CliArgs.PrintCliCommand) parsedArgs.cmd));
    }
  }

  private static int handlePrintCommand(CliArgs.PrintCliCommand args) {
    return 0;
  }
}
