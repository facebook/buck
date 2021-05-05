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
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.parser.api.FileManifest;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** A pipeline that provides cached parsed results for a given file. */
public abstract class GenericFileParsePipeline<T extends FileManifest>
    implements FileParsePipeline<T> {
  protected final BuckEventBus eventBus;
  protected final PipelineNodeCache<ForwardRelPath, T> cache;
  private final ListeningExecutorService executorService;
  private final FileParserPool<T> fileParserPool;
  private final Watchman watchman;
  protected final AtomicBoolean shuttingDown;

  public GenericFileParsePipeline(
      PipelineNodeCache<ForwardRelPath, T> cache,
      FileParserPool<T> fileParserPool,
      ListeningExecutorService executorService,
      BuckEventBus eventBus,
      Watchman watchman) {
    this.eventBus = eventBus;
    this.executorService = executorService;
    this.cache = cache;
    this.fileParserPool = fileParserPool;
    this.watchman = watchman;
    shuttingDown = new AtomicBoolean(false);
  }

  @Override
  public ListenableFuture<T> getFileJob(Cell cell, ForwardRelPath manifestFile)
      throws BuildTargetException {
    if (shuttingDown.get()) {
      return Futures.immediateCancelledFuture();
    }

    AbsPath absManifestPath = cell.getFilesystem().resolve(manifestFile);

    return cache.getJobWithCacheLookup(
        cell,
        manifestFile,
        () -> {
          if (shuttingDown.get()) {
            return Futures.immediateCancelledFuture();
          }

          if (cell.getFilesystem().isIgnored(manifestFile)) {
            throw new HumanReadableException(
                "Content of '%s' cannot be built because it is defined in an ignored directory.",
                absManifestPath);
          }

          return fileParserPool.getManifest(
              eventBus, cell, watchman, absManifestPath, executorService);
        },
        eventBus);
  }

  @Override
  public void close() {
    shuttingDown.set(true);
    fileParserPool.close();
  }
}
