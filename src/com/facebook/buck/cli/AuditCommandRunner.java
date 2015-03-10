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

import com.facebook.buck.util.Console;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class AuditCommandRunner implements CommandRunner {
  private static ImmutableMap<String, ? extends AbstractCommandRunner<?>> auditCommands;

  private final Console console;

  public AuditCommandRunner(CommandRunnerParams params) {
    console = params.getConsole();
    setAuditCommands(params);
  }

  @Override
  public int runCommand(BuckConfig buckConfig, List<String> args)
      throws IOException, InterruptedException {
    if (args.isEmpty()) {
      console.printBuildFailure("No audit command is given.");
      printUsage();
      return 1;
    }

    String auditCmd = args.get(0);
    if (getAuditCommands().containsKey(auditCmd)) {
      CommandRunner cmd = Preconditions.checkNotNull(getAuditCommands().get(auditCmd));
      return cmd.runCommand(buckConfig, args.subList(1, args.size()));
    } else {
      console.printBuildFailure("Unknown audit command: " + auditCmd);
      printUsage();
      return 1;
    }
  }

  private ImmutableMap<String, ? extends AbstractCommandRunner<?>> getAuditCommands() {
    return auditCommands;
  }

  private void setAuditCommands(CommandRunnerParams params) {
    ImmutableMap.Builder<String, AbstractCommandRunner<?>> commandBuilder = ImmutableMap.builder();
    auditCommands = commandBuilder
        .put("classpath", new AuditClasspathCommand(params))
        .put("dependencies", new AuditDependenciesCommand(params))
        .put("input", new AuditInputCommand(params))
        .put("owner", new AuditOwnerCommand(params))
        .put("rules", new AuditRulesCommand(params))
        .put("tests", new AuditTestsCommand(params))
        .build();
  }

  private void printUsage() {
    // TODO(user): implement better way of showing help
    console.getStdOut().println("buck audit <cmd>");
    for (Map.Entry<String, ? extends AbstractCommandRunner<?>> entry :
        getAuditCommands().entrySet()) {
      console.getStdOut().println("  " + entry.getKey() + " - " + entry.getValue().getUsageIntro());
    }
  }
}
