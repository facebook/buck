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

package com.facebook.buck.artifact_cache;

import com.facebook.buck.io.BorrowablePath;
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

public abstract class AbstractAsynchronousCache implements ArtifactCache {
  private static final Logger LOG = Logger.get(AbstractAsynchronousCache.class);
  private final String name;
  private final CacheReadMode cacheReadMode;

  private final ListeningExecutorService storeExecutorService;
  private final ListeningExecutorService fetchExecutorService;
  private final CacheEventListener eventListener;

  private final Optional<Long> maxStoreSize;
  private final ProjectFilesystem projectFilesystem;
  private final ArtifactCacheMode mode;

  public AbstractAsynchronousCache(
      String name,
      ArtifactCacheMode mode,
      CacheReadMode cacheReadMode,
      ListeningExecutorService storeExecutorService,
      ListeningExecutorService fetchExecutorService,
      CacheEventListener eventListener,
      Optional<Long> maxStoreSize,
      ProjectFilesystem projectFilesystem) {
    this.name = name;
    this.cacheReadMode = cacheReadMode;
    this.storeExecutorService = storeExecutorService;
    this.fetchExecutorService = fetchExecutorService;
    this.eventListener = eventListener;
    this.maxStoreSize = maxStoreSize;
    this.projectFilesystem = projectFilesystem;
    this.mode = mode;
  }

  protected final String getName() {
    return name;
  }

  protected final ArtifactCacheMode getMode() {
    return mode;
  }

  protected final ProjectFilesystem getProjectFilesystem() {
    return projectFilesystem;
  }

  protected abstract FetchResult fetchImpl(RuleKey ruleKey, LazyPath output) throws IOException;

  protected abstract StoreResult storeImpl(ArtifactInfo info, Path file) throws IOException;

  @Override
  public final ListenableFuture<CacheResult> fetchAsync(RuleKey ruleKey, LazyPath output) {
    FetchEvents events = eventListener.fetchScheduled(ruleKey);
    return fetchExecutorService.submit(
        () -> {
          try {
            events.started();
            FetchResult result = fetchImpl(ruleKey, output);
            events.finished(result);
            return result.getCacheResult();
          } catch (IOException e) {
            String msg =
                String.format("fetch(%s): %s: %s", ruleKey, e.getClass().getName(), e.getMessage());
            CacheResult result = CacheResult.error(name, mode, msg);
            events.failed(e, msg, result);
            return result;
          }
        });
  }

  @Override
  public final ListenableFuture<Void> store(final ArtifactInfo info, final BorrowablePath output) {
    if (!getCacheReadMode().isWritable()) {
      return Futures.immediateFuture(null);
    }

    long artifactSizeBytes = getFileSize(output.getPath());
    if (artifactExceedsMaximumSize(artifactSizeBytes)) {
      LOG.info(
          "Artifact too big so not storing it in the %s cache. " + "file=[%s] buildTarget=[%s]",
          name, output.getPath(), info.getBuildTarget());
      return Futures.immediateFuture(null);
    }

    final Path tmp;
    try {
      tmp = getPathForArtifact(output);
    } catch (IOException e) {
      LOG.error(e, "Failed to store artifact in temp file: " + output.getPath().toString());
      return Futures.immediateFuture(null);
    }

    StoreEvents events = eventListener.storeScheduled(info, artifactSizeBytes);
    return storeExecutorService.submit(
        () -> {
          try {
            events.started();
            StoreResult result = storeImpl(info, tmp);
            events.finished(result);
            return null;
          } catch (IOException e) {
            String msg =
                String.format(
                    "store(%s): %s: %s",
                    info.getRuleKeys(), e.getClass().getName(), e.getMessage());
            events.failed(e, msg);
            throw new RuntimeException(e);
          }
        });
  }

  @Override
  public final CacheReadMode getCacheReadMode() {
    return cacheReadMode;
  }

  // Depending on if we can borrow the output or not, we will either use output directly or
  // hold it temporarily in hidden place.
  private Path getPathForArtifact(BorrowablePath output) throws IOException {
    Path tmp;
    if (output.canBorrow()) {
      tmp = output.getPath();
    } else {
      tmp = projectFilesystem.createTempFile("artifact", ".tmp");
      projectFilesystem.copyFile(output.getPath(), tmp);
    }
    return tmp;
  }

  private boolean artifactExceedsMaximumSize(long artifactSizeBytes) {
    if (!maxStoreSize.isPresent()) {
      return false;
    }
    return artifactSizeBytes > maxStoreSize.get();
  }

  private long getFileSize(Path path) {
    try {
      return projectFilesystem.getFileSize(path);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public interface CacheEventListener {
    StoreEvents storeScheduled(ArtifactInfo info, long artifactSizeBytes);

    FetchEvents fetchScheduled(RuleKey ruleKey);
  }

  public interface FetchEvents {
    void started();

    void finished(FetchResult result);

    void failed(IOException e, String errorMessage, CacheResult result);
  }

  public interface StoreEvents {
    void started();

    void finished(StoreResult result);

    void failed(IOException e, String errorMessage);
  }

  @BuckStyleTuple
  @Value.Immutable(builder = true)
  public interface AbstractFetchResult {
    Optional<Long> getResponseSizeBytes();

    Optional<String> getBuildTarget();

    Optional<ImmutableSet<RuleKey>> getAssociatedRuleKeys();

    Optional<Long> getArtifactSizeBytes();

    Optional<String> getArtifactContentHash();

    CacheResult getCacheResult();
  }

  @BuckStyleTuple
  @Value.Immutable(builder = true)
  public interface AbstractStoreResult {
    Optional<Long> getRequestSizeBytes();

    Optional<String> getArtifactContentHash();

    Optional<Boolean> getWasStoreSuccessful();
  }
}
