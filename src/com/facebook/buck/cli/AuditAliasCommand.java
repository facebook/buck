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

import org.kohsuke.args4j.Option;

import java.io.IOException;

public class AuditAliasCommand extends AbstractCommand {

  @Option(
      name = "--list",
      usage = "List known build target aliases.")
  private boolean listAliases = false;

  boolean isListAliases() {
    return listAliases;
  }

  Iterable<String> getAliases(BuckConfig buckConfig) {
    return buckConfig.getAliases();
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    if (isListAliases()) {
      for (String alias : getAliases(params.getBuckConfig())) {
        params.getConsole().getStdOut().println(alias);
      }
      return 0;
    }

    params.getConsole().getStdErr().println("No query supplied.");
    return 1;
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
