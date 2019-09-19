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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.parser.ParserPythonInterpreterProvider;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.PerBuildStateFactory;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.google.common.collect.ImmutableList;

/**
 * Buck subcommand which relies on the unconfigured target graph, whose nodes contain all the
 * information inside selects
 */
public class UnconfiguredQueryCommand extends AbstractQueryCommand {

  // explicitly ignore --target-platforms= and --exclude-incompatible-targets=
  // arguments because uquery output should be the same even when uquery is used
  // with mode files (which often specify these arguments)

  @Override
  public ImmutableList<String> getTargetPlatforms() {
    return ImmutableList.of();
  }

  @Override
  public boolean getExcludeIncompatibleTargets() {
    return false;
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    if (arguments.isEmpty()) {
      throw new CommandLineException("must specify at least the query expression");
    }

    if (generateJsonOutput
        || outputFormat == OutputFormat.JSON
        || (outputFormat == OutputFormat.LIST && shouldOutputAttributes())) {
      generateJsonOutput = false;
      outputFormat = OutputFormat.JSON_UNCONFIGURED;
    }

    try (CommandThreadManager pool =
            new CommandThreadManager(
                "UnconfiguredQuery", getConcurrencyLimit(params.getBuckConfig()));
        PerBuildState parserState =
            PerBuildStateFactory.createFactory(
                    params.getTypeCoercerFactory(),
                    new DefaultConstructorArgMarshaller(params.getTypeCoercerFactory()),
                    params.getKnownRuleTypesProvider(),
                    new ParserPythonInterpreterProvider(
                        params.getCell().getBuckConfig(), params.getExecutableFinder()),
                    params.getWatchman(),
                    params.getBuckEventBus(),
                    params.getManifestServiceSupplier(),
                    params.getFileHashCache(),
                    params.getUnconfiguredBuildTargetFactory())
                .create(
                    createParsingContext(params.getCell(), pool.getListeningExecutorService())
                        .withSpeculativeParsing(SpeculativeParsing.ENABLED)
                        .withUseUnconfiguredSelectorResolver(true),
                    params.getParser().getPermState())) {
      BuckQueryEnvironment env =
          BuckQueryEnvironment.from(
              params,
              parserState,
              createParsingContext(params.getCell(), pool.getListeningExecutorService())
                  .withUseUnconfiguredSelectorResolver(true));
      formatAndRunQuery(params, env);
    } catch (QueryException e) {
      throw new HumanReadableException(e);
    }
    return ExitCode.SUCCESS;
  }

  @Override
  public String getShortDescription() {
    return "provides facilities to query information about the unconfigured target nodes graph";
  }
}
