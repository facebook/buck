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

import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import org.apache.commons.lang.NotImplementedException;

/** Buck subcommand which facilitates querying information about the configured target graph. */
public class ConfiguredQueryCommand extends AbstractQueryCommand {
  @Override
  public String getShortDescription() {
    return "provides facilities to query information about the configured target nodes graph";
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    if (arguments.isEmpty()) {
      throw new CommandLineException("must specify at least the query expression");
    }

    throw new NotImplementedException("cquery is not currently implemented.");
  }
}
