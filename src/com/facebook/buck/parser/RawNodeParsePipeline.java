/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.parser.PipelineNodeCache.Cache;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public class RawNodeParsePipeline extends ParsePipeline<Map<String, Object>> {

  private final PipelineNodeCache<Path, ImmutableSet<Map<String, Object>>> cache;
  private final ListeningExecutorService executorService;
  private final ProjectBuildFileParserPool projectBuildFileParserPool;

  public RawNodeParsePipeline(
      Cache<Path, ImmutableSet<Map<String, Object>>> cache,
      ProjectBuildFileParserPool projectBuildFileParserPool,
      ListeningExecutorService executorService,
      BuckEventBus eventBus) {
    super(eventBus);
    this.executorService = executorService;
    this.cache = new PipelineNodeCache<>(cache);
    this.projectBuildFileParserPool = projectBuildFileParserPool;
  }

  /**
   * @param cellRoot root path to the cell the rule is defined in.
   * @param map the map of values that define the rule.
   * @param rulePathForDebug path to the build file the rule is defined in, only used for debugging.
   * @return the build target defined by the rule.
   */
  public static UnflavoredBuildTarget parseBuildTargetFromRawRule(
      Path cellRoot, Optional<String> cellName, Map<String, Object> map, Path rulePathForDebug) {
    String basePath = (String) map.get("buck.base_path");
    String name = (String) map.get("name");
    if (basePath == null || name == null) {
      throw new IllegalStateException(
          String.format(
              "Attempting to parse build target from malformed raw data in %s: %s.",
              rulePathForDebug, Joiner.on(",").withKeyValueSeparator("->").join(map)));
    }
    Path otherBasePath = cellRoot.relativize(MorePaths.getParentOrEmpty(rulePathForDebug));
    if (!otherBasePath.equals(otherBasePath.getFileSystem().getPath(basePath))) {
      throw new IllegalStateException(
          String.format(
              "Raw data claims to come from [%s], but we tried rooting it at [%s].",
              basePath, otherBasePath));
    }
    return UnflavoredBuildTarget.builder()
        .setBaseName(UnflavoredBuildTarget.BUILD_TARGET_PREFIX + basePath)
        .setShortName(name)
        .setCellPath(cellRoot)
        .setCell(cellName)
        .build();
  }

  @Override
  public ListenableFuture<ImmutableSet<Map<String, Object>>> getAllNodesJob(
      Cell cell, KnownBuildRuleTypes knownBuildRuleTypes, Path buildFile, AtomicLong processedBytes)
      throws BuildTargetException {

    if (shuttingDown()) {
      return Futures.immediateCancelledFuture();
    }

    return cache.getJobWithCacheLookup(
        cell,
        buildFile,
        () -> {
          if (shuttingDown()) {
            return Futures.immediateCancelledFuture();
          }

          return projectBuildFileParserPool.getAllRulesAndMetaRules(
              cell, buildFile, processedBytes, executorService);
        },
        eventBus);
  }

  @Override
  public ListenableFuture<Map<String, Object>> getNodeJob(
      Cell cell,
      KnownBuildRuleTypes knownBuildRuleTypes,
      BuildTarget buildTarget,
      AtomicLong processedBytes)
      throws BuildTargetException {
    return Futures.transformAsync(
        getAllNodesJob(
            cell,
            knownBuildRuleTypes,
            cell.getAbsolutePathToBuildFile(buildTarget),
            processedBytes),
        input -> {
          for (Map<String, Object> rawNode : input) {
            Object shortName = rawNode.get("name");
            if (buildTarget.getShortName().equals(shortName)) {
              return Futures.immediateFuture(rawNode);
            }
          }
          throw NoSuchBuildTargetException.createForMissingBuildRule(
              buildTarget, cell.getAbsolutePathToBuildFile(buildTarget));
        },
        executorService);
  }
}
