/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.debug;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;

import java.io.PrintStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracer for debugging slow points in code. Based on Google Closure's goog.debug.Tracer.
 */
final class ThreadTrace {

  static ThreadLocal<ThreadTrace> threadTracer = new ThreadLocal<ThreadTrace>() {
    @Override
    protected ThreadTrace initialValue() {
      return new ThreadTrace();
    }
  };

  private static final int MAX_TRACE_SIZE = 1000;

  private static AtomicLong idGenerator = new AtomicLong();

  private long defaultThreshold;

  /** Events in order. */
  private List<TraceEvent> events = Lists.newArrayList();

  /** Outstanding events that haven't ended yet. */
  private Map<Long, TraceEvent> outstandingEvents = Maps.newHashMap();

  private long startTime;

  private EventBus getEventBus() {
    if (eventBus == null) {
      setEventBus(new EventBus());
    }
    return eventBus;
  }

  private EventBus eventBus = null;

  private void setEventBus(EventBus eventBus) {
    this.eventBus = eventBus;
    this.eventBus.register(this);
  }

  /**
   * Initializes and resets the current trace
   *
   * @param defaultThreshold The default threshold below which the tracer output will be suppressed.
   *     Can be overridden on a per-Tracer basis.
   *
   */
  static void initCurrentTrace(long defaultThreshold) {
    threadTracer.get().reset(defaultThreshold);
  }

  /**
   * Initializes and resets the current trace
   *
   * @param defaultThreshold The default threshold below which the tracer output will be suppressed.
   *     Can be overridden on a per-Tracer basis.
   *
   * @param eventBus The EventBus which TraceEvents are published to.
   */
  static void initCurrentTrace(long defaultThreshold, EventBus eventBus) {
    initCurrentTrace(defaultThreshold);
    threadTracer.get().setEventBus(eventBus);
  }

  /**
   * Clears the current trace.
   */
  static void clearCurrentTrace() {
    threadTracer.get().reset(0);
  }

  static void clearAndPrintCurrentTrace(PrintStream stream) {
    String trace = threadTracer.get().getFormattedTrace();
    if (!Strings.isNullOrEmpty(trace)) {
      stream.println("Thread trace:\n" + Tracer.getFormattedThreadTrace());
    }
    clearCurrentTrace();
  }

  /**
   * Resets the trace.
   *
   * @param defaultThreshold The default threshold below which the tracer output will be suppressed.
   *     Can be overridden on a per-Tracer basis.
   */
  @SuppressWarnings("PMD.UnusedPrivateMethod")
  private void reset(long defaultThreshold) {
    this.defaultThreshold = defaultThreshold;

    this.events.clear();
    this.outstandingEvents.clear();
    this.startTime = System.currentTimeMillis();
  }

  /**
   * Starts a tracer
   *
   * @param comment a comment used to identify the tracer. Does not need to be unique.
   * @param type type used to identify the tracer. If a Trace is given a type (the first argument
   *     to the constructor) and multiple Traces are done on that type then a "TOTAL line will be
   *     produced showing the total number of traces and the sum of the time
   *     ("TOTAL Database 2 (37 ms)" in our example). These traces should be mutually
   *     exclusive or else the sum won't make sense (the time will be double counted if the second
   *     starts before the first ends).
   * @return The identifier for the tracer that should be passed to the the stopTracer method.
   */
  long startTracer(String comment, String type) {
    TraceEvent event = TraceEvents.started(idGenerator.incrementAndGet(), comment, type, -1);
    outstandingEvents.put(event.getId(), event); // Avoid traceStarted/stopTracer race.
    getEventBus().post(event);
    return event.getId();
  }

