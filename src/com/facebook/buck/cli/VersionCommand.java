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

import com.facebook.buck.config.CellConfig;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.log.LogConfigSetup;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Optional;
import java.util.OptionalInt;

public class VersionCommand implements Command {

  private static final String BUCK_GIT_COMMIT_KEY = "buck.git_commit";
  private static final String BUCK_GIT_DIRTY_KEY = "buck.git_dirty";

  /**
   * Returns current Buck version, in the form {@code [*]<git-commit-hash>}, where {@code *}
   * indicates that the working tree of the Buck repository is dirty.
   *
   * @return The version of Buck currently running
   */
  private String getBuckVersion() {
    return (getBuckRepoHasChanges() ? "*" : "") + getBuckGitCommitHash().orElse("N/A");
  }

  private boolean getBuckRepoHasChanges() {
    return "1".equals(System.getProperty(BUCK_GIT_DIRTY_KEY, "1"));
  }

  public Optional<String> getBuckGitCommitHash() {
    return Optional.ofNullable(System.getProperty(BUCK_GIT_COMMIT_KEY));
  }

  @Override
  public int run(CommandRunnerParams params) throws IOException, InterruptedException {
    params.getConsole().getStdOut().println("buck version " + getBuckVersion());
    return 0;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "shows the version number";
  }

  @Override
  public boolean isSourceControlStatsGatheringEnabled() {
    return false;
  }

  @Override
  public CellConfig getConfigOverrides() {
    return CellConfig.of();
  }

  @Override
  public LogConfigSetup getLogConfig() {
    return LogConfigSetup.DEFAULT_SETUP;
  }

  @Override
  public Iterable<BuckEventListener> getEventListeners() {
    return ImmutableList.of();
  }

  @Override
  public void printUsage(PrintStream stream) {
    // Can't get here, because --version is a flag and:
    // `buck help --version` will just return that `--version` is not a valid option for help
    // `buck --version --help` and `buck --help --version` will return the version instead
    throw new IllegalStateException();
  }

  @Override
  public OptionalInt runHelp(PrintStream stream) {
    return OptionalInt.empty();
  }
}
