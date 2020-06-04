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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.parser.ParserPythonInterpreterProvider;
import com.facebook.buck.parser.ParsingContext;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.PerBuildStateFactory;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.query.ConfiguredQueryTarget;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.google.common.collect.Multimap;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Set;

/** Buck subcommand which facilitates querying information about the unconfigured target graph. */
// TODO(srice): We shouldn't be using ConfiguredQueryTarget here.
public class UnconfiguredQueryCommand
    extends AbstractQueryCommand<ConfiguredQueryTarget, UnconfiguredQueryEnvironment> {

  @Override
  public String getShortDescription() {
    return "provides facilities to query information about the unconfigured target graph";
  }

  @Override
  @SuppressWarnings({"unused", "PMD.UnconditionalIfStatement"})
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    if (arguments.isEmpty()) {
      throw new CommandLineException("must specify at least the query expression");
    }

    // Trick the compiler into thinking we might actually use the code below. This also gives us
    // a quick one-line change to make if we want to test things out.
    if (true) {
      throw new HumanReadableException("uquery is not yet implemented and should not be used");
    }

    try (CommandThreadManager pool =
            new CommandThreadManager("UQuery", getConcurrencyLimit(params.getBuckConfig()));
        PerBuildState perBuildState = createPerBuildState(params, pool)) {
      UnconfiguredQueryEnvironment env = UnconfiguredQueryEnvironment.from(params);
      formatAndRunQuery(params, env);
    } catch (QueryException e) {
      throw new HumanReadableException(e);
    }
    return ExitCode.SUCCESS;
  }

  @Override
  protected void printSingleQueryOutput(
      CommandRunnerParams params,
      UnconfiguredQueryEnvironment env,
      Set<ConfiguredQueryTarget> queryResult,
      PrintStream printStream)
      throws QueryException, IOException {
    throw new IllegalStateException("This method is impossible to reach since uquery is NYI");
  }

  @Override
  protected void printMultipleQueryOutput(
      CommandRunnerParams params,
      UnconfiguredQueryEnvironment env,
      Multimap<String, ConfiguredQueryTarget> queryResultMap,
      PrintStream printStream)
      throws QueryException, IOException {
    throw new IllegalStateException("This method is impossible to reach since uquery is NYI");
  }

  private PerBuildState createPerBuildState(CommandRunnerParams params, CommandThreadManager pool) {
    ParsingContext parsingContext =
        createParsingContext(params.getCells(), pool.getListeningExecutorService())
            .withSpeculativeParsing(SpeculativeParsing.ENABLED);

    PerBuildStateFactory factory =
        new PerBuildStateFactory(
            params.getTypeCoercerFactory(),
            new DefaultConstructorArgMarshaller(),
            params.getKnownRuleTypesProvider(),
            new ParserPythonInterpreterProvider(
                params.getCells().getRootCell().getBuckConfig(), params.getExecutableFinder()),
            params.getWatchman(),
            params.getBuckEventBus(),
            params.getUnconfiguredBuildTargetFactory(),
            params.getHostConfiguration().orElse(UnconfiguredTargetConfiguration.INSTANCE));

    return factory.create(parsingContext, params.getParser().getPermState());
  }
}
