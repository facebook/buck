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
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.parser.api.FileManifest;
import com.facebook.buck.parser.api.FileParser;
import com.facebook.buck.util.concurrent.ResourcePool;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.concurrent.GuardedBy;

/**
 * Allows multiple concurrently executing futures to share a constrained number of parsers.
 *
 * <p>Parser instances are lazily created up till a fixed maximum. If more than max parser are
 * requested the associated 'requests' are queued up. As soon as a parser is returned it will be
 * used to satisfy the first pending request, otherwise it is "parked".
 */
abstract class FileParserPool<T extends FileManifest> implements AutoCloseable {
  private final int maxParsersPerCell;

  @GuardedBy("this")
  final Map<Cell, ResourcePool<FileParser<T>>> parserResourcePools;

  @GuardedBy("this")
  private final Map<Cell, FileParser<T>> nonPooledCells;

  private final FileParserFactory<T> fileParserFactory;
  private final AtomicBoolean closing;

  /** @param maxParsersPerCell maximum number of parsers to create for a single cell. */
  public FileParserPool(int maxParsersPerCell, FileParserFactory<T> fileParserFactory) {
    Preconditions.checkArgument(maxParsersPerCell > 0);

    this.maxParsersPerCell = maxParsersPerCell;
    this.parserResourcePools = new HashMap<>();
    this.nonPooledCells = new HashMap<>();
    this.fileParserFactory = fileParserFactory;
    this.closing = new AtomicBoolean(false);
  }

  /**
   * @param cell the cell in which we're parsing
   * @param parseFile the file to parse
   * @param executorService where to perform the parsing.
   * @return a {@link ListenableFuture} containing the result of the parsing. The future will be
   *     cancelled if the {@link ProjectBuildFileParserPool#close()} method is called.
   */
  public ListenableFuture<T> getManifest(
      BuckEventBus buckEventBus,
      Cell cell,
      Watchman watchman,
      AbsPath parseFile,
      ListeningExecutorService executorService) {
    Preconditions.checkState(!closing.get());

    if (shouldUsePoolForCell(cell)) {
      return getResourcePoolForCell(buckEventBus, cell, watchman)
          .scheduleOperationWithResource(
              parser -> parser.getManifest(parseFile.getPath()), executorService);
    }
    FileParser<T> parser = getParserForCell(buckEventBus, cell, watchman);
    return executorService.submit(() -> parser.getManifest(parseFile.getPath()));
  }

  private synchronized ResourcePool<FileParser<T>> getResourcePoolForCell(
      BuckEventBus buckEventBus, Cell cell, Watchman watchman) {
    return parserResourcePools.computeIfAbsent(
        cell,
        c ->
            new ResourcePool<>(
                maxParsersPerCell,
                // If the Python process garbles the output stream then the bser codec doesn't
                // always
                // recover and subsequent attempts at invoking the parser will fail.
                ResourcePool.ResourceUsageErrorPolicy.RETIRE,
                () -> fileParserFactory.createFileParser(buckEventBus, c, watchman, false)));
  }

  private synchronized FileParser<T> getParserForCell(
      BuckEventBus buckEventBus, Cell cell, Watchman watchman) {
    return nonPooledCells.computeIfAbsent(
        cell, c -> fileParserFactory.createFileParser(buckEventBus, c, watchman, false));
  }

  abstract void reportProfile();

  @Override
  public void close() {
    reportProfile();
    ImmutableSet<ResourcePool<FileParser<T>>> resourcePools;
    ImmutableSet<FileParser<T>> parsers;
    synchronized (this) {
      Preconditions.checkState(!closing.get());
      closing.set(true);
      resourcePools = ImmutableSet.copyOf(parserResourcePools.values());
      parsers = ImmutableSet.copyOf(nonPooledCells.values());
    }
    for (FileParser<T> parser : parsers) {
      try {
        parser.close();
      } catch (InterruptedException | IOException e) {
        throw new RuntimeException("Could not properly close a parser.", e);
      }
    }
    resourcePools.forEach(ResourcePool::close);
  }

  abstract boolean shouldUsePoolForCell(Cell cell);
}
