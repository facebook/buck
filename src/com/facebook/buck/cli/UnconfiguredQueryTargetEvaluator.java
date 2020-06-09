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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.spec.BuildTargetMatcherTargetNodeParser;
import com.facebook.buck.parser.spec.TargetNodeSpec;
import com.facebook.buck.query.QueryEnvironment;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.query.UnconfiguredQueryBuildTarget;
import com.facebook.buck.query.UnconfiguredQueryTarget;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code QueryEnvironment.TargetEvaluator} implementation capable of taking strings and turning
 * them into {@code UnconfiguredQueryTarget}s.
 */
public class UnconfiguredQueryTargetEvaluator
    extends AbstractQueryPatternEvaluator<UnconfiguredQueryTarget>
    implements QueryEnvironment.TargetEvaluator<UnconfiguredQueryTarget> {

  private static final Logger LOG = Logger.get(UnconfiguredQueryTargetEvaluator.class);

  private final Parser parser;
  private final PerBuildState perBuildState;
  private final Cell rootCell;
  private final CommandLineTargetNodeSpecParser targetNodeSpecParser;

  public UnconfiguredQueryTargetEvaluator(
      Parser parser,
      PerBuildState perBuildState,
      Cell rootCell,
      BuckConfig buckConfig,
      CommandLineTargetNodeSpecParser targetNodeSpecParser) {
    super(rootCell, buckConfig);

    this.parser = parser;
    this.perBuildState = perBuildState;
    this.rootCell = rootCell;
    this.targetNodeSpecParser = targetNodeSpecParser;
  }

  /** Convenience constructor */
  public static UnconfiguredQueryTargetEvaluator from(
      Parser parser,
      PerBuildState perBuildState,
      Cell rootCell,
      Path absoluteClientWorkingDir,
      BuckConfig buckConfig) {
    CommandLineTargetNodeSpecParser targetNodeSpecParser =
        new CommandLineTargetNodeSpecParser(
            rootCell,
            absoluteClientWorkingDir,
            buckConfig,
            new BuildTargetMatcherTargetNodeParser());
    return new UnconfiguredQueryTargetEvaluator(
        parser, perBuildState, rootCell, buckConfig, targetNodeSpecParser);
  }

  @Override
  public Type getType() {
    return Type.LAZY;
  }

  @Override
  public Set<UnconfiguredQueryTarget> evaluateTarget(String target) throws QueryException {
    try {
      ImmutableMap<String, ImmutableSet<UnconfiguredQueryTarget>> keyedResults =
          resolveTargetPatterns(ImmutableSet.of(target));
      return keyedResults.values().asList().get(0);
    } catch (BuildFileParseException | InterruptedException | IOException e) {
      throw new QueryException(e, "Error in resolving targets matching %s", target);
    }
  }

  @Override
  public ImmutableSet<UnconfiguredQueryTarget> convertQueryFileTargetToNodes(
      Set<QueryFileTarget> targets) {
    return ImmutableSet.copyOf(targets);
  }

  @Override
  public ImmutableMap<String, ImmutableSet<UnconfiguredQueryTarget>> resolveBuildTargetPatterns(
      List<String> patterns) throws InterruptedException, BuildFileParseException {
    // Build up an ordered list of patterns and pass them to the parse to get resolved in one go.
    // The returned list of nodes maintains the spec list ordering.
    List<TargetNodeSpec> specs = new ArrayList<>();
    for (String pattern : patterns) {
      specs.addAll(targetNodeSpecParser.parse(rootCell, pattern));
    }
    ImmutableList<ImmutableSet<UnflavoredBuildTarget>> buildTargets =
        parser.resolveTargetSpecsUnconfigured(perBuildState, specs);
    LOG.verbose("Resolved target patterns %s -> targets %s", patterns, buildTargets);

    // Convert the ordered result into a result map of pattern to set of resolved targets.
    ImmutableMap.Builder<String, ImmutableSet<UnconfiguredQueryTarget>> queryTargets =
        ImmutableMap.builder();
    for (int index = 0; index < buildTargets.size(); index++) {
      ImmutableSet<UnflavoredBuildTarget> targets = buildTargets.get(index);
      // Sorting to have predictable results across different java libraries implementations.
      ImmutableSet.Builder<UnconfiguredQueryTarget> builder = ImmutableSet.builder();
      for (UnflavoredBuildTarget target : targets) {
        builder.add(UnconfiguredQueryBuildTarget.of(target));
      }
      queryTargets.put(patterns.get(index), builder.build());
    }
    return queryTargets.build();
  }
}
