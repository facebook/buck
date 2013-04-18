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

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.google.common.eventbus.EventBus;

import org.easymock.EasyMock;
import org.junit.Test;

public class TraceEventTest {

  @Test
  public void testLongToPaddedString() {
    assertEquals("00000", TraceEvent.longToPaddedString(0));
    assertEquals("00007", TraceEvent.longToPaddedString(7));
    assertEquals("00016", TraceEvent.longToPaddedString(16));
    assertEquals("00321", TraceEvent.longToPaddedString(321));
    assertEquals("06789", TraceEvent.longToPaddedString(6789));
    assertEquals("48291", TraceEvent.longToPaddedString(48291));
    assertEquals("8675309", TraceEvent.longToPaddedString(8675309));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testLongToPaddedStringThrowsForNegativeNumber() {
    TraceEvent.longToPaddedString(-1);
  }

  @Test
  public void testFormatTime() {
    long time = 566       // 566 millis
        + 11 * 1000       //  11 seconds
        +  6 * 1000 * 60; //   6 minutes
    assertEquals("11.566", TraceEvent.formatTime(time));
  }

  @Test
  public void whenTraceStartedTraceStartEventPosted() {
    EventBus eventBus = EasyMock.createNiceMock(EventBus.class); // Unit test with mock EventBus.
    eventBus.post(EasyMock.anyObject(TraceStart.class));
    EasyMock.replay(eventBus);
    Tracer.initCurrentTrace(0 /* defaultThreshold */, eventBus);
    Tracer.startTracer("test-trace");
    EasyMock.verify(eventBus);
  }

  @Test
  public void whenTraceCommentedTraceCommentEventPosted() {
    EventBus eventBus = EasyMock.createNiceMock(EventBus.class); // Unit test with mock EventBus.
    eventBus.post(EasyMock.anyObject(TraceComment.class));
    EasyMock.replay(eventBus);
    Tracer.initCurrentTrace(0 /* defaultThreshold */, eventBus);
    Tracer.addComment("test-comment");
    EasyMock.verify(eventBus);
  }

  @Test
  public void whenTraceStoppedTraceStopEventPosted() {
    EventBus eventBus = EasyMock.createNiceMock(EventBus.class); // Unit test with mock EventBus.
    eventBus.post(EasyMock.anyObject(TraceStop.class));
    EasyMock.replay(eventBus);
    ThreadTrace.initCurrentTrace(0 /* defaultThreshold */, eventBus);
    ThreadTrace threadTrace = ThreadTrace.threadTracer.get(); // Use ThreadTrace API to capture id.
    long id = threadTrace.startTracer("test-trace", "type");
    threadTrace.traceStarted(TraceEvents.started(id, "test-trace", "type", 0 /* Start time */));
    long threshold = 0;
    threadTrace.stopTracer(id, threshold);
    EasyMock.verify(eventBus);
  }

  @Test
  public void postedEventsAreFormatted() {
    ThreadTrace.initCurrentTrace(0, new EventBus()); // Clear events and mock EventBus.
    ThreadTrace threadTrace = ThreadTrace.threadTracer.get(); // Use ThreadTrace API handlers.
    long id = 42;
    threadTrace.traceStarted(TraceEvents.started(id, "test-trace", "type", 0 /* Start time */));
    threadTrace.traceStopped(TraceEvents.stopped(id, "test-trace", "type", 0));
    assertThat(threadTrace.getFormattedTrace(), containsString("[type] test-trace"));
  }

  @Test
  public void tracesAreFormatted() {
    ThreadTrace.initCurrentTrace(0, new EventBus()); // Clear events.
    Tracer tracer = Tracer.startTracer("test-trace", "type");
    tracer.stop();
    assertThat(Tracer.getFormattedThreadTrace(), containsString("[type] test-trace"));
  }
}
