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
import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downwardapi.protocol.DownwardProtocolType;
import com.facebook.buck.downwardapi.utils.DownwardApiUtils;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.event.chrome_trace.ChromeTraceEvent;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.util.Threads;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.AbstractMessage;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Event bus that writes directly to its associated output stream. Only handles events associated
 * with Downward API.
 */
public class DefaultIsolatedEventBus implements IsolatedEventBus {

  private static final Logger LOG = Logger.get(DefaultIsolatedEventBus.class.getSimpleName());
  private static final String THREAD_NAME = "DefaultIsolatedEventBus";
  public static final int DEFAULT_SHUTDOWN_TIMEOUT_MS = 15000;

  private final BuildId buildId;
  private final OutputStream outputStream;
  private final Clock clock;
  private final ListeningExecutorService executorService;
  private final int shutdownTimeoutMillis;

  public DefaultIsolatedEventBus(BuildId buildId, OutputStream outputStream) {
    this(
        buildId,
        outputStream,
        new DefaultClock(true),
        MoreExecutors.listeningDecorator(MostExecutors.newSingleThreadExecutor(THREAD_NAME)),
        DEFAULT_SHUTDOWN_TIMEOUT_MS);
  }

  public DefaultIsolatedEventBus(
      BuildId buildId,
      OutputStream outputStream,
      Clock clock,
      ListeningExecutorService executorService,
      int shutdownTimeoutMillis) {
    this.buildId = buildId;
    this.outputStream = outputStream;
    this.clock = clock;
    this.executorService = executorService;
    this.shutdownTimeoutMillis = shutdownTimeoutMillis;
  }

  @Override
  public BuildId getBuildId() {
    return buildId;
  }

  @Override
  public void post(BuckEvent event) {
    post(event, Thread.currentThread().getId());
  }

  @Override
  public void post(BuckEvent event, Instant atTime, long threadId) {
    checkBuckEventType(event);
    long millis = atTime.toEpochMilli();
    long nano = TimeUnit.SECONDS.toNanos(atTime.getEpochSecond()) + atTime.getNano();
    long threadUserNanoTime = clock.threadUserNanoTime(threadId);
    event.configure(millis, nano, threadUserNanoTime, threadId, buildId);
    dispatch(event);
  }

  @Override
  public void post(BuckEvent event, Instant atTime) {
    post(event, atTime, Thread.currentThread().getId());
  }

  @Override
  public void post(BuckEvent event, long threadId) {
    checkBuckEventType(event);
    timestamp(event, threadId);
    dispatch(event);
  }

  @Override
  public void postWithoutConfiguring(BuckEvent event) {
    Preconditions.checkState(event.isConfigured());
    dispatch(event);
  }

  @Override
  public void timestamp(BuckEvent event) {
    timestamp(event, Thread.currentThread().getId());
  }

  @Override
  public void timestamp(BuckEvent event, long threadId) {
    event.configure(
        clock.currentTimeMillis(),
        clock.nanoTime(),
        clock.threadUserNanoTime(threadId),
        threadId,
        buildId);
  }

  private void dispatch(BuckEvent event) {
    executorService.submit(() -> writeBuckEvent(event), null);
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

  private void writeBuckEvent(BuckEvent event) {
    try {
      // TODO(irenewchen): Support other downward api events
      if (event instanceof ConsoleEvent) {
        writeConsoleEvent((ConsoleEvent) event);
      } else if (event instanceof StepEvent) {
        writeStepEvent((StepEvent) event);
      }
    } catch (IOException e) {
      LOG.error(e, "Failed to write buck event %s", event.getEventName());
    }
  }

  private void writeConsoleEvent(ConsoleEvent event) throws IOException {
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

  private void writeStepEvent(StepEvent event) throws IOException {
    EventTypeMessage eventTypeMessage =
        EventTypeMessage.newBuilder().setEventType(EventTypeMessage.EventType.STEP_EVENT).build();
    com.facebook.buck.downward.model.StepEvent stepEvent =
        com.facebook.buck.downward.model.StepEvent.newBuilder()
            .setEventId(event.getUuid().hashCode())
            .setStepStatus(getStepStatus(event))
            .setStepType(event.getShortStepName())
            .setDescription(event.getDescription())
            .build();
    writeToNamedPipe(eventTypeMessage, stepEvent);
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

  private void writeToNamedPipe(EventTypeMessage eventType, AbstractMessage payload)
      throws IOException {
    DownwardProtocolType.BINARY.getDownwardProtocol().write(eventType, payload, outputStream);
  }

  private static void checkBuckEventType(BuckEvent event) {
    Preconditions.checkArgument(
        event instanceof ConsoleEvent
            || event instanceof StepEvent
            || event instanceof ChromeTraceEvent,
        "Unexpected event type: %s",
        event.getClass().getName());
  }
}
