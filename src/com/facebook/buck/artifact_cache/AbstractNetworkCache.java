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

package com.facebook.buck.artifact_cache;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.BorrowablePath;
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.slb.HttpService;
import com.facebook.buck.slb.NoHealthyServersException;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import org.immutables.value.Value;

public abstract class AbstractNetworkCache implements ArtifactCache {

  private static final Logger LOG = Logger.get(AbstractNetworkCache.class);

  private final String name;
  private final ArtifactCacheMode mode;
  private final String repository;
  protected final String scheduleType;
  protected final HttpService fetchClient;
  protected final HttpService storeClient;
  private final CacheReadMode cacheReadMode;
  private final ProjectFilesystem projectFilesystem;
  private final BuckEventBus buckEventBus;
  private final ListeningExecutorService httpWriteExecutorService;
  private final String errorTextTemplate;
  private final Optional<Long> maxStoreSize;

  private final Set<String> seenErrors = Sets.newConcurrentHashSet();
  private boolean isNoHealthyServersSeen = false;

  public AbstractNetworkCache(NetworkCacheArgs args) {
    this.name = args.getCacheName();
    this.mode = args.getCacheMode();
    this.repository = args.getRepository();
    this.scheduleType = args.getScheduleType();
    this.fetchClient = args.getFetchClient();
    this.storeClient = args.getStoreClient();
    this.cacheReadMode = args.getCacheReadMode();
    this.projectFilesystem = args.getProjectFilesystem();
    this.buckEventBus = args.getBuckEventBus();
    this.httpWriteExecutorService = args.getHttpWriteExecutorService();
    this.errorTextTemplate = args.getErrorTextTemplate();
    this.maxStoreSize = args.getMaxStoreSizeBytes();
  }

  protected String getName() {
    return name;
  }

  protected ArtifactCacheMode getMode() {
    return mode;
  }

  protected ProjectFilesystem getProjectFilesystem() {
    return projectFilesystem;
  }

  protected String getRepository() {
    return repository;
  }

  protected abstract FetchResult fetchImpl(RuleKey ruleKey, LazyPath output) throws IOException;

  protected abstract StoreResult storeImpl(ArtifactInfo info, final Path file) throws IOException;

  private boolean isNoHealthyServersException(Throwable exception) {
    if (exception == null) {
      return false;
    } else if (exception instanceof NoHealthyServersException) {
      return true;
    } else {
      return isNoHealthyServersException(exception.getCause());
    }
  }

  @Override
  public ListenableFuture<CacheResult> fetchAsync(RuleKey ruleKey, LazyPath output) {
    return Futures.immediateFuture(fetch(ruleKey, output));
  }

  private CacheResult fetch(RuleKey ruleKey, LazyPath output) {
    HttpArtifactCacheEvent.Started startedEvent =
        HttpArtifactCacheEvent.newFetchStartedEvent(ruleKey);
    buckEventBus.post(startedEvent);
    HttpArtifactCacheEvent.Finished.Builder eventBuilder =
        HttpArtifactCacheEvent.newFinishedEventBuilder(startedEvent);
    eventBuilder.getFetchBuilder().setRequestedRuleKey(ruleKey);

    CacheResult result = null;
    try {
      FetchResult fetchResult = fetchImpl(ruleKey, output);
      eventBuilder.setTarget(fetchResult.getBuildTarget());
      eventBuilder
          .getFetchBuilder()
          .setAssociatedRuleKeys(fetchResult.getAssociatedRuleKeys().orElse(ImmutableSet.of()))
          .setArtifactContentHash(fetchResult.getArtifactContentHash())
          .setArtifactSizeBytes(fetchResult.getArtifactSizeBytes())
          .setFetchResult(fetchResult.getCacheResult())
          .setResponseSizeBytes(fetchResult.getResponseSizeBytes());
      result = fetchResult.getCacheResult();
      return result;
    } catch (IOException e) {
      String msg = String.format("%s: %s", e.getClass().getName(), e.getMessage());
      if (isNoHealthyServersException(e)) {
        if (!isNoHealthyServersSeen) {
          isNoHealthyServersSeen = true;
          buckEventBus.post(
              ConsoleEvent.warning(
                  "\n"
                      + "Failed to fetch %s over %s:\n"
                      + "Buck encountered a critical network failure.\n"
                      + "Please check your network connection and retry."
                      + "\n",
                  ruleKey, name));
        }
      } else {
        reportFailure(e, "fetch(%s): %s", ruleKey, msg);
      }
      result = CacheResult.error(name, mode, msg);
      return result;
    } finally {
      Preconditions.checkNotNull(result);
      HttpArtifactCacheEventFetchData.Builder fetchBuilder =
          eventBuilder.getFetchBuilder().setFetchResult(result);
      if (result.getType() == CacheResultType.ERROR && result.cacheError().isPresent()) {
        fetchBuilder.setErrorMessage(result.cacheError());
      }
      buckEventBus.post(eventBuilder.build());
    }
  }

