/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.installer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger; // NOPMD
import java.util.logging.SimpleFormatter;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

/**
 * Main entry point for executing {@link Installs for buck2 apple}.
 *
 * <p>Expected usage: {@code this_binary options}.
 */
public class AppleMain {

  public static void main(String[] args) throws IOException, InterruptedException {
    /** Main Entry Point */
    Process process = Runtime.getRuntime().exec(new String("hg root"));
    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
    StringBuilder builder = new StringBuilder();
    String line = null;
    while ((line = reader.readLine()) != null) {
      builder.append(line);
    }
    builder.append(new String("/buck-out/v2/log/installer.log"));
    Logger logger = Logger.getLogger(AppleMain.class.getName());
    FileHandler fh = new FileHandler(builder.toString());
    fh.setFormatter(new SimpleFormatter());
    fh.setLevel(Level.INFO);
    logger.addHandler(new ConsoleHandler());
    logger.addHandler(fh);

    AppleMain installer = new AppleMain();
    AppleCommandLineOptions options = new AppleCommandLineOptions();
    CmdLineParser parser = new CmdLineParser(options);
    try {
      parser.parseArgument(args);
      installer.run(options, logger);
      System.exit(0);
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      parser.printUsage(System.err);
      System.exit(1);
    }
  }

  public void run(AppleCommandLineOptions options, Logger log)
      throws IOException, InterruptedException {
    AppleInstallerManager am = AppleInstallerManager.getInstance();
    am.setLogger(log);
    am.setCLIOptions(options);
    /** Starts the GRPC Server */
    new InstallerServer(options.unix_domain_socket, am, log);
  }
}
