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

package com.facebook.buck.core.test.event;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.event.WorkAdvanceEvent;
import com.facebook.buck.test.TestStatusMessage;

/** Events posted when a test emits a diagnostic status message event. */
public abstract class TestStatusMessageEvent extends AbstractBuckEvent
    implements LeafEvent, WorkAdvanceEvent {

  private TestStatusMessage testStatusMessage;

  private TestStatusMessageEvent(TestStatusMessage testStatusMessage, EventKey eventKey) {
    super(eventKey);
    this.testStatusMessage = testStatusMessage;
  }

  @Override
  public String getCategory() {
    return "test_message";
  }

  @Override
  public long getTimestampMillis() {
    return testStatusMessage.getTimestampMillis();
  }

  public TestStatusMessage getTestStatusMessage() {
    return testStatusMessage;
  }

  public static Started started(TestStatusMessage testStatusMessage) {
    return new Started(testStatusMessage);
  }

  public static Finished finished(Started started, TestStatusMessage testStatusMessage) {
    return new Finished(started, testStatusMessage);
  }

  public static class Started extends TestStatusMessageEvent {
    public Started(TestStatusMessage testStatusMessage) {
      super(testStatusMessage, EventKey.unique());
    }

    @Override
    public String getEventName() {
      return "TestStatusMessageStarted";
    }

    @Override
    protected String getValueString() {
      return String.format(
          "%s: %s", getTestStatusMessage().getLevel(), getTestStatusMessage().getMessage());
    }
  }

  public static class Finished extends TestStatusMessageEvent {
    private final long elapsedTimeMillis;

    public Finished(Started started, TestStatusMessage testStatusMessage) {
      super(testStatusMessage, started.getEventKey());
      this.elapsedTimeMillis =
          testStatusMessage.getTimestampMillis()
              - started.getTestStatusMessage().getTimestampMillis();
    }

    public long getElapsedTimeMillis() {
      return elapsedTimeMillis;
    }

    @Override
    public String getEventName() {
      return "TestStatusMessageFinished";
    }

    @Override
    protected String getValueString() {
      return String.format(
          "%s: %s (%d ms)",
          getTestStatusMessage().getLevel(),
          getTestStatusMessage().getMessage(),
          elapsedTimeMillis);
    }
  }
}
