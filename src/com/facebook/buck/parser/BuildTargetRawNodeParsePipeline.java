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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.util.string.MoreStrings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.Map;
import java.util.OptionalLong;

/** A pipeline that provides access to a raw node by its {@link BuildTarget}. */
public class BuildTargetRawNodeParsePipeline
    implements BuildTargetParsePipeline<Map<String, Object>> {

  private final ListeningExecutorService executorService;
  private final BuildFileRawNodeParsePipeline buildFileRawNodeParsePipeline;

  public BuildTargetRawNodeParsePipeline(
      ListeningExecutorService executorService,
      BuildFileRawNodeParsePipeline buildFileRawNodeParsePipeline) {
    this.executorService = executorService;
    this.buildFileRawNodeParsePipeline = buildFileRawNodeParsePipeline;
  }

  /**
   * If a target wasn't found in a build file, find targets that are pretty closely named. This
   * makes error messages more useful
   */
  private static ImmutableList<UnconfiguredBuildTarget> findSimilarTargets(
      ImmutableMap<String, ImmutableMap<String, Object>> buildFileTargets,
      UnconfiguredBuildTarget expectedTarget,
      int maxLevenshteinDistance) {

    UnconfiguredBuildTarget targetWithoutFlavors = expectedTarget.withoutFlavors();
    String expectedShortName = expectedTarget.getName();

    ImmutableList.Builder<UnconfiguredBuildTarget> builder = ImmutableList.builder();
    for (String shortName : buildFileTargets.keySet()) {
      if (shortName.startsWith(expectedShortName) || expectedShortName.startsWith(shortName)) {
        builder.add(targetWithoutFlavors.withLocalName(shortName));
        continue;
      }

      int distance = MoreStrings.getLevenshteinDistance(shortName, expectedShortName);
      if (distance < maxLevenshteinDistance) {
        builder.add(targetWithoutFlavors.withLocalName(shortName));
      }
    }
    return builder.build().stream().sorted().collect(ImmutableList.toImmutableList());
  }

  @Override
  public ListenableFuture<Map<String, Object>> getNodeJob(
      Cell cell, UnconfiguredBuildTarget buildTarget) throws BuildTargetException {
    return Futures.transformAsync(
        buildFileRawNodeParsePipeline.getFileJob(
            cell,
            cell.getBuckConfigView(ParserConfig.class)
                .getAbsolutePathToBuildFile(cell, buildTarget)),
        input -> {
          if (!input.getTargets().containsKey(buildTarget.getName())) {
            ParserConfig parserConfig = cell.getBuckConfigView(ParserConfig.class);
            ImmutableList<UnconfiguredBuildTarget> similarTargets =
                findSimilarTargets(
                    input.getTargets(),
                    buildTarget,
                    parserConfig.getMissingTargetLevenshteinDistance());
            throw NoSuchBuildTargetException.createForMissingBuildRule(
                buildTarget,
                similarTargets,
                input.getTargets().size(),
                (buildFilePath) -> {
                  try {
                    return OptionalLong.of(cell.getFilesystem().getFileSize(buildFilePath));
                  } catch (IOException e) {
                    return OptionalLong.empty();
                  }
                },
                parserConfig.getAbsolutePathToBuildFile(cell, buildTarget).getPath());
          }
          return Futures.immediateFuture(input.getTargets().get(buildTarget.getName()));
        },
        executorService);
  }

  @Override
  public void close() {
    buildFileRawNodeParsePipeline.close();
  }
}
