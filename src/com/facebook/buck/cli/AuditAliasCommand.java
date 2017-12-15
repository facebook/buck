/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.ExitCode;
import java.io.IOException;
import java.util.Map;
import org.kohsuke.args4j.Option;

public class AuditAliasCommand extends AbstractCommand {

  @Option(name = "--list", usage = "List known build target aliases.")
  private boolean listAliases = false;

  @Option(
    name = "--list-map",
    usage = "List known build target aliases with their mappings to build targets."
  )
  private boolean listAliasesMap = false;

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    if (listAliasesMap) {
      for (Map.Entry<String, BuildTarget> entry : params.getBuckConfig().getAliases().entries()) {
        params
            .getConsole()
            .getStdOut()
            .println(entry.getKey() + " = " + entry.getValue().getFullyQualifiedName());
      }
      return ExitCode.SUCCESS;
    }
    if (listAliases) {
      for (Map.Entry<String, BuildTarget> entry : params.getBuckConfig().getAliases().entries()) {
        params.getConsole().getStdOut().println(entry.getKey());
      }
      return ExitCode.SUCCESS;
    }

    params.getBuckEventBus().post(ConsoleEvent.severe("No query supplied."));
    return ExitCode.NOTHING_TO_DO;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "Query information about the [alias] list in .buckconfig.";
  }
}
