/*
 * Copyright 2019-present Facebook, Inc.
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

import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import java.util.Optional;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.spi.SubCommand;
import org.kohsuke.args4j.spi.SubCommands;

/** Container for {@link ModernBuildRule}-related perf tests. */
public class PerfMbrCommand extends AbstractContainerCommand {

  @Argument(handler = AdditionalOptionsSubCommandHandler.class)
  @SubCommands({
    @SubCommand(
        name = "prepare-remote-execution",
        impl = PerfMbrPrepareRemoteExecutionCommand.class),
    @SubCommand(name = "serialization", impl = PerfMbrSerializationCommand.class),
  })
  @SuppressFieldNotInitialized
  Command subcommand;

  @Override
  public boolean isReadOnly() {
    return subcommand == null || subcommand.isReadOnly();
  }

  @Override
  public String getShortDescription() {
    return "utilities for testing performance of ModernBuildRule-related components of Buck. "
        + "NOTE: This command's interface is unstable and will change without "
        + "warning.";
  }

  @Override
  protected String getContainerCommandPrefix() {
    return "buck perf mbr";
  }

  @Override
  public Optional<Command> getSubcommand() {
    return Optional.ofNullable(subcommand);
  }
}
