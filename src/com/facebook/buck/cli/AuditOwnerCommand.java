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

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.util.MoreExceptions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

public class AuditOwnerCommand extends AbstractCommand {

  @Option(name = "--json", usage = "Output in JSON format")
  private boolean generateJsonOutput;

  public boolean shouldGenerateJsonOutput() {
    return generateJsonOutput;
  }

  @Argument private List<String> arguments = new ArrayList<>();

  public List<String> getArguments() {
    return arguments;
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    if (params.getConsole().getAnsi().isAnsiTerminal()) {
      params
          .getBuckEventBus()
          .post(
              ConsoleEvent.info(
                  "'buck audit owner' is deprecated. Please use 'buck query' instead. e.g.\n\t%s\n\n"
                      + "The query language is documented at https://buckbuild.com/command/query.html",
                  QueryCommand.buildAuditOwnerQueryExpression(
                      getArguments(), shouldGenerateJsonOutput())));
    }

    try (CommandThreadManager pool =
            new CommandThreadManager("Audit", getConcurrencyLimit(params.getBuckConfig()));
        PerBuildState parserState =
            new PerBuildState(
                params.getParser(),
                params.getBuckEventBus(),
                pool.getExecutor(),
                params.getCell(),
                getEnableParserProfiling(),
                SpeculativeParsing.of(true))) {
      BuckQueryEnvironment env =
          BuckQueryEnvironment.from(params, parserState, getEnableParserProfiling());
      return QueryCommand.runMultipleQuery(
          params,
          env,
          pool.getExecutor(),
          "owner('%s')",
          getArguments(),
          shouldGenerateJsonOutput());
    } catch (Exception e) {
      if (e.getCause() instanceof InterruptedException) {
        throw (InterruptedException) e.getCause();
      }
      params
          .getBuckEventBus()
          .post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return 1;
    }
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "prints targets that own specified files";
  }
}