  @Override
  public ListenableFuture<Void> store(final ArtifactInfo info, final BorrowablePath output) {
    if (!getCacheReadMode().isWritable()) {
      return Futures.immediateFuture(null);
    }

    final HttpArtifactCacheEvent.Scheduled scheduled =
        HttpArtifactCacheEvent.newStoreScheduledEvent(
            ArtifactCacheEvent.getTarget(info.getMetadata()), info.getRuleKeys());
    buckEventBus.post(scheduled);

    final Path tmp;
    try {
      tmp = getPathForArtifact(output);
    } catch (IOException e) {
      LOG.error(e, "Failed to store artifact in temp file: " + output.getPath().toString());
      return Futures.immediateFuture(null);
    }

    // HTTP Store operations are asynchronous.
    return httpWriteExecutorService.submit(
        () -> {
          HttpArtifactCacheEvent.Started startedEvent =
              HttpArtifactCacheEvent.newStoreStartedEvent(scheduled);
          buckEventBus.post(startedEvent);
          HttpArtifactCacheEvent.Finished.Builder finishedEventBuilder =
              HttpArtifactCacheEvent.newFinishedEventBuilder(startedEvent);
          finishedEventBuilder.getStoreBuilder().setRuleKeys(info.getRuleKeys());

          try {

            long artifactSizeBytes = projectFilesystem.getFileSize(tmp);
            finishedEventBuilder
                .getStoreBuilder()
                .setArtifactSizeBytes(artifactSizeBytes)
                .setRuleKeys(info.getRuleKeys());
            if (!isArtefactTooBigToBeStored(artifactSizeBytes, maxStoreSize)) {
              StoreResult result = storeImpl(info, tmp);
              finishedEventBuilder
                  .getStoreBuilder()
                  .setArtifactContentHash(result.getArtifactContentHash())
                  .setRequestSizeBytes(result.getRequestSizeBytes())
                  .setWasStoreSuccessful(result.getWasStoreSuccessful());
            } else {
              LOG.info(
                  "Artifact too big so not storing it in the distributed cache. "
                      + "file=[%s] buildTarget=[%s]",
                  tmp, info.getBuildTarget());
            }
            buckEventBus.post(finishedEventBuilder.build());

          } catch (IOException e) {
            reportFailure(
                e, "store(%s): %s: %s", info.getRuleKeys(), e.getClass().getName(), e.getMessage());
            finishedEventBuilder
                .getStoreBuilder()
                .setWasStoreSuccessful(false)
                .setErrorMessage(e.toString());
            buckEventBus.post(finishedEventBuilder.build());
          }
          try {
            projectFilesystem.deleteFileAtPathIfExists(tmp);
          } catch (IOException e) {
            LOG.warn(e, "Failed to delete file %s", tmp);
          }
        },
        /* result */ null);
  }

  @Override
  public CacheReadMode getCacheReadMode() {
    return cacheReadMode;
  }

  @Override
  public void close() {
    fetchClient.close();
    storeClient.close();
  }

  /// depending on if we can borrow the output or not, we will either use output directly or
  /// hold it temporary in hidden place
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

  private void reportFailure(Exception exception, String format, Object... args) {
    LOG.warn(exception, format, args);
    reportFailureToEventBus(format, args);
  }

  protected void reportFailure(String format, Object... args) {
    LOG.warn(format, args);
    reportFailureToEventBus(format, args);
  }

  private void reportFailureToEventBus(String format, Object... args) {
    if (seenErrors.add(format)) {
      buckEventBus.post(
          ConsoleEvent.warning(
              errorTextTemplate
                  .replaceAll("\\{cache_name}", Matcher.quoteReplacement(name))
                  .replaceAll("\\\\t", Matcher.quoteReplacement("\t"))
                  .replaceAll("\\\\n", Matcher.quoteReplacement("\n"))
                  .replaceAll(
                      "\\{error_message}", Matcher.quoteReplacement(String.format(format, args)))));
    }
  }

  private static boolean isArtefactTooBigToBeStored(
      long artifactSizeBytes, Optional<Long> maxStoreSize) {
    return maxStoreSize.isPresent() && artifactSizeBytes > maxStoreSize.get();
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
