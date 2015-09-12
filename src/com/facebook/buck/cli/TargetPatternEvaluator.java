/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.BuildTargetPatternTargetNodeParser;
import com.facebook.buck.parser.ParserConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class TargetPatternEvaluator {
  private final boolean enableProfiling;
  private final CommandRunnerParams params;
  private final ParserConfig parserConfig;
  private final Path projectRoot;
  private final CommandLineTargetNodeSpecParser targetNodeSpecParser;

  private Map<String, ImmutableSet<QueryTarget>> resolvedTargets = new HashMap<>();

  public TargetPatternEvaluator(CommandRunnerParams params, boolean enableProfiling) {
    this.enableProfiling = enableProfiling;
    this.params = params;
    this.parserConfig = new ParserConfig(params.getBuckConfig());
    this.projectRoot = params.getRepository().getFilesystem().getRootPath();
    this.targetNodeSpecParser = new CommandLineTargetNodeSpecParser(
        params.getBuckConfig(),
        new BuildTargetPatternTargetNodeParser(
            params.getRepository().getFilesystem().getIgnorePaths()));
  }

  /**
   * Attempts to parse and load the given collection of patterns.
   */
  public void preloadTargetPatterns(Iterable<String> patterns)
      throws InterruptedException, BuildFileParseException, BuildTargetException, IOException {
    for (String pattern : patterns) {
      resolveTargetPattern(pattern);
    }
  }

  ImmutableSet<QueryTarget> resolveTargetPattern(String pattern)
      throws InterruptedException, BuildFileParseException, BuildTargetException, IOException {
    // First check if this pattern was resolved before.
    ImmutableSet<QueryTarget> targets = resolvedTargets.get(pattern);
    if (targets != null) {
      return targets;
    }
    // Check if this is an alias.
    BuildTarget alias = params.getBuckConfig().getBuildTargetForAlias(pattern).getFirst();
    if (alias != null) {
      targets = resolveBuildTargetPattern(alias.getFullyQualifiedName());
    } else {
      // Check if the pattern corresponds to a build target or a path.
      if (pattern.startsWith("//") || pattern.startsWith(":") || pattern.startsWith("@")) {
        targets = resolveBuildTargetPattern(pattern);
      } else {
        targets = resolveFilePattern(pattern);
      }
    }
    resolvedTargets.put(pattern, targets);
    return targets;
  }

  ImmutableSet<QueryTarget> resolveFilePattern(String pattern) throws IOException {
    ImmutableSet<Path> filePaths =
        PathArguments.getCanonicalFilesUnderProjectRoot(projectRoot, ImmutableList.of(pattern))
            .relativePathsUnderProjectRoot;
    ImmutableSet.Builder<QueryTarget> builder = ImmutableSortedSet.naturalOrder();
    for (Path filePath : filePaths) {
      builder.add(QueryFileTarget.of(filePath));
    }
    return builder.build();
  }

  ImmutableSet<QueryTarget> resolveBuildTargetPattern(String pattern)
      throws InterruptedException, BuildFileParseException, BuildTargetException, IOException {
    Set<BuildTarget> buildTargets = params.getParser()
        .resolveTargetSpec(
            targetNodeSpecParser.parse(pattern),
            parserConfig,
            params.getBuckEventBus(),
            params.getConsole(),
            params.getEnvironment(),
            enableProfiling);
    // Sorting to have predictable results across different java libraries implementations.
    ImmutableSet.Builder<QueryTarget> builder = ImmutableSortedSet.naturalOrder();
    for (BuildTarget target : buildTargets) {
      builder.add(QueryBuildTarget.of(target));
    }
    return builder.build();
  }
}
