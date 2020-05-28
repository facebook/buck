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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.query.EvaluatingQueryEnvironment;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.query.QueryNormalizer;
import com.facebook.buck.rules.param.ParamNameOrSpecial;
import com.facebook.buck.util.CloseableWrapper;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.PatternsMatcher;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.FileOptionHandler;

/** Provides base functionality for query commands. */
public abstract class AbstractQueryCommand<
        NODE_TYPE, ENV_TYPE extends EvaluatingQueryEnvironment<NODE_TYPE>>
    extends AbstractCommand {
  private static final Logger LOG = Logger.get(AbstractQueryCommand.class);

  /** Enum with values for `--output-format` CLI parameter */
  protected enum OutputFormat {
    /** Format output as list */
    LIST,

    /** Format output as dot graph */
    DOT,

    /** Format output as dot graph, in a more compact format */
    DOT_COMPACT,

    /** Format output as dot graph in bfs order */
    DOT_BFS,

    /** Format output as dot graph in bfs order, in a more compact format */
    DOT_BFS_COMPACT,

    /** Format output as JSON */
    JSON,

    /** Format output as Thrift binary */
    THRIFT,
  }

  @Option(
      name = "--output-format",
      usage =
          "Output format (default: list).\n"
              + " dot -  dot graph format.\n"
              + " dot_compact - dot graph format, compacted.\n"
              + " dot_bfs -  dot graph format in bfs order.\n"
              + " dot_bfs_compact - dot graph format in bfs order, compacted.\n"
              + " json - JSON format.\n"
              + " json_unconfigured - JSON format with unevaluated selects\n"
              + " thrift - thrift binary format.\n")
  protected OutputFormat outputFormat = OutputFormat.LIST;

  @Option(
      name = "--output-file",
      usage = "Specify output file path for a result",
      handler = FileOptionHandler.class)
  @Nullable
  private File outputFile;

  // Use the `outputAttributes()` function to access this data instead. See the comment on the top
  // of that function for the reason why.
  @Option(
      name = "--output-attribute",
      usage =
          "List of attributes to output, --output-attributes attr1. Attributes can be "
              + "regular expressions. Multiple attributes may be selected by specifying this option "
              + "multiple times.",
      handler = SingleStringSetOptionHandler.class,
      forbids = {"--output-attributes"})
  @VisibleForTesting
  Supplier<ImmutableSet<String>> outputAttributesDoNotUseDirectly =
      Suppliers.ofInstance(ImmutableSet.of());

  // NOTE: Use this rather than accessing the data directly because subclasses (looking at you,
  // {@link QueryCommand}) override this to support other ways of specifying output attributes.
  protected ImmutableSet<String> outputAttributes() {
    return outputAttributesDoNotUseDirectly.get();
  }

  protected boolean shouldOutputAttributes() {
    return !outputAttributes().isEmpty();
  }

  @Argument(handler = QueryMultiSetOptionHandler.class)
  protected List<String> arguments = new ArrayList<>();

  @VisibleForTesting
  void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }

  protected abstract void printSingleQueryOutput(
      CommandRunnerParams params, ENV_TYPE env, Set<NODE_TYPE> queryResult, PrintStream printStream)
      throws QueryException, IOException;

  protected abstract void printMultipleQueryOutput(
      CommandRunnerParams params,
      ENV_TYPE env,
      Multimap<String, NODE_TYPE> queryResultMap,
      PrintStream printStream)
      throws QueryException, IOException;

  @VisibleForTesting
  void formatAndRunQuery(CommandRunnerParams params, ENV_TYPE env)
      throws IOException, InterruptedException, QueryException {

    String queryFormat = arguments.get(0);
    List<String> formatArgs = arguments.subList(1, arguments.size());
    if (queryFormat.contains(QueryNormalizer.SET_SUBSTITUTOR)) {
      runSingleQuery(params, env, QueryNormalizer.normalizePattern(queryFormat, formatArgs));
      return;
    }
    if (queryFormat.contains("%s")) {
      runMultipleQuery(params, env, queryFormat, formatArgs);
      return;
    }
    if (formatArgs.size() > 0) {
      throw new CommandLineException(
          "Must not specify format arguments without a %s or %Ss in the query");
    }
    runSingleQuery(params, env, queryFormat);
  }

  /**
   * Evaluate multiple queries in a single `buck query` run. Usage: buck query <query format>
   * <input1> <input2> <...> <inputN>
   *
   * <p>NOTE: This should really be private, but we have some CLI commands which are just wrappers
   * around common queries and those use `runMultipleQuery` to function.
   */
  void runMultipleQuery(
      CommandRunnerParams params,
      ENV_TYPE env,
      String queryFormat,
      List<String> inputsFormattedAsBuildTargets)
      throws IOException, InterruptedException, QueryException {
    if (inputsFormattedAsBuildTargets.isEmpty()) {
      throw new CommandLineException(
          "specify one or more input targets after the query expression format");
    }

    LOG.debug("Preloading target patterns for multi query");

    // Do an initial pass over the query arguments and parse them into their expressions so we can
    // preload all the target patterns from every argument in one go, as doing them one-by-one is
    // really inefficient.
    Set<String> targetLiterals = new LinkedHashSet<>();
    for (String input : inputsFormattedAsBuildTargets) {
      String query = queryFormat.replace("%s", input);
      QueryExpression<NODE_TYPE> expr = QueryExpression.parse(query, env.getQueryParserEnv());
      expr.collectTargetPatterns(targetLiterals);
    }
    env.preloadTargetPatterns(targetLiterals);

    LOG.debug("Finished preloading target patterns. Executing queries.");

    // Now execute the query on the arguments one-by-one.
    LinkedHashMultimap<String, NODE_TYPE> queryResultMap = LinkedHashMultimap.create();
    for (String input : inputsFormattedAsBuildTargets) {
      String query = queryFormat.replace("%s", input);
      Set<NODE_TYPE> queryResult = env.evaluateQuery(query);
      queryResultMap.putAll(input, queryResult);
    }

    LOG.debug("Printing out %d targets", queryResultMap.size());

    try (CloseableWrapper<PrintStream> printStreamWrapper = getPrintStreamWrapper(params)) {
      PrintStream printStream = printStreamWrapper.get();
      printMultipleQueryOutput(params, env, queryResultMap, printStream);
    }
  }

  private void runSingleQuery(CommandRunnerParams params, ENV_TYPE env, String query)
      throws IOException, InterruptedException, QueryException {
    LOG.debug("Evaluating single query");

    Set<NODE_TYPE> queryResult = env.evaluateQuery(query);

    LOG.debug("Printing out %d targets", queryResult.size());

    try (CloseableWrapper<PrintStream> printStreamWrapper = getPrintStreamWrapper(params)) {
      PrintStream printStream = printStreamWrapper.get();
      printSingleQueryOutput(params, env, queryResult, printStream);
    }
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  public static String getEscapedArgumentsListAsString(List<String> arguments) {
    return arguments.stream().map(arg -> "'" + arg + "'").collect(Collectors.joining(" "));
  }

  public static String getJsonOutputParamDeclaration() {
    return " --output-format json";
  }

  /**
   * Filters the entries in {@code attributes} based on whether the key matches a pattern from
   * {@code matcher}. In the context of queries, this matcher normally represents the {@code
   * --output-attributes} parameter. Returns a new map of only matching key/values.
   */
  protected ImmutableMap<ParamNameOrSpecial, Object> getMatchingAttributes(
      PatternsMatcher matcher, ImmutableMap<ParamNameOrSpecial, Object> attributes) {
    ImmutableMap.Builder<ParamNameOrSpecial, Object> result = ImmutableMap.builder();
    if (!matcher.isMatchesNone()) {
      for (Map.Entry<ParamNameOrSpecial, Object> entry : attributes.entrySet()) {
        String snakeCaseKey = entry.getKey().getSnakeCase();
        if (matcher.matches(snakeCaseKey)) {
          result.put(entry.getKey(), entry.getValue());
        }
      }
    }
    return result.build();
  }

  /** Takes a json-serializable object and pretty-prints it to {@code printStream} */
  protected void prettyPrintJsonObject(Object jsonObject, PrintStream printStream)
      throws IOException {
    ObjectMappers.WRITER
        .with(
            new DefaultPrettyPrinter().withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE))
        // Jackson closes stream by default - we do not want it
        .without(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
        .writeValue(printStream, jsonObject);

    // Jackson does not append a newline after final closing bracket. Do it to make JSON look
    // nice on console.
    printStream.println();
  }

  /**
   * Returns PrintStream wrapper with modified {@code close()} operation.
   *
   * <p>If {@code --output-file} parameter is specified then print stream will be opened from {@code
   * outputFile}. During the {@code close()} operation this stream will be closed.
   *
   * <p>Else if {@code --output-file} parameter is not specified then standard console output will
   * be returned as print stream. {@code close()} operation is ignored in this case.
   */
  private CloseableWrapper<PrintStream> getPrintStreamWrapper(CommandRunnerParams params)
      throws IOException {
    if (outputFile == null) {
      // use stdout for output, do not close stdout stream as it is not owned here
      return CloseableWrapper.of(params.getConsole().getStdOut(), stream -> {});
    }
    return CloseableWrapper.of(
        new PrintStream(new BufferedOutputStream(Files.newOutputStream(outputFile.toPath()))),
        stream -> stream.close());
  }

  /**
   * Convert a map by param name to a map by string. It is a common operation in query
   * implementations.
   */
  protected static ImmutableSortedMap<String, Object> attrMapToMapBySnakeCase(
      Map<ParamNameOrSpecial, Object> attrs) {
    return attrs.entrySet().stream()
        .collect(
            ImmutableSortedMap.toImmutableSortedMap(
                Comparator.naturalOrder(), e -> e.getKey().getSnakeCase(), Map.Entry::getValue));
  }
}
