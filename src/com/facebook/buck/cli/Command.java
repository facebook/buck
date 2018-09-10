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

import com.facebook.buck.core.cell.CellConfig;
import com.facebook.buck.core.cell.CellName;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.log.LogConfigSetup;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.util.ExitCode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import org.pf4j.PluginManager;

public interface Command {

  /** @return the appropriate exit code for the command */
  ExitCode run(CommandRunnerParams params) throws IOException, InterruptedException;

  /**
   * If the current command is a help command, run the action to print out the appropriate help
   * message.
   *
   * <p>This is an optimization to avoid initializing everything in CommandRunnerParams, in order to
   * return help strings quickly.
   *
   * @param stream stream to output the help text.
   * @return The exit code of the command, if the command is a help request.
   */
  Optional<ExitCode> runHelp(PrintStream stream);

  /** @return whether the command doesn't modify the state of the filesystem */
  boolean isReadOnly();

  /** @return whether we should gather source control stats while executing the command. */
  boolean isSourceControlStatsGatheringEnabled();

  String getShortDescription();

  CellConfig getConfigOverrides(ImmutableMap<CellName, Path> cellMapping);

  /** @return how we want logging to be configured for the the command. */
  LogConfigSetup getLogConfig();

  /** If any of these listeners also extends Closeable, it will be closed by Main. */
  Iterable<BuckEventListener> getEventListeners(
      Map<ExecutorPool, ListeningExecutorService> executorPool,
      ScheduledExecutorService scheduledExecutorService);

  void printUsage(PrintStream stream);

  boolean performsBuild();

  void setPluginManager(PluginManager pluginManager);

  PluginManager getPluginManager();

  ImmutableList<String> getTargetPlatforms();
}
