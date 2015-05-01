/*
 * Copyright 2015-present Facebook, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may
 *  not use this file except in compliance with the License. You may obtain
 *  a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.facebook.buck.cli;

import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.util.List;
import java.util.Map;

// TODO(natthu): Implement this using the @Subcommands annotation from args4j.
public class ServerCommandRunner implements CommandRunner {

  private static final ImmutableMap<String, ? extends AbstractCommandRunner<?>> subcommands =
      ImmutableMap.of("status", new ServerStatusCommand());

  @Override
  public int runCommand(CommandRunnerParams params, List<String> args)
      throws IOException, InterruptedException {
    boolean invalid = false;
    String subcommand = "";
    if (args.isEmpty()) {
      params.getConsole().printBuildFailure("No server command is given.");
      invalid = true;
    } else {
      subcommand = args.get(0);
      if (!subcommands.containsKey(subcommand)) {
        params.getConsole().printBuildFailure("Unknown server subcommand: " + args.get(0));
        invalid = true;
      }
    }

    if (invalid) {
      params.getConsole().getStdErr().println("buck server <cmd>");
      for (Map.Entry<String, ? extends AbstractCommandRunner<?>> entry : subcommands.entrySet()) {
        params
            .getConsole()
            .getStdErr()
            .printf("  %s - %s%n", entry.getKey(), entry.getValue().getUsageIntro());
      }
      return 1;
    }

    AbstractCommandRunner<?> commandRunner = subcommands.get(subcommand);
    return commandRunner.runCommand(params, args.subList(1, args.size()));
  }

}
