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

import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downward.model.ChromeTraceEvent;
import com.facebook.buck.downward.model.ChromeTraceEvent.ChromeTraceEventStatus;
import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downward.model.EventTypeMessage.EventType;
import com.facebook.buck.downward.model.StepEvent.StepStatus;
import com.facebook.buck.downwardapi.protocol.DownwardProtocol;
import com.facebook.buck.downwardapi.utils.DownwardApiUtils;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.ExternalEvent;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.StepEvent;
import com.facebook.buck.event.utils.EventBusUtils;
import com.facebook.buck.util.timing.Clock;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.Duration;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;

/**
 * Event bus that writes directly to its associated output stream. Only handles events associated
 * with Downward API.
 */
public class DefaultIsolatedEventBus implements IsolatedEventBus {

  private static final Logger LOG = Logger.get(DefaultIsolatedEventBus.class);

  private final BuildId buildId;
  private final OutputStream outputStream;
  private final Clock clock;
  private final long startExecutionEpochMillis;
  private final DownwardProtocol downwardProtocol;
  private final Phaser phaser;
  private final boolean measureWaitedTime;
  private final ActionId defaultActionId;

  public DefaultIsolatedEventBus(
      BuildId buildId,
      OutputStream outputStream,
      Clock clock,
      DownwardProtocol downwardProtocol,
      ActionId defaultActionId) {
    this(
        buildId, outputStream, clock, clock.currentTimeMillis(), downwardProtocol, defaultActionId);
  }

  @VisibleForTesting
  public DefaultIsolatedEventBus(
      BuildId buildId,
      OutputStream outputStream,
      Clock clock,
      long startExecutionEpochMillis,
      DownwardProtocol downwardProtocol,
      ActionId defaultActionId) {
    this(
        buildId,
        outputStream,
        clock,
        startExecutionEpochMillis,
        downwardProtocol,
        false,
        defaultActionId);
  }

  @VisibleForTesting
  DefaultIsolatedEventBus(
      BuildId buildId,
      OutputStream outputStream,
      Clock clock,
      long startExecutionEpochMillis,
      DownwardProtocol downwardProtocol,
      boolean measureWaitedTime,
      ActionId defaultActionId) {
    this.buildId = buildId;
    this.outputStream = outputStream;
    this.clock = clock;
    this.startExecutionEpochMillis = startExecutionEpochMillis;
    this.downwardProtocol = downwardProtocol;
    this.defaultActionId = defaultActionId;
    this.phaser = new Phaser();
    this.measureWaitedTime = measureWaitedTime;
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
    configureWithTimestamp(event, threadId);
    writeConsoleEvent(event);
  }

  @Override
  public void post(ExternalEvent event) {
    post(event, Thread.currentThread().getId());
  }

  @Override
  public void post(ExternalEvent event, long threadId) {
    configureWithTimestamp(event, threadId);
    writeExternalEvent(event);
  }

  @Override
  public void post(StepEvent event, ActionId actionId) {
    post(event, actionId, Thread.currentThread().getId());
  }

  @Override
  public void post(StepEvent event, ActionId actionId, long threadId) {
    configureWithTimestamp(event, threadId);
    writeStepEvent(event, actionId);
  }

  @Override
  public void post(StepEvent event, ActionId actionId, Instant atTime, long threadId) {
    EventBusUtils.configureEvent(event, atTime, threadId, clock, buildId);
    writeStepEvent(event, actionId);
  }

  @Override
  public void post(SimplePerfEvent event, ActionId actionId) {
    post(event, actionId, Thread.currentThread().getId());
  }

  @Override
  public void post(SimplePerfEvent event, ActionId actionId, Instant atTime) {
    post(event, actionId, atTime, Thread.currentThread().getId());
  }

  @Override
  public void post(SimplePerfEvent event, ActionId actionId, long threadId) {
    timestamp(event, threadId);
    writeChromeTraceEvent(event, actionId);
  }

  @Override
  public void post(SimplePerfEvent event, ActionId actionId, Instant atTime, long threadId) {
    EventBusUtils.configureEvent(event, atTime, threadId, clock, buildId);
    writeChromeTraceEvent(event, actionId);
  }

  @Override
  public void postWithoutConfiguring(SimplePerfEvent event, ActionId actionId) {
    Preconditions.checkState(event.isConfigured(), "Event must be configured");
    writeChromeTraceEvent(event, actionId);
  }

  @Override
  public void timestamp(ConsoleEvent event) {
    configureWithTimestamp(event, Thread.currentThread().getId());
  }

  @Override
  public void timestamp(StepEvent event) {
    configureWithTimestamp(event, Thread.currentThread().getId());
  }

  @Override
  public void timestamp(SimplePerfEvent event) {
    timestamp(event, Thread.currentThread().getId());
  }

  @Override
  public void timestamp(SimplePerfEvent event, long threadId) {
    configureWithTimestamp(event, threadId);
  }

  @Override
  public void close() {}

