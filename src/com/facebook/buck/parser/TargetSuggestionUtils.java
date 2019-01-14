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
package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.util.string.MoreStrings;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Common functions that related to build targets */
public class TargetSuggestionUtils {
  private static final Logger LOG = Logger.get(TargetSuggestionUtils.class);
  private static final String UI = "UI";
  private static final String MAXIMUM_TARGET_TYPO_DISTANCE_ALLOWED =
      "maximum_target_typo_distance_allowed";
  private static final String MAXIMUM_TARGET_SUGGESTIONS_DISPLAY =
      "maximum_target_suggestions_display";

  private TargetSuggestionUtils() {}

  /**
   * A function that given a buildTarget that not exist, return a list of suggestions. Suggested
   * targets are not necessarily in the same target file, i.e. input //foo:bar could lead to
   * //foo:foo and //bar:bar if they exist. The return results are the full qualified name and
   * ordered in the Levenstein distances.
   */
  public static ImmutableList<String> getTypoSuggestions(
      Cell cell,
      BuildTarget buildTarget,
      BuildFileRawNodeParsePipeline buildFileRawNodeParsePipeline) {
    BuckConfig config = cell.getBuckConfig();
    int maxTargetTypoDistanceAllowed =
        config.getInteger(UI, MAXIMUM_TARGET_TYPO_DISTANCE_ALLOWED).orElse(3);
    int maxTargetSuggestionsDisplay =
        config.getInteger(UI, MAXIMUM_TARGET_SUGGESTIONS_DISPLAY).orElse(3);

    if (maxTargetTypoDistanceAllowed <= 0 || maxTargetSuggestionsDisplay <= 0) {
      return ImmutableList.of();
    }

    Path cellPath = cell.getRoot();
    List<Path> buildFileSuggestions =
        MorePaths.getPathSuggestions(
                cell.getAbsolutePathToBuildFile(buildTarget),
                cellPath,
                maxTargetTypoDistanceAllowed)
            .stream()
            .map(Pair::getFirst)
            .collect(Collectors.toList());
    if (buildFileSuggestions.isEmpty()) {
      return ImmutableList.of();
    }
    List<String> candidateQualifiedName = new ArrayList<>();
    for (Path buildFileSuggestion : buildFileSuggestions) {
      String targetPrefix =
          "//"
              + MorePaths.pathWithUnixSeparators(
                  cellPath.relativize(buildFileSuggestion.getParent()).toString())
              + ":";
      try {
        buildFileRawNodeParsePipeline
            .getAllNodes(cell, buildFileSuggestion)
            .getTargets()
            .keySet()
            .stream()
            .map(target -> targetPrefix + target)
            .forEach(candidateQualifiedName::add);
      } catch (BuildFileParseException e) {
        LOG.warn(e, "Fail to parse suggested build file: %s", buildFileSuggestion);
      }
    }

    if (candidateQualifiedName.isEmpty()) {
      return ImmutableList.of();
    }

    ImmutableList<String> suggestions =
        MoreStrings.getSpellingSuggestions(
            buildTarget.getFullyQualifiedName(),
            candidateQualifiedName,
            maxTargetTypoDistanceAllowed);
    return suggestions.subList(0, Math.min(suggestions.size(), maxTargetSuggestionsDisplay));
  }
}
