/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.log.Logger;
import com.google.common.base.Stopwatch;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class MultiSourceContentsProvider implements FileContentsProvider {
  private static final Logger LOG = Logger.get(MultiSourceContentsProvider.class);

  private final FileContentsProvider serverProvider;
  private final Optional<LocalFsContentsProvider> localFsProvider;
  private final InlineContentsProvider inlineProvider;
  private final FileMaterializationStatsTracker fileMaterializationStatsTracker;

  public MultiSourceContentsProvider(
      DistBuildService service,
      FileMaterializationStatsTracker fileMaterializationStatsTracker,
      Optional<Path> localCacheAbsPath)
      throws InterruptedException, IOException {
    this(new ServerContentsProvider(service), fileMaterializationStatsTracker, localCacheAbsPath);
  }

  public MultiSourceContentsProvider(
      FileContentsProvider serverContentProvider,
      FileMaterializationStatsTracker fileMaterializationStatsTracker,
      Optional<Path> localCacheAbsPath)
      throws InterruptedException, IOException {
    this.inlineProvider = new InlineContentsProvider();
    this.fileMaterializationStatsTracker = fileMaterializationStatsTracker;
    if (localCacheAbsPath.isPresent()) {
      this.localFsProvider = Optional.of(new LocalFsContentsProvider(localCacheAbsPath.get()));
    } else {
      this.localFsProvider = Optional.empty();
    }

    this.serverProvider = serverContentProvider;
  }

  @Override
  public boolean materializeFileContents(BuildJobStateFileHashEntry entry, Path targetAbsPath)
      throws IOException {

    if (inlineProvider.materializeFileContents(entry, targetAbsPath)) {
      LOG.info("Materialized source file using Inline Data: [%s]", targetAbsPath);
      return true;
    }

    if (localFsProvider.isPresent()
        && localFsProvider.get().materializeFileContents(entry, targetAbsPath)) {
      fileMaterializationStatsTracker.recordLocalFileMaterialized();
      LOG.info("Materialized source file using Local Source File Cache: [%s]", targetAbsPath);
      return true;
    }

    Stopwatch remoteMaterializationStopwatch = Stopwatch.createStarted();
    boolean wasRemotelyMaterialized = serverProvider.materializeFileContents(entry, targetAbsPath);
    remoteMaterializationStopwatch.stop();
    if (wasRemotelyMaterialized) {
      fileMaterializationStatsTracker.recordRemoteFileMaterialized(
          remoteMaterializationStopwatch.elapsed(TimeUnit.MILLISECONDS));
      if (localFsProvider.isPresent()) {
        localFsProvider.get().writeFileAndGetInputStream(entry, targetAbsPath);
      }

      LOG.info("Materialized source file from CAS Server: [%s]", targetAbsPath);
      return true;
    }

    return false;
  }
}
