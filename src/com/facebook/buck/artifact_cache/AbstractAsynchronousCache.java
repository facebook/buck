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

import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.artifact_cache.config.CacheReadMode;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.types.Pair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import org.immutables.value.Value;

public abstract class AbstractAsynchronousCache implements ArtifactCache {
  private static final Logger LOG = Logger.get(AbstractAsynchronousCache.class);
  private static final int MAX_CONSECUTIVE_MULTI_FETCH_ERRORS = 3;
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

  // TODO(cjhopman): Remove this error-based disabling of multiFetch, it's only here to make rollout
  // less disruptive.
  private volatile boolean enableMultiFetch = true;
  private final AtomicInteger consecutiveMultiFetchErrorCount = new AtomicInteger();
  private volatile boolean markAllFetchRequestsAsSkipped = false;

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

  protected abstract MultiContainsResult multiContainsImpl(ImmutableSet<RuleKey> ruleKeys)
      throws IOException;

  protected abstract StoreResult storeImpl(ArtifactInfo info, Path file) throws IOException;

  protected abstract CacheDeleteResult deleteImpl(List<RuleKey> ruleKeys) throws IOException;

  /** The MultiFetchResult should contain results in the same order as the requests. */
  protected abstract MultiFetchResult multiFetchImpl(Iterable<FetchRequest> requests)
      throws IOException;

  /**
   * Used to compute the number of keys to include in every multiFetchRequest. If < 1, fetch will be
   * used instead of multifetch.
   */
  @SuppressWarnings("unused")
  protected int getMultiFetchBatchSize(int pendingRequestsSize) {
    return 0;
  }

