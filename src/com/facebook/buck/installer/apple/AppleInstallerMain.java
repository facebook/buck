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

package com.facebook.buck.installer.apple;

import com.facebook.buck.installer.InstallerServer;
import java.io.BufferedReader;
import java.io.File;
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
 * Main entry point for executing {@code Installs for buck2 apple}.
 *
 * <p>Expected usage: {@code this_binary options}.
 */
public class AppleInstallerMain {

  static {
    // set java.util.logging (JUL) simple formatter to 1 liner.
    System.setProperty(
        "java.util.logging.SimpleFormatter.format",
        "[%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS] [%4$s] %5$s%6$s%n");
  }

  public static final String INSTALLER_LOG_PATH =
      String.join(File.separator, "buck-out", "v2", "log", "installer.log");

  /** Main Entry Point */
  public static void main(String[] args) throws IOException, InterruptedException {
    AppleInstallerMain installer = new AppleInstallerMain();
    AppleCommandLineOptions options = new AppleCommandLineOptions();
    CmdLineParser parser = new CmdLineParser(options);
    try {
      parser.parseArgument(args);
      installer.run(options, getLogger());
      System.exit(0);
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      parser.printUsage(System.err);
      System.exit(1);
    }
  }

  private static Logger getLogger() throws IOException, InterruptedException {
    Logger logger = Logger.getLogger(AppleInstallerMain.class.getName());
    logger.addHandler(new ConsoleHandler());
    logger.addHandler(getFileHandler());
    return logger;
  }

  private static FileHandler getFileHandler() throws IOException, InterruptedException {
    FileHandler fileHandler = new FileHandler(getLogFilePath());
    fileHandler.setFormatter(new SimpleFormatter());
    fileHandler.setLevel(Level.INFO);
    return fileHandler;
  }

  private static String getLogFilePath() throws IOException, InterruptedException {
    StringBuilder loggerPathBuilder = new StringBuilder(INSTALLER_LOG_PATH);

    Process process = Runtime.getRuntime().exec("hg root");
    int exitCode = process.waitFor();
    if (exitCode != 0) {
      System.err.printf(
          "Can't find a root of the repo. The logger will write into a path (%s) that is relative to the current directory %s.",
          INSTALLER_LOG_PATH, System.getProperty("user.dir"));
    } else {
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (!line.endsWith(File.separator)) {
            loggerPathBuilder.insert(0, File.separator);
          }
          loggerPathBuilder.insert(0, line);
        }
      }
    }
    return loggerPathBuilder.toString();
  }

  private void run(AppleCommandLineOptions options, Logger logger)
      throws IOException, InterruptedException {
    AppleInstallerManager am = AppleInstallerManager.getInstance();
    am.setLogger(logger);
    am.setCLIOptions(options);
    /** Starts the GRPC Server */
    new InstallerServer(options.unix_domain_socket, am, logger);
  }
}
