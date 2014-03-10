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

import com.google.common.annotations.VisibleForTesting;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.io.PrintStream;

public class GenericBuckOptions {

  /**
   * This return code tells calling code (i.e. in Main) that the main help screen should be shown.
   * Actual value chosen to be distinct from actual shell error codes
   */
  public static final int SHOW_MAIN_HELP_SCREEN_EXIT_CODE = -129;

  private static final String BUCK_GIT_COMMIT_KEY = "buck.git_commit";
  private static final String BUCK_GIT_DIRTY_KEY = "buck.git_dirty";

  private final PrintStream stdOut;
  private final PrintStream stdErr;
  private final CmdLineParser parser;

  private final String buckGitCommitHash;
  private final boolean buckGitRepoHasChanges;

  @Option(
      name = "--version",
      aliases = {"-V"},
      usage = "Show version number.")
  private boolean version;

  @Option(
      name = "--help",
      usage = "Shows this screen and exits.")
  private boolean helpScreen;

  public boolean showHelpScreen() {
    return helpScreen;
  }

  public boolean showVersion() {
    return version;
  }

  /**
   * Returns current Buck version, in the form {@code [*]<git-commit-hash>}, where {@code *}
   * indicates that the working tree of the Buck repository is dirty.
   * @return The version of Buck currently running
   */
  private String getBuckVersion() {
    return (buckGitRepoHasChanges ? "*" : "") + buckGitCommitHash;
  }

  @VisibleForTesting
    GenericBuckOptions(
        PrintStream stdOut,
        PrintStream stdErr,
        String buckGitCommitHash,
        boolean buckGitRepoHasChanges) {
    this.stdOut = stdOut;
    this.stdErr = stdErr;
    this.buckGitCommitHash = buckGitCommitHash;
    this.buckGitRepoHasChanges = buckGitRepoHasChanges;
    parser = new CmdLineParser(this);
  }

  public GenericBuckOptions(PrintStream stdOut, PrintStream stdErr) {
    this(
        stdOut,
        stdErr,
        System.getProperty(BUCK_GIT_COMMIT_KEY, "N/A"),
        "1".equals(System.getProperty(BUCK_GIT_DIRTY_KEY, "1")) ? true : false
        );
  }

  public GenericBuckOptions() {
    this(System.out, System.err);
  }

  public int execute(String[] args) throws IOException{
    try {
      parser.parseArgument(args);
      return execute();
    } catch (CmdLineException e) {
      stdErr.println(e.getMessage());
      return SHOW_MAIN_HELP_SCREEN_EXIT_CODE;
    }
  }

  public void printUsage() {
    new CmdLineParser(this).printUsage(stdErr);
  }

  private int execute() throws IOException {
    if (showHelpScreen()) {
      return SHOW_MAIN_HELP_SCREEN_EXIT_CODE;
    } else if (showVersion()) {
      stdOut.println("buck version " + getBuckVersion());
      return 0;
    }
    return SHOW_MAIN_HELP_SCREEN_EXIT_CODE;
  }
}
