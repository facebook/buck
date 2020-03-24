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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.parser.ParserPythonInterpreterProvider;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.PerBuildStateFactory;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Objects;
import java.util.Set;

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

    try (CommandThreadManager pool =
            new CommandThreadManager("CQuery", getConcurrencyLimit(params.getBuckConfig()));
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
                    createParsingContext(params.getCells(), pool.getListeningExecutorService())
                        .withSpeculativeParsing(SpeculativeParsing.ENABLED),
                    params.getParser().getPermState())) {
      BuckQueryEnvironment env =
          BuckQueryEnvironment.from(
              params,
              parserState,
              createParsingContext(params.getCells(), pool.getListeningExecutorService()));
      formatAndRunQuery(params, env);
    } catch (QueryException e) {
      throw new HumanReadableException(e);
    }
    return ExitCode.SUCCESS;
  }

  @Override
  protected void printSingleQueryOutput(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      Set<QueryTarget> queryResult,
      PrintStream printStream)
      throws QueryException, IOException {
    OutputFormat trueOutputFormat = checkSupportedOutputFormat(outputFormat);
    switch (trueOutputFormat) {
      case JSON:
        printJsonOutput(queryResult, printStream);
        break;

      case LIST:
        printListOutput(queryResult, printStream);
        break;

      case DOT:
      case DOT_BFS:
      case DOT_COMPACT:
      case DOT_BFS_COMPACT:
      case THRIFT:
      default:
        throw new IllegalStateException(
            "checkSupportedOutputFormat should never give an unsupported format");
    }
  }

  @Override
  protected void printMultipleQueryOutput(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      Multimap<String, QueryTarget> queryResultMap,
      PrintStream printStream)
      throws QueryException, IOException {
    throw new QueryException("cquery is not yet capable of printing results");
  }

  // Returns the output format that we should actually use based on what the user asked for.
  private OutputFormat checkSupportedOutputFormat(OutputFormat requestedOutputFormat)
      throws QueryException {
    switch (requestedOutputFormat) {
      case JSON:
        return OutputFormat.JSON;
      case LIST:
        return shouldOutputAttributes() ? OutputFormat.JSON : OutputFormat.LIST;
      case DOT:
      case DOT_BFS:
      case DOT_COMPACT:
      case DOT_BFS_COMPACT:
      case THRIFT:
      default:
        throw new QueryException("cquery does not currently support that output format");
    }
  }

  private void printListOutput(Set<QueryTarget> queryResult, PrintStream printStream) {
    queryResult.stream().map(this::toPresentationForm).forEach(printStream::println);
  }

  private void printJsonOutput(Set<QueryTarget> queryResult, PrintStream printStream)
      throws IOException {

    Set<String> targetsNames =
        queryResult.stream()
            .peek(Objects::requireNonNull)
            .map(this::toPresentationForm)
            .collect(ImmutableSet.toImmutableSet());

    ObjectMappers.WRITER.writeValue(printStream, targetsNames);
  }

  private String toPresentationForm(QueryTarget target) {
    if (target instanceof QueryFileTarget) {
      QueryFileTarget fileTarget = (QueryFileTarget) target;
      return fileTarget.toString();
    } else if (target instanceof QueryBuildTarget) {
      QueryBuildTarget buildTarget = (QueryBuildTarget) target;
      return toPresentationForm(buildTarget.getBuildTarget());
    } else {
      throw new IllegalStateException(
          String.format("Unknown QueryTarget implementation - %s", target.getClass().toString()));
    }
  }

  private String toPresentationForm(BuildTarget buildTarget) {
    // NOTE: We are explicitly ignoring flavors here because flavored nodes in the graph can really
    // mess with us. Long term we hope to replace flavors with configurations anyway so in the end
    // this shouldn't really matter.
    return buildTarget.withoutFlavors().toStringWithConfiguration();
  }
}
