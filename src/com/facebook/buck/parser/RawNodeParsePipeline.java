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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.parser.PipelineNodeCache.Cache;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.nio.file.Path;
import java.util.Map;

public class RawNodeParsePipeline extends ParsePipeline<Map<String, Object>> {

  private final BuckEventBus eventBus;
  private final PipelineNodeCache<Path, ImmutableList<Map<String, Object>>> cache;
  private final ListeningExecutorService executorService;
  private final ProjectBuildFileParserPool projectBuildFileParserPool;
  private final Watchman watchman;

  public RawNodeParsePipeline(
      Cache<Path, ImmutableList<Map<String, Object>>> cache,
      ProjectBuildFileParserPool projectBuildFileParserPool,
      ListeningExecutorService executorService,
      BuckEventBus eventBus,
      Watchman watchman) {
    this.eventBus = eventBus;
    this.executorService = executorService;
    this.cache = new PipelineNodeCache<>(cache);
    this.projectBuildFileParserPool = projectBuildFileParserPool;
    this.watchman = watchman;
  }

  @Override
  public ListenableFuture<ImmutableList<Map<String, Object>>> getAllNodesJob(
      Cell cell, Path buildFile) throws BuildTargetException {

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

          return Futures.transform(
              projectBuildFileParserPool.getBuildFileManifest(
                  eventBus, cell, watchman, buildFile, executorService),
              buildFileManifest -> buildFileManifest.toRawNodes(),
              executorService);
        },
        eventBus);
  }

  @Override
  public ListenableFuture<Map<String, Object>> getNodeJob(Cell cell, BuildTarget buildTarget)
      throws BuildTargetException {
    return Futures.transformAsync(
        getAllNodesJob(cell, cell.getAbsolutePathToBuildFile(buildTarget)),
        input -> {
          Path pathToCheck = buildTarget.getBasePath();
          if (cell.getFilesystem().isIgnored(pathToCheck)) {
            throw new HumanReadableException(
                "Content of '%s' cannot be built because it is defined in an ignored directory.",
                pathToCheck);
          }

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

  @Override
  public void close() {
    super.close();
    projectBuildFileParserPool.close();
  }
}