  private void configureWithTimestamp(BuckEvent event, long threadId) {
    event.configure(
        clock.currentTimeMillis(),
        clock.nanoTime(),
        clock.threadUserNanoTime(threadId),
        threadId,
        buildId);
  }

  private void writeExternalEvent(ExternalEvent event) {
    EventTypeMessage eventTypeMessage =
        EventTypeMessage.newBuilder().setEventType(EventType.EXTERNAL_EVENT).build();
    com.facebook.buck.downward.model.ExternalEvent externalEvent =
        com.facebook.buck.downward.model.ExternalEvent.newBuilder()
            .putAllData(event.getData())
            .build();
    writeToNamedPipe(eventTypeMessage, externalEvent);
  }

  private void writeConsoleEvent(ConsoleEvent event) {
    EventTypeMessage eventTypeMessage =
        EventTypeMessage.newBuilder().setEventType(EventType.CONSOLE_EVENT).build();
    com.facebook.buck.downward.model.ConsoleEvent consoleEvent =
        com.facebook.buck.downward.model.ConsoleEvent.newBuilder()
            .setLogLevel(DownwardApiUtils.convertLogLevel(event.getLevel()))
            .setMessage(event.getMessage())
            .build();
    writeToNamedPipe(eventTypeMessage, consoleEvent);
  }

  private void writeStepEvent(StepEvent event, ActionId actionId) {
    EventTypeMessage eventTypeMessage =
        EventTypeMessage.newBuilder().setEventType(EventType.STEP_EVENT).build();
    com.facebook.buck.downward.model.StepEvent stepEvent =
        com.facebook.buck.downward.model.StepEvent.newBuilder()
            .setEventId(getEventId(event))
            .setStepStatus(getStepStatus(event))
            .setStepType(event.getShortStepName())
            .setDescription(event.getDescription())
            .setDuration(getDuration(event))
            .setActionId(actionId != null ? actionId.getValue() : defaultActionId.getValue())
            .build();
    writeToNamedPipe(eventTypeMessage, stepEvent);
  }

  private int getEventId(BuckEvent event) {
    return (int) event.getEventKey().getValue();
  }

  private void writeChromeTraceEvent(SimplePerfEvent event, ActionId actionId) {
    EventTypeMessage eventTypeMessage =
        EventTypeMessage.newBuilder().setEventType(EventType.CHROME_TRACE_EVENT).build();
    ChromeTraceEventStatus eventStatus = convertEventType(event.getEventType());
    ChromeTraceEvent chromeTraceEvent =
        ChromeTraceEvent.newBuilder()
            .setEventId(getEventId(event))
            .setCategory(event.getCategory())
            .setTitle(event.getTitle().getValue())
            .setStatus(eventStatus)
            .putAllData(Maps.transformValues(event.getEventInfo(), Object::toString))
            .setDuration(getDuration(event))
            .setActionId(actionId != null ? actionId.getValue() : defaultActionId.getValue())
            .build();
    writeToNamedPipe(eventTypeMessage, chromeTraceEvent);
  }

  private Duration getDuration(BuckEvent event) {
    return EventBusUtils.millisToDuration(event.getTimestampMillis() - startExecutionEpochMillis);
  }

  private ChromeTraceEventStatus convertEventType(SimplePerfEvent.Type eventType) {
    switch (eventType) {
      case STARTED:
        return ChromeTraceEventStatus.BEGIN;

      case FINISHED:
        return ChromeTraceEventStatus.END;

      case UPDATED:
      default:
        return ChromeTraceEventStatus.UNKNOWN;
    }
  }

  private StepStatus getStepStatus(StepEvent event) {
    if (event instanceof StepEvent.Started) {
      return StepStatus.STARTED;
    }

    if (event instanceof StepEvent.Finished) {
      return StepStatus.FINISHED;
    }

    return StepStatus.UNKNOWN;
  }

  private void writeToNamedPipe(EventTypeMessage eventType, AbstractMessage payload) {
    phaser.register();
    try {
      writeIntoStream(eventType, payload);
    } catch (IOException e) {
      LOG.error(e, "Failed to write buck event %s of type", payload, eventType.getEventType());
    } finally {
      phaser.arriveAndDeregister();
    }
  }

  @VisibleForTesting
  void writeIntoStream(EventTypeMessage eventType, AbstractMessage payload) throws IOException {
    // events processing could be blocked here waiting for an opportunity to write into the same
    // output stream.
    downwardProtocol.write(eventType, payload, outputStream);
  }

  @Override
  public void waitTillAllEventsProcessed() {
    int phase = phaser.getPhase();

    if (!measureWaitedTime) {
      awaitTillProcessed(phase);
    } else {
      long startTime = System.nanoTime();
      awaitTillProcessed(phase);
      long runTimeInMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
      LOG.info("Waited for " + runTimeInMillis + " ms");
    }
  }

  private void awaitTillProcessed(int phase) {
    phaser.awaitAdvance(phase);
  }

  @VisibleForTesting
  int getUnprocessedEventsCount() {
    return phaser.getRegisteredParties();
  }
}
