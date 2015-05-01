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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class AuditCommandRunner implements CommandRunner {
  private static ImmutableMap<String, ? extends AbstractCommandRunner<?>> auditCommands;

  public AuditCommandRunner() {
    setAuditCommands();
  }

  @Override
  public int runCommand(CommandRunnerParams params, List<String> args)
      throws IOException, InterruptedException {
    if (args.isEmpty()) {
      params.getConsole().printBuildFailure("No audit command is given.");
      printUsage(params);
      return 1;
    }

    String auditCmd = args.get(0);
    if (getAuditCommands().containsKey(auditCmd)) {
      CommandRunner cmd = Preconditions.checkNotNull(getAuditCommands().get(auditCmd));
      return cmd.runCommand(params, args.subList(1, args.size()));
    } else {
      params.getConsole().printBuildFailure("Unknown audit command: " + auditCmd);
      printUsage(params);
      return 1;
    }
  }

  private ImmutableMap<String, ? extends AbstractCommandRunner<?>> getAuditCommands() {
    return auditCommands;
  }

  private void setAuditCommands() {
    ImmutableMap.Builder<String, AbstractCommandRunner<?>> commandBuilder = ImmutableMap.builder();
    auditCommands = commandBuilder
        .put("alias", new AuditAliasCommand())
        .put("classpath", new AuditClasspathCommand())
        .put("dependencies", new AuditDependenciesCommand())
        .put("input", new AuditInputCommand())
        .put("owner", new AuditOwnerCommand())
        .put("rules", new AuditRulesCommand())
        .put("tests", new AuditTestsCommand())
        .build();
  }

  private void printUsage(CommandRunnerParams params) {
    // TODO(user): implement better way of showing help
    params.getConsole().getStdOut().println("buck audit <cmd>");
    for (Map.Entry<String, ? extends AbstractCommandRunner<?>> entry :
        getAuditCommands().entrySet()) {
      params.getConsole()
          .getStdOut()
          .println("  " + entry.getKey() + " - " + entry.getValue().getUsageIntro());
    }
  }
}
