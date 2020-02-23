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
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.PerBuildStateFactory;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

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
  public Optional<String> getHostPlatform() {
    return Optional.empty();
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

    try (CommandThreadManager pool =
            new CommandThreadManager(
                "UnconfiguredQuery", getConcurrencyLimit(params.getBuckConfig()));
        PerBuildState parserState =
            new PerBuildStateFactory(
                    params.getTypeCoercerFactory(),
                    new DefaultConstructorArgMarshaller(),
                    params.getKnownRuleTypesProvider(),
                    new ParserPythonInterpreterProvider(
                        params.getCells().getRootCell().getBuckConfig(),
                        params.getExecutableFinder()),
                    params.getWatchman(),
                    params.getBuckEventBus(),
                    params.getUnconfiguredBuildTargetFactory(),
                    params.getHostConfiguration().orElse(UnconfiguredTargetConfiguration.INSTANCE))
                .create(
                    createParsingContext(
                            params.getCells().getRootCell(), pool.getListeningExecutorService())
                        .withSpeculativeParsing(SpeculativeParsing.ENABLED)
                        .withUseUnconfiguredSelectorResolver(true),
                    params.getParser().getPermState())) {
      BuckQueryEnvironment env =
          BuckQueryEnvironment.from(
              params,
              parserState,
              createParsingContext(
                      params.getCells().getRootCell(), pool.getListeningExecutorService())
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

  @Override
  protected WhichQueryCommand whichQueryCommand() {
    return WhichQueryCommand.UQUERY;
  }
}
