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

import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.spec.BuildTargetMatcherTargetNodeParser;
import com.facebook.buck.parser.spec.TargetNodeSpec;
import com.facebook.buck.query.ConfiguredQueryBuildTarget;
import com.facebook.buck.query.ConfiguredQueryTarget;
import com.facebook.buck.query.QueryFileTarget;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Class capable of taking strings and turning them into {@code ConfiguredQueryTarget}s. */
class TargetPatternEvaluator extends AbstractQueryPatternEvaluator<ConfiguredQueryTarget> {
  private static final Logger LOG = Logger.get(TargetPatternEvaluator.class);

  private final TargetUniverse targetUniverse;
  private final CommandLineTargetNodeSpecParser targetNodeSpecParser;
  private final Cells cells;

  public TargetPatternEvaluator(
      TargetUniverse targetUniverse,
      Cells cells,
      Path absoluteClientWorkingDir,
      BuckConfig buckConfig) {
    super(cells.getRootCell(), buckConfig);

    this.targetUniverse = targetUniverse;
    this.cells = cells;
    this.targetNodeSpecParser =
        new CommandLineTargetNodeSpecParser(
            cells, absoluteClientWorkingDir, buckConfig, new BuildTargetMatcherTargetNodeParser());
  }

  /** Attempts to parse and load the given collection of patterns. */
  void preloadTargetPatterns(Iterable<String> patterns)
      throws InterruptedException, BuildFileParseException, IOException {
    resolveTargetPatterns(patterns);
  }

  @Override
  public ImmutableSet<ConfiguredQueryTarget> convertQueryFileTargetToNodes(
      Set<QueryFileTarget> targets) {
    return ImmutableSet.copyOf(targets);
  }

  @Override
  public ImmutableMap<String, ImmutableSet<ConfiguredQueryTarget>> resolveBuildTargetPatterns(
      List<String> patterns) throws InterruptedException, BuildFileParseException {

    // Build up an ordered list of patterns and pass them to the parse to get resolved in one go.
    // The returned list of nodes maintains the spec list ordering.
    List<TargetNodeSpec> specs = new ArrayList<>();
    for (String pattern : patterns) {
      specs.addAll(targetNodeSpecParser.parse(cells, pattern));
    }
    ImmutableList<ImmutableSet<BuildTarget>> buildTargets =
        targetUniverse.resolveTargetSpecs(specs);
    LOG.verbose("Resolved target patterns %s -> targets %s", patterns, buildTargets);

    // Convert the ordered result into a result map of pattern to set of resolved targets.
    ImmutableMap.Builder<String, ImmutableSet<ConfiguredQueryTarget>> queryTargets =
        ImmutableMap.builder();
    for (int index = 0; index < buildTargets.size(); index++) {
      ImmutableSet<BuildTarget> targets = buildTargets.get(index);
      // Sorting to have predictable results across different java libraries implementations.
      ImmutableSet.Builder<ConfiguredQueryTarget> builder = ImmutableSet.builder();
      for (BuildTarget target : targets) {
        builder.add(ConfiguredQueryBuildTarget.of(target));
      }
      queryTargets.put(patterns.get(index), builder.build());
    }
    return queryTargets.build();
  }
}
