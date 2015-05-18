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

import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.SubCommand;
import org.kohsuke.args4j.spi.SubCommands;

import java.io.IOException;

public class AuditCommand extends AbstractContainerCommand {

  @Argument(handler = AdditionalOptionsSubCommandHandler.class)
  @SubCommands({
      @SubCommand(name = "alias", impl = AuditAliasCommand.class),
      @SubCommand(name = "classpath", impl = AuditClasspathCommand.class),
      @SubCommand(name = "dependencies", impl = AuditDependenciesCommand.class),
      @SubCommand(name = "input", impl = AuditInputCommand.class),
      @SubCommand(name = "owner", impl = AuditOwnerCommand.class),
      @SubCommand(name = "rules", impl = AuditRulesCommand.class),
      @SubCommand(name = "tests", impl = AuditTestsCommand.class),
  })
  @SuppressFieldNotInitialized
  Command subcommand;

  @Option(
      name = "--help",
      usage = "Shows this screen and exits.")
  @SuppressWarnings("PMD.UnusedPrivateField")
  private boolean helpScreen;

  @Override
  public int run(CommandRunnerParams params) throws IOException, InterruptedException {
    if (subcommand == null) {
      printUsage(params.getConsole().getStdErr());
      return 1;
    }
    return subcommand.run(params);
  }

  @Override
  public boolean isReadOnly() {
    return subcommand == null || subcommand.isReadOnly();
  }

  @Override
  public String getShortDescription() {
    return "lists the inputs for the specified target";
  }

  @Override
  protected String getContainerCommandPrefix() {
    return "buck audit";
  }

  @Override
  public Optional<Command> getSubcommand() {
    return Optional.fromNullable(subcommand);
  }

}
