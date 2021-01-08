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

package com.facebook.buck.event.isolated;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downward.model.ChromeTraceEvent;
import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downwardapi.protocol.DownwardProtocol;
import com.facebook.buck.downwardapi.protocol.DownwardProtocolType;
import com.facebook.buck.downwardapi.utils.DownwardApiUtils;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.ExternalEvent;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.StepEvent;
import com.facebook.buck.util.Threads;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.Duration;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Event bus that writes directly to its associated output stream. Only handles events associated
 * with Downward API.
 */
public class DefaultIsolatedEventBus implements IsolatedEventBus {

  private static final Logger LOG = Logger.get(DefaultIsolatedEventBus.class);
  private static final String THREAD_NAME = "DefaultIsolatedEventBus";
  public static final int DEFAULT_SHUTDOWN_TIMEOUT_MS = 15000;

  private static final DownwardProtocol DOWNWARD_PROTOCOL =
      DownwardProtocolType.BINARY.getDownwardProtocol();

  private final BuildId buildId;
  private final OutputStream outputStream;
  private final Clock clock;
  private final ListeningExecutorService executorService;
  private final int shutdownTimeoutMillis;
  private final long startExecutionEpochMillis;

  public DefaultIsolatedEventBus(
      BuildId buildId, OutputStream outputStream, long startExecutionEpochMillis) {
    this(
        buildId,
        outputStream,
        new DefaultClock(true),
        MoreExecutors.listeningDecorator(MostExecutors.newSingleThreadExecutor(THREAD_NAME)),
        DEFAULT_SHUTDOWN_TIMEOUT_MS,
        startExecutionEpochMillis);
  }

  public DefaultIsolatedEventBus(
      BuildId buildId,
      OutputStream outputStream,
      Clock clock,
      ListeningExecutorService executorService,
      int shutdownTimeoutMillis,
      long startExecutionEpochMillis) {
    this.buildId = buildId;
    this.outputStream = outputStream;
    this.clock = clock;
    this.executorService = executorService;
    this.shutdownTimeoutMillis = shutdownTimeoutMillis;
    this.startExecutionEpochMillis = startExecutionEpochMillis;
  }

  @Override
  public BuildId getBuildId() {
    return buildId;
  }

  @Override
  public void post(ConsoleEvent event) {
    post(event, Thread.currentThread().getId());
  }

  @Override
  public void post(ConsoleEvent event, long threadId) {
    timestamp(event, threadId);
    writeConsoleEvent(event);
  }

  @Override
  public void post(ExternalEvent event) {
    post(event, Thread.currentThread().getId());
  }

  @Override
  public void post(ExternalEvent event, long threadId) {
    timestamp(event, threadId);
    writeExternalEvent(event);
  }

  @Override
  public void post(StepEvent event) {
    post(event, Thread.currentThread().getId());
  }

  @Override
  public void post(StepEvent event, long threadId) {
    timestamp(event, threadId);
    writeStepEvent(event);
  }

  @Override
  public void post(StepEvent event, Instant atTime, long threadId) {
    long millis = atTime.toEpochMilli();
    long nano = clock.nanoTime();
    long threadUserNanoTime = clock.threadUserNanoTime(threadId);
    event.configure(millis, nano, threadUserNanoTime, threadId, buildId);
    writeStepEvent(event);
  }

  @Override
  public void post(SimplePerfEvent event) {
    post(event, Thread.currentThread().getId());
  }

  @Override
  public void post(SimplePerfEvent event, Instant atTime) {
    post(event, atTime, Thread.currentThread().getId());
  }

  @Override
  public void post(SimplePerfEvent event, long threadId) {
    timestamp(event, threadId);
    writeChromeTraceEvent(event);
  }

  @Override
  public void post(SimplePerfEvent event, Instant atTime, long threadId) {
    long millis = atTime.toEpochMilli();
    long nano = clock.nanoTime();
    long threadUserNanoTime = clock.threadUserNanoTime(threadId);
    event.configure(millis, nano, threadUserNanoTime, threadId, buildId);
    writeChromeTraceEvent(event);
  }

  @Override
  public void postWithoutConfiguring(SimplePerfEvent event) {
    Preconditions.checkState(event.isConfigured(), "Event must be configured");
    writeChromeTraceEvent(event);
  }

  @Override
  public void timestamp(ConsoleEvent event) {
    timestamp(event, Thread.currentThread().getId());
  }

  @Override
  public void timestamp(StepEvent event) {
    timestamp(event, Thread.currentThread().getId());
  }

  @Override
  public void timestamp(SimplePerfEvent event) {
    timestamp(event, Thread.currentThread().getId());
  }

