/*
 * Copyright 2016-present Facebook, Inc.
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
import java.util.Optional;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.spi.SubCommand;
import org.kohsuke.args4j.spi.SubCommands;

public class DistBuildCommand extends AbstractContainerCommand {

  @Argument(handler = AdditionalOptionsSubCommandHandler.class)
  @SubCommands({
    @SubCommand(name = "status", impl = DistBuildStatusCommand.class),
    @SubCommand(name = "run", impl = DistBuildRunCommand.class),
    @SubCommand(name = "sourcefiles", impl = DistBuildSourceFilesCommand.class),
  })
  @SuppressFieldNotInitialized
  private Command subcommand;

  @Override
  protected String getContainerCommandPrefix() {
    return "buck distbuild";
  }

  @Override
  public Optional<Command> getSubcommand() {
    return Optional.ofNullable(subcommand);
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public boolean isSourceControlStatsGatheringEnabled() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "attaches to a distributed build (experimental)";
  }
}
