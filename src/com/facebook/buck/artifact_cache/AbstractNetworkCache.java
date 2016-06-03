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
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.regex.Matcher;

public abstract class AbstractNetworkCache implements ArtifactCache {

  private static final Logger LOG = Logger.get(AbstractNetworkCache.class);

  protected final String name;
  protected final HttpService fetchClient;
  protected final HttpService storeClient;
  private final boolean doStore;
  protected final ProjectFilesystem projectFilesystem;
  private final BuckEventBus buckEventBus;
  private final ListeningExecutorService httpWriteExecutorService;
  private final String errorTextTemplate;
  private final Optional<Long> maxStoreSize;

  private final Set<String> seenErrors = Sets.newConcurrentHashSet();

  public AbstractNetworkCache(NetworkCacheArgs args) {
    this.name = args.getCacheName();
    this.fetchClient = args.getFetchClient();
    this.storeClient = args.getStoreClient();
    this.doStore = args.getDoStore();
    this.projectFilesystem = args.getProjectFilesystem();
    this.buckEventBus = args.getBuckEventBus();
    this.httpWriteExecutorService = args.getHttpWriteExecutorService();
    this.errorTextTemplate = args.getErrorTextTemplate();
    this.maxStoreSize = args.getMaxStoreSizeBytes();
  }

  protected abstract CacheResult fetchImpl(
      RuleKey ruleKey,
      LazyPath output,
      final HttpArtifactCacheEvent.Finished.Builder eventBuilder) throws IOException;

  protected abstract void storeImpl(ArtifactInfo info,
      final Path file,
      final HttpArtifactCacheEvent.Finished.Builder eventBuilder)
      throws IOException;

  @Override
  public CacheResult fetch(RuleKey ruleKey, LazyPath output) {
    HttpArtifactCacheEvent.Started startedEvent = HttpArtifactCacheEvent.newFetchStartedEvent(
        ImmutableSet.of(ruleKey));
    buckEventBus.post(startedEvent);
    HttpArtifactCacheEvent.Finished.Builder eventBuilder =
        HttpArtifactCacheEvent.newFinishedEventBuilder(startedEvent)
        .setRuleKeys(Lists.newArrayList(ruleKey));

    try {
      CacheResult result = fetchImpl(ruleKey, output, eventBuilder);
      buckEventBus.post(
          eventBuilder
              .setFetchResult(result)
              .build());
      return result;
    } catch (IOException e) {
      String msg = String.format("%s: %s", e.getClass().getName(), e.getMessage());
      reportFailure(e, "fetch(%s): %s", ruleKey, msg);
      CacheResult cacheResult = CacheResult.error(name, msg);
      buckEventBus.post(eventBuilder
          .setFetchResult(cacheResult)
          .setErrorMessage(msg)
          .build());
      return cacheResult;
    }
  }

  @Override
  public ListenableFuture<Void> store(
      final ArtifactInfo info,
      final BorrowablePath output) {
    if (!isStoreSupported()) {
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
        new Runnable() {
          @Override
          public void run() {
            HttpArtifactCacheEvent.Started startedEvent =
                HttpArtifactCacheEvent.newStoreStartedEvent(scheduled);
            buckEventBus.post(startedEvent);
            HttpArtifactCacheEvent.Finished.Builder finishedEventBuilder =
                HttpArtifactCacheEvent.newFinishedEventBuilder(startedEvent)
                    .setRuleKeys(info.getRuleKeys());

            try {
              if (!isArtefactTooBigToBeStored(tmp, maxStoreSize, projectFilesystem)) {
                storeImpl(info, tmp, finishedEventBuilder);
              } else {
                LOG.info("Artifact too big so not storing it in the distributed cache. " +
                    "file=[%s] buildTarget=[%s]",
                    tmp,
                    info.getBuildTarget());
              }
              buckEventBus.post(finishedEventBuilder.build());

            } catch (IOException e) {
              reportFailure(
                  e,
                  "store(%s): %s: %s",
                  info.getRuleKeys(),
                  e.getClass().getName(),
                  e.getMessage());

              buckEventBus.post(
                  finishedEventBuilder
                      .setWasUploadSuccessful(false)
                      .setErrorMessage(e.toString())
                      .build());
            }
            try {
              projectFilesystem.deleteFileAtPathIfExists(tmp);
            } catch (IOException e) {
              LOG.warn(e, "Failed to delete file %s", tmp);
            }
          }
        },
        /* result */ null
    );
  }

  @Override
  public boolean isStoreSupported() {
    return doStore;
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
      Optional<Path> tmpDirPath = projectFilesystem.getPathRelativeToProjectRoot(Paths.get("tmp"));
      projectFilesystem.mkdirs(tmpDirPath.get());
      tmp = projectFilesystem.createTempFile(tmpDirPath.get(), "artifact", ".tmp");
      projectFilesystem.copyFile(output.getPath(), tmp);
    }
    return tmp;
  }

  protected void reportFailure(Exception exception, String format, Object... args) {
    LOG.warn(exception, format, args);
    reportFailureToEvenBus(format, args);
  }

  protected void reportFailure(String format, Object... args) {
    LOG.warn(format, args);
    reportFailureToEvenBus(format, args);
  }

  private void reportFailureToEvenBus(String format, Object... args) {
    if (seenErrors.add(format)) {
      buckEventBus.post(ConsoleEvent.warning(
          errorTextTemplate
              .replaceAll("\\{cache_name}",
                  Matcher.quoteReplacement(name))
              .replaceAll("\\{error_message}",
                  Matcher.quoteReplacement(String.format(format, args)))));
    }
  }

  private static boolean isArtefactTooBigToBeStored(
      Path file,
      Optional<Long> maxStoreSize,
      ProjectFilesystem projectFilesystem) throws IOException {
    return maxStoreSize.isPresent() &&
        projectFilesystem.getFileSize(file) > maxStoreSize.get();
  }
}
