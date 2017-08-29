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
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import javax.annotation.Nullable;
import org.immutables.value.Value;

public abstract class AbstractAsynchronousCache implements ArtifactCache {
  private static final Logger LOG = Logger.get(AbstractAsynchronousCache.class);
  private final String name;
  private final CacheReadMode cacheReadMode;

  private final ListeningExecutorService storeExecutorService;
  // TODO(cjhopman): The fetch handling would probably be simpler if we created a threadpool
  // ourselves and just ran a bunch of threads just continuously running processFetch() and have
  // that just take() FetchRequests off a BlockingQueue.
  private final ListeningExecutorService fetchExecutorService;
  private final CacheEventListener eventListener;

  private final Optional<Long> maxStoreSize;
  private final ProjectFilesystem projectFilesystem;
  private final ArtifactCacheMode mode;

  private final BlockingQueue<FetchRequest> pendingFetchRequests = new LinkedBlockingQueue<>();

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

  /** The MultiFetchResult should contain results in the same order as the requests. */
  protected abstract MultiFetchResult multiFetchImpl(Iterable<FetchRequest> requests)
      throws IOException;

  private void doMultiFetch(ImmutableList<FetchRequest> requests) {
    requests.forEach(r -> r.events.multiFetchStarted());
    try {
      MultiFetchResult result = multiFetchImpl(requests);
      Preconditions.checkState(result.getResults().size() == requests.size());
      // MultiFetch must return a non-skipped result for at least one of the requested keys.
      Preconditions.checkState(
          result
              .getResults()
              .stream()
              .anyMatch(
                  fetchResult ->
                      fetchResult.getCacheResult().getType() != CacheResultType.SKIPPED));
      for (int i = 0; i < requests.size(); i++) {
        FetchRequest thisRequest = requests.get(i);
        FetchResult thisResult = result.getResults().get(i);
        if (thisResult.getCacheResult().getType() == CacheResultType.SKIPPED) {
          thisRequest.events.multiFetchSkipped();
          addFetchRequest(thisRequest);
        } else {
          thisRequest.events.multiFetchFinished(thisResult);
          thisRequest.future.set(thisResult.getCacheResult());
        }
      }
    } catch (IOException e) {
      ImmutableList<RuleKey> keys =
          requests.stream().map(FetchRequest::getRuleKey).collect(MoreCollectors.toImmutableList());
      String msg =
          String.format(
              "multifetch(<%s>): %s: %s",
              Joiner.on(", ").join(keys), e.getClass().getName(), e.getMessage());
      // Some of these might already be fulfilled. That's fine, this set() call will just be
      // ignored.
      requests.forEach(
          r -> {
            CacheResult result = CacheResult.error(name, mode, msg);
            r.events.multiFetchFailed(e, msg, result);
            r.future.set(result);
          });
    }
  }

  private void doFetch(FetchRequest request) {
    CacheResult result;
    try {
      request.events.started();
      FetchResult fetchResult = fetchImpl(request.getRuleKey(), request.getOutput());
      request.events.finished(fetchResult);
      result = fetchResult.getCacheResult();
    } catch (IOException e) {
      String msg =
          String.format(
              "fetch(%s): %s: %s", request.getRuleKey(), e.getClass().getName(), e.getMessage());
      result = CacheResult.error(name, mode, msg);
      request.events.failed(e, msg, result);
    }
    request.future.set(result);
  }

  private void processFetch() {
    int multiFetchLimit = getMultiFetchBatchSize(pendingFetchRequests.size());
    if (multiFetchLimit > 0) {
      ImmutableList.Builder<FetchRequest> requestsBuilder = ImmutableList.builder();
      for (int i = 0; i < multiFetchLimit; i++) {
        FetchRequest request = getFetchRequest();
        if (request == null) {
          break;
        }
        requestsBuilder.add(request);
      }
      ImmutableList<FetchRequest> requests = requestsBuilder.build();
      if (requests.isEmpty()) {
        return;
      }
      doMultiFetch(requests);
    } else {
      FetchRequest request = getFetchRequest();
      if (request == null) {
        return;
      }
      doFetch(request);
    }
  }

  /**
   * Used to compute the number of keys to include in every multiFetchRequest. If < 1, fetch will be
   * used instead of multifetch.
   */
  @SuppressWarnings("unused")
  protected int getMultiFetchBatchSize(int pendingRequestsSize) {
    return 0;
  }

  @Nullable
  private FetchRequest getFetchRequest() {
    return pendingFetchRequests.poll();
  }

  private void addFetchRequest(FetchRequest fetchRequest) {
    pendingFetchRequests.add(fetchRequest);
    fetchExecutorService.submit(this::processFetch);
  }

  @Override
  public final ListenableFuture<CacheResult> fetchAsync(RuleKey ruleKey, LazyPath output) {
    FetchEvents events = eventListener.fetchScheduled(ruleKey);
    SettableFuture<CacheResult> future = SettableFuture.create();
    addFetchRequest(new FetchRequest(ruleKey, output, events, future));
    return future;
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

    void multiFetchStarted();

    void multiFetchSkipped();

    void multiFetchFinished(FetchResult thisResult);

    void multiFetchFailed(IOException e, String msg, CacheResult result);
  }

  public interface StoreEvents {
    void started();

    void finished(StoreResult result);

    void failed(IOException e, String errorMessage);
  }

  protected static class FetchRequest {
    private final RuleKey ruleKey;
    private final LazyPath output;
    private final FetchEvents events;
    private final SettableFuture<CacheResult> future;

    private FetchRequest(
        RuleKey ruleKey, LazyPath output, FetchEvents events, SettableFuture<CacheResult> future) {
      this.ruleKey = ruleKey;
      this.output = output;
      this.events = events;
      this.future = future;
    }

    public RuleKey getRuleKey() {
      return ruleKey;
    }

    public LazyPath getOutput() {
      return output;
    }
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
  public interface AbstractMultiFetchResult {
    /** At least one of the results must be non-skipped. */
    ImmutableList<FetchResult> getResults();
  }

  @BuckStyleTuple
  @Value.Immutable(builder = true)
  public interface AbstractStoreResult {
    Optional<Long> getRequestSizeBytes();

    Optional<String> getArtifactContentHash();

    Optional<Boolean> getWasStoreSuccessful();
  }
}
