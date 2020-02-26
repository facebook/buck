/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cli;

import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import java.util.Optional;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.spi.SubCommand;
import org.kohsuke.args4j.spi.SubCommands;

/** The PerfCommand is for targeted tests of the performance of buck components. */
public class PerfCommand extends AbstractContainerCommand {

  @Argument(handler = AdditionalOptionsSubCommandHandler.class)
  @SubCommands({
    // TODO(cjhopman): Should we enforce that each of these commands derives from
    // AbstractPerfCommand?
    @SubCommand(name = "action-graph", impl = PerfActionGraphCommand.class),
    @SubCommand(name = "cas", impl = PerfCasCommand.class),
    @SubCommand(name = "rk", impl = PerfRuleKeyCommand.class),
    @SubCommand(name = "manifest", impl = PerfManifestCommand.class),
    @SubCommand(name = "mbr", impl = PerfMbrCommand.class),
    @SubCommand(name = "stat", impl = PerfStatCommand.class),
  })
  @SuppressFieldNotInitialized
  Command subcommand;

  @Override
  public boolean isReadOnly() {
    return subcommand == null || subcommand.isReadOnly();
  }

  @Override
  public String getShortDescription() {
    return "various utilities for testing performance of Buck against real codebases and "
        + "configurations. NOTE: This command's interface is unstable and will change without "
        + "warning.";
  }

  @Override
  protected String getContainerCommandPrefix() {
    return "buck perf";
  }

  @Override
  public Optional<Command> getSubcommand() {
    return Optional.ofNullable(subcommand);
  }
}
