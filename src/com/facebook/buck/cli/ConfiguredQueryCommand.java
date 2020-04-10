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
import com.facebook.buck.parser.ParsingContext;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.PerBuildStateFactory;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryEnvironment;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.query.QueryParserEnv;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Option;

/** Buck subcommand which facilitates querying information about the configured target graph. */
public class ConfiguredQueryCommand extends AbstractQueryCommand {

  @Option(
      name = "--target-universe",
      usage =
          "Comma separated list of targets at which to root the queryable universe. "
              + "This is useful since targets can exist in multiple configurations. "
              + "While this argument isn't required, it's recommended for basically all queries")
  @Nullable
  private String targetUniverseParam = null;

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
      ParsingContext parsingContext =
          createParsingContext(params.getCells(), pool.getListeningExecutorService());
      PrecomputedTargetUniverse targetUniverse =
          PrecomputedTargetUniverse.createFromRootTargets(
              rootTargetsForUniverse(), params, parserState, parsingContext);
      BuckQueryEnvironment env = BuckQueryEnvironment.from(params, targetUniverse, parsingContext);
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
    if (outputFormat != OutputFormat.LIST) {
      // TODO(srice): Change this.
      throw new QueryException("cquery does not yet support non-list multiquery results");
    }

    printListOutput(queryResultMap, printStream);
  }

  // TODO: This API is too stringly typed. It's easy for users to provide strings here which will
  // cause us to have problems - if not outright crash - later down the line. We should use a type
  // here which better reflects our intent, something like `BuildTargetPattern`.
  private List<String> rootTargetsForUniverse() throws QueryException {
    // Happy path - the user told us what universe they wanted.
    if (targetUniverseParam != null) {
      return Splitter.on(',').splitToList(targetUniverseParam);
    }

    // Less happy path - parse the query and try to infer the roots of the target universe.
    RepeatingTargetEvaluator evaluator = new RepeatingTargetEvaluator();
    QueryExpression<String> expression =
        QueryExpression.parse(
            arguments.get(0),
            QueryParserEnv.of(BuckQueryEnvironment.defaultFunctions(), evaluator));
    // TODO: Right now we use `expression.getTargets`, which gets all target literals referenced in
    // the query. We don't want all literals, for example with `rdeps(//my:binary, //some:library)`
    // we only want to include `//my:binary` in the universe, not both. We should provide a way for
    // functions to specify which of their parameters should be used for the universe calculation.
    return ImmutableList.copyOf(expression.getTargets(evaluator));
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

  private void printListOutput(
      Multimap<String, QueryTarget> queryResultMap, PrintStream printStream) {
    queryResultMap.values().stream().map(this::toPresentationForm).forEach(printStream::println);
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

  /**
   * `QueryEnvironment.TargetEvaluator` implementation that simply repeats back the target string it
   * was provided. Used to parse the query ahead of time and infer the target universe.
   */
  private static class RepeatingTargetEvaluator
      implements QueryEnvironment.TargetEvaluator<String> {
    @Override
    public Type getType() {
      return Type.LAZY;
    }

    @Override
    public Set<String> evaluateTarget(String target) throws QueryException {
      return ImmutableSet.of(target);
    }
  }
}
