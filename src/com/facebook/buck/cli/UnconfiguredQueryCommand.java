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
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.query.UnconfiguredQueryBuildTarget;
import com.facebook.buck.query.UnconfiguredQueryTarget;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.param.ParamNameOrSpecial;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Multimap;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Optional;
import java.util.Set;

/** Buck subcommand which facilitates querying information about the unconfigured target graph. */
public class UnconfiguredQueryCommand
    extends AbstractQueryCommand<UnconfiguredQueryTarget, UnconfiguredQueryEnvironment> {

  @Override
  public String getShortDescription() {
    return "provides facilities to query information about the unconfigured target graph";
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    if (arguments.isEmpty()) {
      throw new CommandLineException("must specify at least the query expression");
    }

    try (CommandThreadManager pool =
            new CommandThreadManager("UQuery", getConcurrencyLimit(params.getBuckConfig()));
        PerBuildState perBuildState = createPerBuildState(params, pool)) {
      UnconfiguredQueryEnvironment env = UnconfiguredQueryEnvironment.from(params, perBuildState);
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
      Set<UnconfiguredQueryTarget> queryResult,
      PrintStream printStream)
      throws QueryException, IOException {
    OutputFormat trueOutputFormat =
        (outputFormat == OutputFormat.LIST && shouldOutputAttributes())
            ? OutputFormat.JSON
            : outputFormat;

    Optional<ImmutableMap<UnconfiguredQueryTarget, ImmutableSortedMap<ParamNameOrSpecial, Object>>>
        attributesByResult = collectAttributes(params, env, queryResult);

    switch (trueOutputFormat) {
      case LIST:
        printListOutput(queryResult, attributesByResult, printStream);
        break;
        // Use a non-exhausive switch while we're still in the early stages of uquery.
        // $CASES-OMITTED$
      default:
        throw new QueryException("Unsupported output format - uquery is still a work in progress");
    }
  }

  @Override
  protected void printMultipleQueryOutput(
      CommandRunnerParams params,
      UnconfiguredQueryEnvironment env,
      Multimap<String, UnconfiguredQueryTarget> queryResultMap,
      PrintStream printStream)
      throws QueryException, IOException {
    throw new IllegalStateException("This method is impossible to reach since uquery is NYI");
  }

  private void printListOutput(
      Set<UnconfiguredQueryTarget> queryResult,
      Optional<
              ImmutableMap<UnconfiguredQueryTarget, ImmutableSortedMap<ParamNameOrSpecial, Object>>>
          attributesByResultOptional,
      PrintStream printStream) {
    Preconditions.checkState(
        !attributesByResultOptional.isPresent(), "We should be printing with JSON instead");

    queryResult.stream().map(this::toPresentationForm).forEach(printStream::println);
  }

  @SuppressWarnings("unused")
  private Optional<
          ImmutableMap<UnconfiguredQueryTarget, ImmutableSortedMap<ParamNameOrSpecial, Object>>>
      collectAttributes(
          CommandRunnerParams params,
          UnconfiguredQueryEnvironment env,
          Set<UnconfiguredQueryTarget> queryResult) {
    if (!shouldOutputAttributes()) {
      return Optional.empty();
    }
    throw new HumanReadableException(
        "Printing attributes is not yet supported - uquery is still a work in progress");
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

  private String toPresentationForm(UnconfiguredQueryTarget queryTarget) {
    if (queryTarget instanceof UnconfiguredQueryBuildTarget) {
      return toPresentationForm((UnconfiguredQueryBuildTarget) queryTarget);
    } else if (queryTarget instanceof QueryFileTarget) {
      return toPresentationForm((QueryFileTarget) queryTarget);
    } else {
      throw new IllegalStateException(
          String.format(
              "Unknown UnconfiguredQueryTarget implementation - %s",
              queryTarget.getClass().toString()));
    }
  }

  private String toPresentationForm(UnconfiguredQueryBuildTarget queryBuildTarget) {
    return queryBuildTarget.getBuildTarget().getUnflavoredBuildTarget().toString();
  }
}
