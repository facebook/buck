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
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.parser.api.PackageFileManifest;
import com.facebook.buck.parser.api.PackageMetadata;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

/** A pipeline that provides a {@link PackageFileManifest} for a given package file. */
public class PackageFileParsePipeline extends GenericFileParsePipeline<PackageFileManifest> {

  public PackageFileParsePipeline(
      PipelineNodeCache<AbsPath, PackageFileManifest> cache,
      PackageFileParserPool packageFileParserPool,
      ListeningExecutorService executorService,
      BuckEventBus eventBus,
      Watchman watchman) {
    super(cache, packageFileParserPool, executorService, eventBus, watchman);
  }

  /**
   * A singleton instance of a manifest when there is no defined package. We utilize a pseudo
   * package at the path, and it inherits properties of the parent package.
   */
  protected static final PackageFileManifest NONEXISTENT_PACKAGE =
      PackageFileManifest.of(
          PackageMetadata.of(true, ImmutableList.of(), ImmutableList.of()),
          ImmutableSortedSet.of(),
          ImmutableMap.of(),
          ImmutableList.of());

  @Override
  public ListenableFuture<PackageFileManifest> getFileJob(Cell cell, AbsPath packageFile)
      throws BuildTargetException {
    // If the file exists, parse the file and cache accordingly.
    RelPath relativePath = cell.getRoot().relativize(packageFile);
    if (cell.getFilesystem().isFile(relativePath)) {
      return super.getFileJob(cell, packageFile);
    }

    // Else cache an empty manifest.
    return cache.getJobWithCacheLookup(
        cell,
        packageFile,
        () -> {
          if (shuttingDown.get()) {
            return Futures.immediateCancelledFuture();
          }

          return Futures.immediateFuture(NONEXISTENT_PACKAGE);
        },
        eventBus);
  }
}