  @Override
  public void timestamp(SimplePerfEvent event, long threadId) {
    timestamp((BuckEvent) event, Thread.currentThread().getId());
  }

  @Override
  public void close() throws IOException {
    executorService.shutdown();
    try {
      executorService.awaitTermination(shutdownTimeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Threads.interruptCurrentThread();
    }
  }

  private void timestamp(BuckEvent event, long threadId) {
    event.configure(
        clock.currentTimeMillis(),
        clock.nanoTime(),
        clock.threadUserNanoTime(threadId),
        threadId,
        buildId);
  }

  private void writeExternalEvent(ExternalEvent event) {
    EventTypeMessage eventTypeMessage =
        EventTypeMessage.newBuilder()
            .setEventType(EventTypeMessage.EventType.EXTERNAL_EVENT)
            .build();
    com.facebook.buck.downward.model.ExternalEvent externalEvent =
        com.facebook.buck.downward.model.ExternalEvent.newBuilder()
            .putAllData(event.getData())
            .build();
    writeToNamedPipe(eventTypeMessage, externalEvent);
  }

  private void writeConsoleEvent(ConsoleEvent event) {
    EventTypeMessage eventTypeMessage =
        EventTypeMessage.newBuilder()
            .setEventType(EventTypeMessage.EventType.CONSOLE_EVENT)
            .build();
    com.facebook.buck.downward.model.ConsoleEvent consoleEvent =
        com.facebook.buck.downward.model.ConsoleEvent.newBuilder()
            .setLogLevel(DownwardApiUtils.convertLogLevel(event.getLevel()))
            .setMessage(event.getMessage())
            .build();
    writeToNamedPipe(eventTypeMessage, consoleEvent);
  }

  private void writeStepEvent(StepEvent event) {
    EventTypeMessage eventTypeMessage =
        EventTypeMessage.newBuilder().setEventType(EventTypeMessage.EventType.STEP_EVENT).build();
    com.facebook.buck.downward.model.StepEvent stepEvent =
        com.facebook.buck.downward.model.StepEvent.newBuilder()
            .setEventId(event.getUuid().hashCode())
            .setStepStatus(getStepStatus(event))
            .setStepType(event.getShortStepName())
            .setDescription(event.getDescription())
            .setDuration(getDuration(event))
            .build();
    writeToNamedPipe(eventTypeMessage, stepEvent);
  }

  private void writeChromeTraceEvent(SimplePerfEvent event) {
    EventTypeMessage eventTypeMessage =
        EventTypeMessage.newBuilder()
            .setEventType(EventTypeMessage.EventType.CHROME_TRACE_EVENT)
            .build();
    ChromeTraceEvent chromeTraceEvent =
        ChromeTraceEvent.newBuilder()
            .setEventId(event.getEventKey().hashCode())
            .setCategory(event.getCategory())
            .setTitle(event.getTitle().getValue())
            .setStatus(convertEventType(event))
            .putAllData(Maps.transformValues(event.getEventInfo(), Object::toString))
            .setDuration(getDuration(event))
            .build();
    writeToNamedPipe(eventTypeMessage, chromeTraceEvent);
  }

  private Duration getDuration(BuckEvent event) {
    return DurationUtils.millisToDuration(event.getTimestampMillis() - startExecutionEpochMillis);
  }

  private ChromeTraceEvent.ChromeTraceEventStatus convertEventType(SimplePerfEvent event) {
    switch (event.getEventType()) {
      case STARTED:
        return ChromeTraceEvent.ChromeTraceEventStatus.BEGIN;
      case FINISHED:
        return ChromeTraceEvent.ChromeTraceEventStatus.END;
      case UPDATED:
      default:
        return ChromeTraceEvent.ChromeTraceEventStatus.UNKNOWN;
    }
  }

  private com.facebook.buck.downward.model.StepEvent.StepStatus getStepStatus(StepEvent event) {
    if (event instanceof StepEvent.Started) {
      return com.facebook.buck.downward.model.StepEvent.StepStatus.STARTED;
    }
    if (event instanceof StepEvent.Finished) {
      return com.facebook.buck.downward.model.StepEvent.StepStatus.FINISHED;
    }
    return com.facebook.buck.downward.model.StepEvent.StepStatus.UNKNOWN;
  }

  private void writeToNamedPipe(EventTypeMessage eventType, AbstractMessage payload) {
    try {
      DOWNWARD_PROTOCOL.write(eventType, payload, outputStream);
    } catch (IOException e) {
      LOG.error(e, "Failed to write buck event %s of type", payload, eventType.getEventType());
    }
  }
}
