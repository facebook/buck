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

import com.facebook.buck.cli.CommandEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.network.BlockingHttpEndpoint;
import com.facebook.buck.util.network.RemoteLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles uploading events to a remote log service.
 */
public class RemoteLogUploaderEventListener implements BuckEventListener {

  private static final Logger LOG = Logger.get(RemoteLogUploaderEventListener.class);
  @VisibleForTesting
  static final int MAX_FAILURE_COUNT = 3;

  private final RemoteLogger remoteLogger;
  private final ObjectMapper mapper;
  private final BuildEnvironmentDescription buildEnvironmentDescription;
  private final Set<ListenableFuture<Void>> pendingUploads;
  private final AtomicInteger failureCount = new AtomicInteger(0);
  private int sentEventsCount = 0;

  public RemoteLogUploaderEventListener(
      ObjectMapper objectMapper,
      RemoteLogger remoteLogger,
      BuildEnvironmentDescription buildEnvironmentDescription) {
    this.mapper = objectMapper;
    this.buildEnvironmentDescription = buildEnvironmentDescription;
    this.remoteLogger = remoteLogger;
    this.pendingUploads = new HashSet<>();
  }

  @Subscribe
  public void buckEvent(final BuckEvent event) {
    putInSink(event.getBuildId(), event);
  }

  private void putInSink(BuildId buildId, Object object) {
    if (failureCount.get() > MAX_FAILURE_COUNT) {
      return;
    }
    sentEventsCount++;

    ObjectNode jsonNode = mapper.valueToTree(object);
    jsonNode.put("@class", object.getClass().getCanonicalName());
    jsonNode.put("buildId", buildId.toString());

    Optional<ListenableFuture<Void>> upload = remoteLogger.log(jsonNode.toString());
    if (upload.isPresent()) {
      registerPendingUpload(upload.get());
    }
  }

  private void registerPendingUpload(final ListenableFuture<Void> upload) {
    synchronized (pendingUploads) {
      pendingUploads.add(upload);
    }
    Futures.addCallback(
        upload,
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void result) {
            failureCount.set(0);
            onDone();
          }

          @Override
          public void onFailure(Throwable t) {
            onDone();
            LOG.info(t, "Failed uploading event to the remote log.");
            if (failureCount.incrementAndGet() == MAX_FAILURE_COUNT) {
              LOG.info("Maximum number of log upload failures reached, giving up.");
            }
          }

          private void onDone() {
            synchronized (pendingUploads) {
              pendingUploads.remove(upload);
            }
          }
        });
  }

  @Subscribe
  public void commandStarted(CommandEvent.Started started) {
    putInSink(
        started.getBuildId(),
        ImmutableMap.of(
            "type", "CommandStartedAux",
            "environment", buildEnvironmentDescription
        )
    );
  }

  @Subscribe
  public void commandFinished(CommandEvent.Finished finished) {
    putInSink(
        finished.getBuildId(),
        ImmutableMap.of(
            "type", "CommandFinishedAux",
            "environment", buildEnvironmentDescription,
            "eventsCount", sentEventsCount + 1
        )
    );
  }

  @Subscribe
  public void ruleStarted(BuildRuleEvent.Started started) {
    putInSink(
        started.getBuildId(),
        ImmutableMap.of(
            "type", "BuildRuleStartedAux",
            "buildRule", started.getBuildRule(),
            "deps", started.getBuildRule().getDeps()
        ));
  }

  @Override
  public synchronized void outputTrace(BuildId buildId) throws InterruptedException {
    ImmutableSet<ListenableFuture<Void>> uploads;
    ListenableFuture<Void> drain = remoteLogger.close();
    synchronized (pendingUploads) {
      pendingUploads.add(drain);
      uploads = ImmutableSet.copyOf(pendingUploads);
      pendingUploads.clear();
    }
    try {
      Futures.successfulAsList(uploads).get(
          BlockingHttpEndpoint.DEFAULT_COMMON_TIMEOUT_MS * 100,
          TimeUnit.MILLISECONDS);
    } catch (ExecutionException|TimeoutException  e) {
      LOG.info(e, "Failed uploading remaining log data to remote server");
    }
  }
}
