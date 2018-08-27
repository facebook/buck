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
package com.facebook.buck.cli;

import static org.junit.Assert.assertThat;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.WorkAdvanceEvent;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hamcrest.Matchers;
import org.junit.Test;

public class HangMonitorTest {

  private static class WorkEvent extends AbstractBuckEvent implements WorkAdvanceEvent {

    public WorkEvent() {
      super(EventKey.unique());
    }

    @Override
    protected String getValueString() {
      return "work";
    }

    @Override
    public String getEventName() {
      return "WorkEvent";
    }
  }

  @Test
  public void reportContainsCurrentThread() throws Exception {
    AtomicBoolean sleepingThreadShouldRun = new AtomicBoolean(true);
    SettableFuture<Void> sleepingThreadRunning = SettableFuture.create();
    try {
      Thread sleepingThread =
          new Thread("testThread") {
            @Override
            public void run() {
              hangForHangMonitorTestReport();
            }

            private void hangForHangMonitorTestReport() {
              sleepingThreadRunning.set(null);
              try {
                while (sleepingThreadShouldRun.get()) {
                  Thread.sleep(1000);
                }
              } catch (InterruptedException e) {
                // Ignore.
              }
            }
          };
      sleepingThread.start();
      sleepingThreadRunning.get(1, TimeUnit.SECONDS);

      SettableFuture<String> result = SettableFuture.create();
      HangMonitor hangMonitor = new HangMonitor(result::set, Duration.ofMillis(10));
      hangMonitor.runOneIteration();
      assertThat(result.isDone(), Matchers.is(true));
      String report = result.get();
      assertThat(report, Matchers.containsString("hangForHangMonitorTestReport"));
    } finally {
      sleepingThreadShouldRun.set(false);
    }
  }

  @Test
  public void workAdvanceEventsSuppressReport() {
    AtomicBoolean didGetReport = new AtomicBoolean(false);
    HangMonitor hangMonitor =
        new HangMonitor(input -> didGetReport.set(true), Duration.ofMillis(10));
    hangMonitor.onWorkAdvance(new WorkEvent());
    hangMonitor.runOneIteration();
    assertThat(didGetReport.get(), Matchers.is(false));
  }
}
