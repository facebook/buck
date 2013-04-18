/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Preconditions;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;

abstract class AbstractCommandRunner<T extends AbstractCommandOptions> implements CommandRunner {

  protected final PrintStream stdOut;
  protected final PrintStream stdErr;
  protected final Ansi ansi;
  protected final Console console;
  private final ProjectFilesystem projectFilesystem;

  protected AbstractCommandRunner() {
    this(System.out,
        System.err,
        new Console(System.out, System.err, new Ansi()),
        new ProjectFilesystem(new File(".")));
  }

  protected AbstractCommandRunner(PrintStream stdOut,
      PrintStream stdErr,
      Console console,
      ProjectFilesystem projectFilesystem) {
    this.stdOut = Preconditions.checkNotNull(stdOut);
    this.stdErr = Preconditions.checkNotNull(stdErr);
    this.console = Preconditions.checkNotNull(console);
    this.ansi = Preconditions.checkNotNull(console.getAnsi());
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
  }

  abstract T createOptions(BuckConfig buckConfig);

  private final ParserAndOptions<T> createParser(BuckConfig buckConfig) {
    T options = createOptions(buckConfig);
    return new ParserAndOptions<T>(options);
  }

  @Override
  public final int runCommand(BuckConfig buckConfig, String[] args) throws IOException {
    ParserAndOptions<T> parserAndOptions = createParser(buckConfig);
    T options = parserAndOptions.options;
    CmdLineParser parser = parserAndOptions.parser;

    boolean hasValidOptions = false;
    try {

      parser.parseArgument(args);
      hasValidOptions = true;
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
    }

    if (hasValidOptions && !options.showHelp()) {
      return runCommandWithOptions(options);
    } else {
      printUsage(parser);
      return 1;
    }
  }

  public final void printUsage(BuckConfig buckConfig) {
    CmdLineParser parser = createParser(buckConfig).parser;
    printUsage(parser);
  }

  public final void printUsage(CmdLineParser parser) {
    String intro = getUsageIntro();
    if (intro != null) {
      System.err.println(intro);
    }
    parser.printUsage(System.err);
  }

  /**
   * @return the exit code this process should exit with or
   *     {@link #STATUS_NO_EXIT} if it should not shut down
   */
  abstract int runCommandWithOptions(T options) throws IOException;

  /**
   * @return may be null
   */
  abstract String getUsageIntro();

  public ProjectFilesystem getProjectFilesystem() {
    return projectFilesystem;
  }

  private static class ParserAndOptions<T> {
    private final T options;
    private final CmdLineParser parser;

    private ParserAndOptions(T options) {
      this.options = options;
      this.parser = new CmdLineParserAdditionalOptions(options);
    }
  }

}
