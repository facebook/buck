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

package com.facebook.buck.downwardapi.processexecutor.context;

import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.downwardapi.protocol.DownwardProtocolType;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.StepEvent;
import com.facebook.buck.event.isolated.DefaultIsolatedEventBus;
import com.facebook.buck.util.timing.FakeClock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Test;

public class DownwardApiExecutionContextTest {

  private static final BuildId BUILD_UUID_FOR_TEST = new BuildId("my_build");
  private static final ActionId ACTION_ID = ActionId.of("my_action_id");

  private static final FakeClock FAKE_CLOCK = FakeClock.doNotCare();
  private static final IsolatedEventBus EVENT_BUS =
      new DefaultIsolatedEventBus(
          BUILD_UUID_FOR_TEST,
          null,
          FAKE_CLOCK,
          FAKE_CLOCK.currentTimeMillis(),
          DownwardProtocolType.BINARY.getDownwardProtocol(),
          ACTION_ID);

  @Test
  public void noConcurrentModificationExceptionWhileClosingTheContext()
      throws InterruptedException {
    DownwardApiExecutionContext downwardApiExecutionContext =
        DownwardApiExecutionContext.of(EVENT_BUS, FAKE_CLOCK, ACTION_ID);

    int eventsCount = 1_000;

    // create events.
    for (int i = 0; i < eventsCount; i++) {
      downwardApiExecutionContext.registerStartStepEvent(
          i, StepEvent.started("shortName_" + i, "description_" + i));
      downwardApiExecutionContext.registerStartChromeEvent(
          i, SimplePerfEvent.started(SimplePerfEvent.PerfEventTitle.of(String.valueOf(i))));
    }

    CountDownLatch startRunning = new CountDownLatch(1);
    // start events removing
    Thread thread =
        new Thread(
            () -> {
              startRunning.countDown();
              for (int i = 0; i < eventsCount; i++) {
                downwardApiExecutionContext.getChromeTraceStartedEvent(i);
                downwardApiExecutionContext.getStepStartedEvent(i);
              }
            });
    thread.start();

    try {
      boolean started = startRunning.await(100, TimeUnit.MILLISECONDS);
      if (!started) {
        Assert.fail("Thread not started");
      }
      downwardApiExecutionContext.close();
    } finally {
      thread.interrupt();
    }
  }
}