  @Subscribe
  public void traceStarted(TraceStart event) {
    outstandingEvents.put(event.getId(), event);
    int outstandingEventCount = outstandingEvents.size();
    if (events.size() + outstandingEventCount > MAX_TRACE_SIZE) {
      System.err.println("Giant thread trace. Clearing to avoid memory leak.");

      // This is the more likely case. This usually means that we
      // either forgot to clear the trace or else we are performing a
      // very large number of events
      if (events.size() > MAX_TRACE_SIZE / 2) {
        this.events.clear();
      }

      // This is less likely and probably indicates that a lot of traces
      // aren't being closed. We want to avoid unnecessarily clearing
      // this though in case the events do eventually finish.
      if (outstandingEventCount > MAX_TRACE_SIZE / 2) {
        this.outstandingEvents.clear();
      }
    }
    events.add(event);
  }

  /**
   * Stops a tracer
   *
   * @param id The id of the tracer that is ending.
   * @param silenceThresholdParam Threshold below which the tracer is silenced.
   * @return The elapsed time for the tracer or -1 if the tracer identifier was not recognized.
   */
  @SuppressWarnings("PMD.PrematureDeclaration")
  long stopTracer(long id, Long silenceThresholdParam) {

    long now = System.currentTimeMillis();
    long silenceThreshold;
    if (silenceThresholdParam == null) {
      silenceThreshold = defaultThreshold;
    } else {
      silenceThreshold = silenceThresholdParam;
    }

    TraceEvent startEvent = outstandingEvents.get(id);
    if (startEvent == null) {
      return -1;
    }
    outstandingEvents.remove(id);

    long elapsed = now - startEvent.getEventTime();
    if (elapsed < silenceThreshold) {
      int count = events.size();
      for (int i = count - 1; i >= 0; i--) {
        TraceEvent nextEvent = this.events.get(i);
        if (nextEvent == startEvent) {
          events.remove(i);
          break;
        }
      }

    } else {
      getEventBus().post(TraceEvents.stopped(idGenerator.incrementAndGet(), startEvent.getComment(),
          startEvent.getType(), startEvent.getEventTime()));
    }
    return elapsed;
  }

  @Subscribe
  public void traceStopped(TraceStop event) {
    events.add(event);
  }

  /**
   * Adds a comment to the trace. Makes it possible to see when a specific event
   * happened in relation to the traces.
   *
   * @param comment A comment that is inserted into the trace.
   * @param type    Type used to identify the tracer. If a comment is given a type and multiple
   *                comments are done on that type then a "TOTAL line will be produced showing the
   *                total number of comments of that type.
   */
  void addComment(String comment, String type) {
    getEventBus().post(TraceEvents.comment(idGenerator.incrementAndGet(), comment, type, -1));
  }

  @Subscribe
  public void traceComment(TraceComment event) {
    events.add(event);
  }

  /**
   * Returns a formatted string for the current trace
   *
   * @return a formatted string that shows the timings of the current trace
   */
  String getFormattedTrace() {
    return this.toString();
  }

  /**
   * Returns a formatted string that describes the thread trace.
   *
   * @return {string} A formatted string.
   */
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    long etime = -1;

    long now = System.currentTimeMillis();
    LinkedList<String> indent = Lists.newLinkedList();
    for (int i = 0; i < events.size(); i++) {
      TraceEvent e = events.get(i);
      if (e instanceof TraceStop) {
        indent.removeFirst();
      }
      sb.append(" ");
      sb.append(e.toTraceString(startTime, etime, join(indent)));
      sb.append(" ");
      etime = e.getEventTime();
      sb.append("\n");
      if (e instanceof TraceStart) {
        indent.add("|  ");
      }
    }

    if (!outstandingEvents.isEmpty()) {
      sb.append(" Unstopped timers:\n");
      for (TraceEvent startEvent : outstandingEvents.values()) {
        sb.append("  ");
        sb.append(startEvent);
        sb.append(" (");
        sb.append(now - startEvent.getEventTime());
        sb.append(" ms, started at ");
        sb.append(TraceEvent.formatTime(startEvent.getEventTime()));
        sb.append("\n");
      }
    }
    return sb.toString();
  }

  private String join(List<String> indents) {
    return Joiner.on("").join(indents);
  }

}