  private void doMultiFetch(ImmutableList<ClaimedFetchRequest> requests) {
    boolean gotNonError = false;
    try (CacheEventListener.MultiFetchRequestEvents requestEvents =
        eventListener.multiFetchStarted(
            requests
                .stream()
                .map(r -> r.getRequest().getBuildTarget())
                .filter(Objects::nonNull)
                .collect(ImmutableList.toImmutableList()),
            requests
                .stream()
                .map(r -> r.getRequest().getRuleKey())
                .collect(ImmutableList.toImmutableList()))) {
      try {
        MultiFetchResult result =
            multiFetchImpl(
                requests
                    .stream()
                    .map(ClaimedFetchRequest::getRequest)
                    .collect(ImmutableList.toImmutableList()));
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
          ClaimedFetchRequest thisRequest = requests.get(i);
          FetchResult thisResult = result.getResults().get(i);
          if (thisResult.getCacheResult().getType() == CacheResultType.SKIPPED) {
            requestEvents.skipped(i);
            thisRequest.reschedule();
          } else {
            requestEvents.finished(i, thisResult);
            thisRequest.setResult(thisResult.getCacheResult());
          }
        }
        gotNonError =
            result
                .getResults()
                .stream()
                .anyMatch(
                    fetchResult -> fetchResult.getCacheResult().getType() != CacheResultType.ERROR);
      } catch (IOException e) {
        ImmutableList<RuleKey> keys =
            requests
                .stream()
                .map(r -> r.getRequest().getRuleKey())
                .collect(ImmutableList.toImmutableList());
        String msg =
            String.format(
                "multifetch(<%s>): %s: %s",
                Joiner.on(", ").join(keys), e.getClass().getName(), e.getMessage());
        // Some of these might already be fulfilled. That's fine, this set() call will just be
        // ignored.
        for (int i = 0; i < requests.size(); i++) {
          CacheResult result = CacheResult.error(name, mode, msg);
          requestEvents.failed(i, e, msg, result);
          requests.get(i).setResult(result);
        }
      }
    } finally {
      if (gotNonError) {
        consecutiveMultiFetchErrorCount.set(0);
      } else {
        if (consecutiveMultiFetchErrorCount.incrementAndGet()
            == MAX_CONSECUTIVE_MULTI_FETCH_ERRORS) {
          LOG.info("Too many MultiFetch errors, falling back to Fetch only.");
          enableMultiFetch = false;
        }
      }
    }
  }

  @Override
  public void skipPendingAndFutureAsyncFetches() {
    if (markAllFetchRequestsAsSkipped) {
      return; // Avoid log spam
    }
    LOG.info(
        String.format(
            "All [%d] pending, and future, fetch requests will return skipped results.",
            pendingFetchRequests.size()));
    markAllFetchRequestsAsSkipped = true;
  }

  private void doFetch(FetchRequest request) {
    CacheResult result;
    CacheEventListener.FetchRequestEvents requestEvents =
        eventListener.fetchStarted(request.getBuildTarget(), request.getRuleKey());
    try {
      FetchResult fetchResult = fetchImpl(request.getRuleKey(), request.getOutput());
      result = fetchResult.getCacheResult();
      requestEvents.finished(fetchResult);
    } catch (IOException e) {
      String msg =
          String.format(
              "fetch(%s): %s: %s", request.getRuleKey(), e.getClass().getName(), e.getMessage());
      result = CacheResult.error(name, mode, msg);
      requestEvents.failed(e, msg, result);
    }
    request.future.set(result);
  }

  private void processFetch() {
    try {
      if (markAllFetchRequestsAsSkipped) {
        // Build is finished/terminated, all pending fetch requests should be set to skipped state.
        while (true) {
          ClaimedFetchRequest request = getFetchRequest();
          if (request == null) {
            return;
          }
          String ruleKey = request.getRequest().getRuleKey().toString();
          LOG.verbose(
              String.format(
                  "Skipping cache fetch for key [%s] as markAllFetchRequestsAsSkipped=true",
                  ruleKey));
          request.setResult(CacheResult.skipped());
        }
      }

      int multiFetchLimit =
          enableMultiFetch ? getMultiFetchBatchSize(pendingFetchRequests.size()) : 0;
      if (multiFetchLimit > 0) {
        ImmutableList.Builder<ClaimedFetchRequest> requestsBuilder = ImmutableList.builder();
        try {
          for (int i = 0; i < multiFetchLimit; i++) {
            ClaimedFetchRequest request = getFetchRequest();
            if (request == null) {
              break;
            }
            requestsBuilder.add(request);
          }
          ImmutableList<ClaimedFetchRequest> requests = requestsBuilder.build();
          if (requests.isEmpty()) {
            return;
          }
          doMultiFetch(requests);
        } finally {
          requestsBuilder.build().forEach(ClaimedFetchRequest::close);
        }
      } else {
        try (ClaimedFetchRequest request = getFetchRequest()) {
          if (request == null) {
            return;
          }
          doFetch(request.getRequest());
        }
      }
    } catch (Exception e) {
      // If any exception is thrown in trying to process requests, just fulfill everything with an
      // error.
      ClaimedFetchRequest request;
      while ((request = getFetchRequest()) != null) {
        request.setResult(CacheResult.error(getName(), getMode(), e.getMessage()));
      }
      LOG.error(e, "Exception thrown while processing fetch requests.");
    }
  }

  /**
   * This is just a Scope that will set an exception on the underlying request's future if the
   * request isn't fulfilled or rescheduled and prevents any modifications to the request after
   * either of those have happened.
   */
  private class ClaimedFetchRequest implements Scope {
    @Nullable FetchRequest request;

    public ClaimedFetchRequest(FetchRequest request) {
      this.request = request;
    }

    @Override
    public void close() {
      if (request == null) {
        return;
      }
      String msg =
          String.format("ClaimedFetchRequest for key %s was not satisfied.", request.ruleKey);
      IllegalStateException throwable = new IllegalStateException(msg);
      LOG.verbose(throwable, msg);
      getRequest().future.setException(throwable);
    }

    private FetchRequest getRequest() {
      return Preconditions.checkNotNull(request);
    }

    public void setResult(CacheResult result) {
      getRequest().future.set(result);
      request = null;
    }

    public void reschedule() {
      addFetchRequest(getRequest());
      request = null;
    }
  }

  @Nullable
  private ClaimedFetchRequest getFetchRequest() {
    FetchRequest request = pendingFetchRequests.poll();
    if (request == null) {
      return null;
    }
    return new ClaimedFetchRequest(request);
  }

  @SuppressWarnings("CheckReturnValue")
  private void addFetchRequest(FetchRequest fetchRequest) {
    pendingFetchRequests.add(fetchRequest);
    fetchExecutorService.submit(this::processFetch);
  }

  @Override
  public final ListenableFuture<CacheResult> fetchAsync(
      @Nullable BuildTarget target, RuleKey ruleKey, LazyPath output) {
    SettableFuture<CacheResult> future = SettableFuture.create();
    addFetchRequest(new FetchRequest(target, ruleKey, output, future));
    return future;
  }

  @Override
  public final ListenableFuture<ImmutableMap<RuleKey, CacheResult>> multiContainsAsync(
      ImmutableSet<RuleKey> ruleKeys) {
    return fetchExecutorService.submit(
        () -> {
          MultiContainsResult results = multiContainsImpl(ruleKeys);
          return results.getCacheResults();
        });
  }

  @Override
  public final ListenableFuture<Void> store(ArtifactInfo info, BorrowablePath output) {
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

    Path tmp;
    try {
      tmp = getPathForArtifact(output);
    } catch (IOException e) {
      LOG.error(e, "Failed to store artifact in temp file: " + output.getPath());
      return Futures.immediateFuture(null);
    }

    StoreEvents events = eventListener.storeScheduled(info, artifactSizeBytes);
    return storeExecutorService.submit(
        () -> {
          StoreEvents.StoreRequestEvents requestEvents = events.started();
          try {
            StoreResult result = storeImpl(info, tmp);
            requestEvents.finished(result);
            return null;
          } catch (IOException e) {
            String msg =
                String.format(
                    "store(%s): %s: %s",
                    info.getRuleKeys(), e.getClass().getName(), e.getMessage());
            requestEvents.failed(e, msg);
            throw new RuntimeException(e);
          }
        });
  }

  @Override
  public final ListenableFuture<Void> store(
      ImmutableList<Pair<ArtifactInfo, BorrowablePath>> artifacts) {
    if (!getCacheReadMode().isWritable()) {
      return Futures.immediateFuture(null);
    }

    ImmutableList.Builder<Pair<ArtifactInfo, Path>> matchedArtifactsBuilder =
        ImmutableList.builderWithExpectedSize(artifacts.size());
    ImmutableList.Builder<Long> artifactSizesInBytesBuilder =
        ImmutableList.builderWithExpectedSize(artifacts.size());

    for (int i = 0; i < artifacts.size(); i++) {
      BorrowablePath output = artifacts.get(i).getSecond();
      ArtifactInfo info = artifacts.get(i).getFirst();
      long artifactSizeBytes = getFileSize(output.getPath());
      if (artifactExceedsMaximumSize(artifactSizeBytes)) {
        LOG.info(
            "Artifact too big so not storing it in the %s cache. file=[%s] buildTarget=[%s]",
            name, output.getPath(), info.getBuildTarget());
        continue;
      }

      Path tmp;
      try {
        tmp = getPathForArtifact(output);
      } catch (IOException e) {
        LOG.error(e, "Failed to store artifact in temp file: " + output.getPath());
        continue;
      }

      matchedArtifactsBuilder.add(new Pair<>(info, tmp));
      artifactSizesInBytesBuilder.add(artifactSizeBytes);
    }

    ImmutableList<Pair<ArtifactInfo, Path>> matchedArtifacts = matchedArtifactsBuilder.build();

    if (matchedArtifacts.isEmpty()) {
      return Futures.immediateFuture(null);
    }

    ImmutableList<Long> artifactSizesInBytes = artifactSizesInBytesBuilder.build();

    ImmutableList.Builder<StoreEvents> eventsBuilder =
        ImmutableList.builderWithExpectedSize(artifactSizesInBytes.size());
    for (int i = 0; i < artifactSizesInBytes.size(); i++) {
      eventsBuilder.add(
          eventListener.storeScheduled(
              matchedArtifacts.get(i).getFirst(), artifactSizesInBytes.get(i)));
    }

    ImmutableList<StoreEvents> events = eventsBuilder.build();

    return storeExecutorService.submit(
        () -> {
          for (int i = 0; i < matchedArtifacts.size(); i++) {
            StoreEvents.StoreRequestEvents requestEvents = events.get(i).started();
            try {
              StoreResult result =
                  storeImpl(
                      matchedArtifacts.get(i).getFirst(), matchedArtifacts.get(i).getSecond());
              requestEvents.finished(result);
            } catch (IOException e) {
              String msg =
                  String.format(
                      "store(%s): %s: %s",
                      matchedArtifacts.get(i).getFirst().getRuleKeys(),
                      e.getClass().getName(),
                      e.getMessage());
              requestEvents.failed(e, msg);
              throw new RuntimeException(e);
            }
          }

          return null;
        });
  }

  @Override
  public final ListenableFuture<CacheDeleteResult> deleteAsync(List<RuleKey> ruleKeys) {
    if (!getCacheReadMode().isWritable()) {
      throw new IllegalArgumentException(
          "Cannot delete artifacts from cache, cache is not writable");
    }

    return storeExecutorService.submit(
        () -> {
          return deleteImpl(ruleKeys);
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

    void fetchScheduled(RuleKey ruleKey);

    FetchRequestEvents fetchStarted(BuildTarget target, RuleKey ruleKey);

    interface FetchRequestEvents {
      void finished(FetchResult result);

      void failed(IOException e, String errorMessage, CacheResult result);
    }

    MultiFetchRequestEvents multiFetchStarted(
        ImmutableList<BuildTarget> targets, ImmutableList<RuleKey> keys);

    interface MultiFetchRequestEvents extends Scope {
      void skipped(int keyIndex);

      void finished(int keyIndex, FetchResult thisResult);

      void failed(int keyIndex, IOException e, String msg, CacheResult result);
    }
  }

  public interface StoreEvents {
    StoreRequestEvents started();

    interface StoreRequestEvents {
      void finished(StoreResult result);

      void failed(IOException e, String errorMessage);
    }
  }

  protected static class FetchRequest {
    @Nullable private final BuildTarget target;
    private final RuleKey ruleKey;
    private final LazyPath output;
    private final SettableFuture<CacheResult> future;

    @VisibleForTesting
    protected FetchRequest(
        @Nullable BuildTarget target,
        RuleKey ruleKey,
        LazyPath output,
        SettableFuture<CacheResult> future) {
      this.target = target;
      this.ruleKey = ruleKey;
      this.output = output;
      this.future = future;
    }

    @Nullable
    public BuildTarget getBuildTarget() {
      return target;
    }

    public RuleKey getRuleKey() {
      return ruleKey;
    }

    public LazyPath getOutput() {
      return output;
    }
  }

  /** Return type used by the implementations of this abstract class. */
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

  /** Return type used by the implementations of this abstract class. */
  @BuckStyleTuple
  @Value.Immutable(builder = true)
  public interface AbstractMultiContainsResult {
    Optional<Long> getResponseSizeBytes();

    ImmutableMap<RuleKey, CacheResult> getCacheResults();
  }

  /** Return type used by the implementations of this abstract class. */
  @BuckStyleTuple
  @Value.Immutable(builder = true)
  public interface AbstractMultiFetchResult {
    /** At least one of the results must be non-skipped. */
    ImmutableList<FetchResult> getResults();
  }

  /** Return type used by the implementations of this abstract class. */
  @BuckStyleTuple
  @Value.Immutable(builder = true)
  public interface AbstractStoreResult {
    Optional<Long> getRequestSizeBytes();

    Optional<String> getArtifactContentHash();

    Optional<Boolean> getWasStoreSuccessful();
  }
}
