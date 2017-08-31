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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class MultiSourceContentsProvider implements FileContentsProvider {
  private static final Logger LOG = Logger.get(MultiSourceContentsProvider.class);

  private final FileContentsProvider serverContentsProvider;
  private final Optional<LocalFsContentsProvider> localFsProvider;
  private final InlineContentsProvider inlineProvider;
  private final FileMaterializationStatsTracker fileMaterializationStatsTracker;

  public MultiSourceContentsProvider(
      ServerContentsProvider serverContentProvider,
      FileMaterializationStatsTracker fileMaterializationStatsTracker,
      ListeningExecutorService executor,
      Optional<Path> localCacheAbsPath)
      throws InterruptedException, IOException {
    this(
        new InlineContentsProvider(executor),
        localCacheAbsPath.map(
            path -> {
              try {
                return new LocalFsContentsProvider(path);
              } catch (InterruptedException | IOException e) {
                throw new RuntimeException(e);
              }
            }),
        serverContentProvider,
        fileMaterializationStatsTracker);
  }

  @VisibleForTesting
  MultiSourceContentsProvider(
      InlineContentsProvider inlineProvider,
      Optional<LocalFsContentsProvider> localFsProvider,
      ServerContentsProvider serverContentsProvider,
      FileMaterializationStatsTracker fileMaterializationStatsTracker) {
    this.inlineProvider = inlineProvider;
    this.localFsProvider = localFsProvider;
    this.serverContentsProvider = serverContentsProvider;
    this.fileMaterializationStatsTracker = fileMaterializationStatsTracker;
  }

  private void postInlineMaterializationHelper(Path targetAbsPath) {
    LOG.info("Materialized source file using Inline Data: [%s]", targetAbsPath);
  }

  private void postLocalFsMaterializationHelper(Path targetAbsPath) {
    fileMaterializationStatsTracker.recordLocalFileMaterialized();
    LOG.info("Materialized source file using Local Source File Cache: [%s]", targetAbsPath);
  }

  private void postRemoteMaterializationHelper(
      Path targetAbsPath, BuildJobStateFileHashEntry entry, long elapsedTimeMillis)
      throws IOException {
    fileMaterializationStatsTracker.recordRemoteFileMaterialized(elapsedTimeMillis);
    if (localFsProvider.isPresent()) {
      localFsProvider.get().writeFileAndGetInputStream(entry, targetAbsPath);
    }

    LOG.info("Materialized source file from CAS Server: [%s]", targetAbsPath);
  }

  @Override
  public ListenableFuture<Boolean> materializeFileContentsAsync(
      BuildJobStateFileHashEntry entry, Path targetAbsPath) {
    return Futures.transformAsync(
        inlineProvider.materializeFileContentsAsync(entry, targetAbsPath),
        (inlineSuccess) -> {
          if (inlineSuccess) {
            postInlineMaterializationHelper(targetAbsPath);
            return Futures.immediateFuture(true);
          }

          ListenableFuture<Boolean> localFsFuture;
          if (localFsProvider.isPresent()) {
            localFsFuture =
                localFsProvider.get().materializeFileContentsAsync(entry, targetAbsPath);
          } else {
            localFsFuture = Futures.immediateFuture(false);
          }

          return Futures.transformAsync(
              localFsFuture,
              (localFsSuccess) -> {
                if (localFsSuccess) {
                  postLocalFsMaterializationHelper(targetAbsPath);
                  return Futures.immediateFuture(true);
                }

                Stopwatch remoteMaterializationStopwatch = Stopwatch.createStarted();
                return Futures.transformAsync(
                    serverContentsProvider.materializeFileContentsAsync(entry, targetAbsPath),
                    (remoteSuccess) -> {
                      if (remoteSuccess) {
                        postRemoteMaterializationHelper(
                            targetAbsPath,
                            entry,
                            remoteMaterializationStopwatch.elapsed(TimeUnit.MILLISECONDS));
                      }
                      return Futures.immediateFuture(remoteSuccess);
                    });
              });
        });
  }

  @Override
  public void close() throws IOException {
    serverContentsProvider.close();
    inlineProvider.close();
    if (localFsProvider.isPresent()) {
      localFsProvider.get().close();
    }
  }
}
