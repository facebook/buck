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
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** A pipeline that provides raw nodes for a given build file. */
public class BuildFileRawNodeParsePipeline
    implements BuildFileParsePipeline<String, Map<String, Object>> {

  private final BuckEventBus eventBus;
  private final PipelineNodeCache<Path, ImmutableMap<String, Map<String, Object>>> cache;
  private final ListeningExecutorService executorService;
  private final ProjectBuildFileParserPool projectBuildFileParserPool;
  private final Watchman watchman;
  private final AtomicBoolean shuttingDown;

  public BuildFileRawNodeParsePipeline(
      PipelineNodeCache<Path, ImmutableMap<String, Map<String, Object>>> cache,
      ProjectBuildFileParserPool projectBuildFileParserPool,
      ListeningExecutorService executorService,
      BuckEventBus eventBus,
      Watchman watchman) {
    this.eventBus = eventBus;
    this.executorService = executorService;
    this.cache = cache;
    this.projectBuildFileParserPool = projectBuildFileParserPool;
    this.watchman = watchman;
    shuttingDown = new AtomicBoolean(false);
  }

  @Override
  public ListenableFuture<ImmutableMap<String, Map<String, Object>>> getAllNodesJob(
      Cell cell, Path buildFile) throws BuildTargetException {

    if (shuttingDown.get()) {
      return Futures.immediateCancelledFuture();
    }

    return cache.getJobWithCacheLookup(
        cell,
        buildFile,
        () -> {
          if (shuttingDown.get()) {
            return Futures.immediateCancelledFuture();
          }

          Path pathToCheck = cell.getRoot().relativize(buildFile.getParent());
          if (cell.getFilesystem().isIgnored(pathToCheck)) {
            throw new HumanReadableException(
                "Content of '%s' cannot be built because it is defined in an ignored directory.",
                pathToCheck);
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
  public void close() {
    shuttingDown.set(true);
    projectBuildFileParserPool.close();
  }
}
