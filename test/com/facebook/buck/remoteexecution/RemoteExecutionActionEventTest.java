/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.remoteexecution;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.remoteexecution.RemoteExecutionActionEvent.State;
import com.facebook.buck.util.Scope;
import com.google.common.collect.Lists;
import com.google.common.eventbus.Subscribe;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class RemoteExecutionActionEventTest {
  private final BuildTarget BUILD_TARGET = BuildTargetFactory.newInstance("//test:test");

  private BuckEventBus eventBus;
  private List<RemoteExecutionActionEvent> remoteExecutionActionEvents;
  private List<LeafEvent> leafEvents;

  @Before
  public void setUp() {
    eventBus = BuckEventBusForTests.newInstance();
    eventBus.register(this);
    remoteExecutionActionEvents = Lists.newArrayList();
    leafEvents = Lists.newArrayList();
  }

  @After
  public void tearDown() throws IOException {
    eventBus.close();
  }

  @Test
  public void testSendEventAlsoSendsLeafEvents() {
    int totalEvents = 0;
    for (State state : State.values()) {
      if (RemoteExecutionActionEvent.isTerminalState(state)) {
        continue;
      }
      try (Scope scope =
          RemoteExecutionActionEvent.sendEvent(eventBus, state, BUILD_TARGET, Optional.empty())) {
        Assert.assertEquals(totalEvents + 1, leafEvents.size());
        totalEvents += 2;
      }
    }
  }

  @Test
  public void testNotClosingScopeDoesNotSendFinishedEvent() {
    RemoteExecutionActionEvent.sendEvent(
        eventBus, State.COMPUTING_ACTION, BUILD_TARGET, Optional.empty());
    Assert.assertEquals(1, remoteExecutionActionEvents.size());
  }

  @Subscribe
  public void onRemoteExecutionEvent(RemoteExecutionActionEvent event) {
    remoteExecutionActionEvents.add(event);
  }

  @Subscribe
  public void onLeafEvent(LeafEvent event) {
    leafEvents.add(event);
  }
}
