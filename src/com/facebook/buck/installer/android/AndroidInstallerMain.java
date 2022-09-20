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

package com.facebook.buck.installer.android;

import com.facebook.buck.installer.InstallerServer;
import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger; // NOPMD
import java.util.logging.SimpleFormatter;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

/**
 * Main entry point for executing {@code Installs for buck2 android}.
 *
 * <p>Expected usage: {@code this_binary options}.
 */
public class AndroidInstallerMain {
  static {
    // set java.util.logging (JUL) simple formatter to 1 liner.
    System.setProperty(
        "java.util.logging.SimpleFormatter.format",
        "[%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS] [%4$s] %5$s%6$s%n");
  }

  /** Main Entry Point */
  public static void main(String[] args) throws IOException, InterruptedException {
    AndroidInstallerMain installer = new AndroidInstallerMain();
    AndroidCommandLineOptions options = new AndroidCommandLineOptions();
    CmdLineParser parser = new CmdLineParser(options);
    try {
      parser.parseArgument(args);
      installer.run(options, getLogger(options.getLogPath()));
      System.exit(0);
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      parser.printUsage(System.err);
      System.exit(1);
    }
  }

  private static Logger getLogger(String logPath) throws IOException {
    Logger logger = Logger.getLogger(AndroidInstallerMain.class.getName());
    logger.addHandler(getFileHandler(logPath));
    return logger;
  }

  private static FileHandler getFileHandler(String logPath) throws IOException {
    FileHandler fileHandler = new FileHandler(logPath);
    fileHandler.setFormatter(new SimpleFormatter());
    fileHandler.setLevel(Level.INFO);
    return fileHandler;
  }

  private void run(AndroidCommandLineOptions options, Logger logger)
      throws IOException, InterruptedException {
    AndroidInstallerManager androidInstallerManager = new AndroidInstallerManager(logger, options);
    /** Starts the GRPC Server */
    new InstallerServer(
        options.getUnixDomainSocket(), androidInstallerManager, logger, options.getTcpPort());
  }
}
