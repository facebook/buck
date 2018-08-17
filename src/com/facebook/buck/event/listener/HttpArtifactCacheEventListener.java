/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.event.listener;

import com.facebook.buck.artifact_cache.ArtifactCacheEvent;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEventFetchData;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEventStoreData;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.support.bgtasks.BackgroundTask;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager;
import com.facebook.buck.support.bgtasks.ImmutableBackgroundTask;
import com.facebook.buck.support.bgtasks.TaskAction;
import com.facebook.buck.util.network.BatchingLogger;
import com.facebook.buck.util.network.HiveRowFormatter;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.immutables.value.Value;

/** Listens to HttpArtifactCacheEvents and logs stats data in Hive row format. */
public class HttpArtifactCacheEventListener implements BuckEventListener {
  private static final Logger LOG = Logger.get(HttpArtifactCacheEventListener.class);

  private static final long TEAR_DOWN_SECONDS = TimeUnit.SECONDS.toSeconds(15);
  private static final String NOT_SET_STRING = "NOT_SET";
  private static final long NOT_SET_LONG = -1L;

  private final BatchingLogger storeRequestLogger;
  private final BatchingLogger fetchRequestLogger;

  private final BackgroundTaskManager bgTaskManager;

  public HttpArtifactCacheEventListener(
      BatchingLogger storeRequestLogger,
      BatchingLogger fetchRequestLogger,
      BackgroundTaskManager bgTaskManager) {
    this.storeRequestLogger = storeRequestLogger;
    this.fetchRequestLogger = fetchRequestLogger;
    this.bgTaskManager = bgTaskManager;
  }

  @Subscribe
  public void onHttpArtifactCacheEvent(HttpArtifactCacheEvent.Finished event) {
    String buildIdString = event.getBuildId().toString();

    if (event.getOperation() == ArtifactCacheEvent.Operation.FETCH) {
      HttpArtifactCacheEventFetchData data = event.getFetchData();
      String hiveRow =
          HiveRowFormatter.newFormatter()
              .appendString(buildIdString)
              .appendString(event.getRequestDurationMillis())
              .appendString(data.getRequestedRuleKey())
              .appendString(
                  data.getFetchResult().isPresent() ? data.getFetchResult().get() : NOT_SET_STRING)
              .appendString(data.getResponseSizeBytes().orElse(NOT_SET_LONG))
              .appendString(data.getArtifactContentHash().orElse(NOT_SET_STRING))
              .appendString(data.getArtifactSizeBytes().orElse(NOT_SET_LONG))
              .appendString(data.getErrorMessage().orElse(NOT_SET_STRING))
              .appendString(event.getTarget().orElse(NOT_SET_STRING))
              .build();
      fetchRequestLogger.log(hiveRow);
    } else { // ArtifactCacheEvent.Operation.STORE
      HttpArtifactCacheEventStoreData data = event.getStoreData();
      String hiveRow =
          HiveRowFormatter.newFormatter()
              .appendString(buildIdString)
              .appendString(event.getRequestDurationMillis())
              .appendStringIterable(data.getRuleKeys())
              .appendString(data.getRequestSizeBytes().orElse(NOT_SET_LONG))
              .appendString(data.getArtifactContentHash().orElse(NOT_SET_STRING))
              .appendString(data.getArtifactSizeBytes().orElse(NOT_SET_LONG))
              .appendString(data.getErrorMessage().orElse(NOT_SET_STRING))
              .appendString(
                  data.wasStoreSuccessful().isPresent()
                      ? data.wasStoreSuccessful().get()
                      : NOT_SET_STRING)
              .appendString(event.getTarget().orElse(NOT_SET_STRING))
              .build();
      storeRequestLogger.log(hiveRow);
    }
  }

  @Override
  public void close() {
    HttpArtifactCacheEventListenerCloseArgs args =
        HttpArtifactCacheEventListenerCloseArgs.of(fetchRequestLogger, storeRequestLogger);
    BackgroundTask<HttpArtifactCacheEventListenerCloseArgs> task =
        ImmutableBackgroundTask.<HttpArtifactCacheEventListenerCloseArgs>builder()
            .setAction(new HttpArtifactCacheEventListenerCloseAction())
            .setActionArgs(args)
            .setName("HttpArtifactCacheEventListener_close")
            .build();
    bgTaskManager.schedule(task);
  }

  /**
   * {@link TaskAction} implementation for {@link HttpArtifactCacheEventListener}. Flushes fetch and
   * store loggers.
   */
  static class HttpArtifactCacheEventListenerCloseAction
      implements TaskAction<HttpArtifactCacheEventListenerCloseArgs> {
    @Override
    public void run(HttpArtifactCacheEventListenerCloseArgs args) {
      List<ListenableFuture<Void>> futures = new ArrayList<>();
      futures.add(args.getFetchRequestLogger().forceFlush());
      futures.add(args.getStoreRequestLogger().forceFlush());
      try {
        Futures.allAsList(futures).get(TEAR_DOWN_SECONDS, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        LOG.warn(e, "Flushing of logs was interrupted.");
      } catch (ExecutionException e) {
        LOG.warn(e, "Execution of log flushing failed.");
      } catch (TimeoutException e) {
        LOG.warn(e, "Flushing the logs timed out.");
      }
    }
  }

  /** Arguments to {@link HttpArtifactCacheEventListenerCloseAction}. */
  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractHttpArtifactCacheEventListenerCloseArgs {
    @Value.Parameter
    public abstract BatchingLogger getFetchRequestLogger();

    @Value.Parameter
    public abstract BatchingLogger getStoreRequestLogger();
  }
}
